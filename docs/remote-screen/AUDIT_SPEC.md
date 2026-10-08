# Approval audit trail: data layer

Owner: Codex (non-UI). Reviewer and UI: Claude. Branch base: `feature/remote-screen`.

The user wants a complete trail of permission decisions: every approval request and how it was settled, every action that ran without asking because of a standing permission, and every time the user took over or handed back a remote screen. The trail must survive deleting the conversation. Claude builds all screens on top of the API below; this task only adds data, recording and queries.

## 1. Per-call record on the tool part (inline display)

Add to `UIMessagePart.Tool` (`ai/src/main/java/me/rerere/ai/ui/UIMessagePart.kt`) an optional, serializable field `approvalRecord: ToolApprovalRecord? = null` (default null so stored messages still decode, and `merge()` keeps it):

```kotlin
@Serializable
data class ToolApprovalRecord(
    /** Epoch millis when the call started waiting for the user (null if it never asked). */
    val requestedAt: Long? = null,
    /** Epoch millis when it was settled or auto-allowed. */
    val decidedAt: Long? = null,
    val decision: ToolDecision? = null,
    val via: ToolDecisionVia? = null,
)

@Serializable enum class ToolDecision { ALLOWED, DECLINED, ANSWERED, REPLIED_IN_CHAT, CANCELLED, AUTO_ALLOWED }

@Serializable enum class ToolDecisionVia {
    CARD,            // Allow / Not this time / an answer on the card in the chat
    PARTNER_SCREEN,  // the compact approval card on the partner screen page
    ALWAYS_ALLOW,    // the user pressed "always allow" for shell on the card
    STANDING_ALWAYS_ALLOW, // ran without asking because of an earlier "always allow" for this target
    NO_ASK_SETTING,  // ran without asking because asking is off (shell approval off, computer "Automatic")
    CHAT_REPLY,      // the user wrote in chat instead (settleUnfinishedTools, repliedInChat)
    STOP,            // generation stopped / interrupted
}
```

Where to fill it:
- `GenerationHandler`: when a call is set to `Pending`, set `requestedAt`. When a call would be gated but runs without asking because of a standing permission, set `decision = AUTO_ALLOWED`, `decidedAt`, and `via`. To know the reason, add to `me.rerere.ai.core.Tool` an optional `autoApprovedBy: (JsonElement) -> ToolDecisionVia? = { null }` returning `STANDING_ALWAYS_ALLOW` or `NO_ASK_SETTING` when `needsApproval` is false only because of such a permission; leave it null for tools that never need approval (reads, observations). Implement it for `workspace_shell` (`data/ai/tools/` workspace tools: always-allow target vs. approval off) and for computer action tools (`data/ai/tools/ComputerTools.kt`: `ComputerUseMode.AUTO`; observation tools stay null; foreground tools always ask).
- `ChatService.handleToolApproval`: `ALLOWED` / `DECLINED` / `ANSWERED` with `via` passed in by the caller (new parameter, default `CARD`). The only call-site change allowed in UI code: `ui/im/computer/PartnerScreenPage.kt` passes `PARTNER_SCREEN` (one argument, no other UI edits).
- `ChatService.alwaysAllowWorkspaceShell`: `ALLOWED` via `ALWAYS_ALLOW`.
- `settleUnfinishedTools` (`service/ChatService.kt`): `REPLIED_IN_CHAT` via `CHAT_REPLY`, or `CANCELLED` via `STOP`; keep `requestedAt`.

## 2. Audit log table (survives conversation deletion)

New Room entity `audit_event` with a migration and a schema export, following the existing migrations and their tests under `app/src/androidTest/.../data/db/migrations/`:

| column | type | notes |
|---|---|---|
| id | String PK | random UUID |
| at | Long | epoch millis of the event |
| kind | String | `APPROVAL` (a gated call was settled or auto-allowed), `SCREEN_TAKEN_OVER`, `SCREEN_HANDED_BACK` |
| assistant_id | String? | partner, when known |
| conversation_id | String? | |
| message_id | String? | |
| tool_call_id | String? | unique together with kind APPROVAL, so re-settling a call never duplicates a row |
| host_id | String? | remote host for shell/computer calls and screen events |
| host_name | String? | display name captured at the time (hosts can be renamed or deleted) |
| tool_name | String? | |
| summary | String | human-readable action captured at the time, built with the existing pure `approvalNotificationSummary` logic (e.g. "Run command: uname -a", "On cachyos: Click") in the app's current locale |
| decision | String? | `ToolDecision` name |
| via | String? | `ToolDecisionVia` name |
| requested_at | Long? | |

- Record an `APPROVAL` row whenever section 1 sets a final `decision` (not for `Pending`), for every tool that can ask (that has an approval policy), including `AUTO_ALLOWED`. Observation/read-only tools are never logged.
- Record `SCREEN_TAKEN_OVER` / `SCREEN_HANDED_BACK` from `RemoteComputerControl` (`data/ai/computer/RemoteComputerControl.kt`) on real transitions only (`userTakesOver` when the controller was not already USER; `userHandsBack` when it was USER). Resolve `host_name` from `WorkspaceRepository` hosts; `assistant_id` stays null (the screen is shared).
- Write off the generation hot path (a repository with its own scope / `Dispatchers.IO`), never blocking or failing the tool execution: log and swallow recording errors.
- Retention: keep the newest 5000 rows; trim on insert.
- Deleting a conversation or partner does not delete rows.

Repository API for Claude's UI (`data/audit/AuditRepository.kt`, registered in Koin):

```kotlin
data class AuditEvent(/* mirrors the table, with enums parsed; unknown values → null */)
fun observe(assistantId: Uuid?, hostIds: Set<String>, limit: Int): Flow<List<AuditEvent>> // newest first:
// rows of this partner, plus screen events on the given hosts
suspend fun clear(assistantId: Uuid?) // for a future "clear history" action
```

## 3. Settings history

No data change needed: assistant revisions already store full snapshots. Claude labels permission fields in the UI.

## Boundaries
- Do not edit Compose UI files except the single `PartnerScreenPage.kt` argument above.
- Keep approval semantics unchanged (who is asked, when). Only record.
- Do not commit or run git write commands; Claude reviews and commits.

## Verification
- Unit tests: record filling for each path (pending → allowed/declined/answered, always allow, chat reply, stop, auto-allowed by standing always-allow and by no-ask setting, observation not recorded), JSON round trip of an old tool part without the field, audit row building, duplicate suppression, retention trim.
- Migration test (androidTest) compiles: `./gradlew :app:compileDebugAndroidTestKotlin --offline`.
- `./gradlew :app:testDebugUnitTest --offline` and `./gradlew :app:assembleDebug --offline` pass.
- On emulator-5560 (`me.ayuilos.miffan.app.debug`, easy mode): trigger a shell approval ("run uname -a in the shell") and tap "Not this time" (never Allow); reply in chat to a second card; then dump the table (`adb shell run-as me.ayuilos.miffan.app.debug` + sqlite3 if available, or a temporary debug log removed before finishing) and show the rows. If the emulator freezes (qemu at 0% CPU and adb shell hanging), cold-boot it with `emulator -avd RikkaHub_API_35_16K -port 5560 -no-snapshot-load`.
- Write `docs/remote-screen/AUDIT_NOTES.md`: what changed, the API, verification output.
