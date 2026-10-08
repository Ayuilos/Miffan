# Notifications for easy mode: live updates and approvals

Owner: Codex (non-UI). Reviewer: Claude. Branch base: `feature/remote-screen`.

## Problems reported by the user (OPPO Find X6 Pro, ColorOS "流体云" = Android promoted ongoing notifications)

1. While chatting in easy mode with the app in the background, no live update appears in the Fluid Cloud.
2. When the partner stops for an approval card (shell command, computer action, etc.), no notification is posted, so the user does not know the partner is waiting.

## What is known

- `service/ChatNotificationManager.kt` posts the live update (`requestPromotedOngoing`, `shortCriticalText`) on `AppEvent.ChatGenerationUpdate` and a "done" notification on `AppEvent.ChatGenerationEnded`. Both are emitted from `ChatService.handleMessageComplete` and do not depend on the interface mode.
- Both gates default to off: `DisplaySetting.enableNotificationOnMessageGeneration = false` and `enableLiveUpdateNotification = false` (`data/datastore/PreferencesStore.kt`). Nothing in easy mode turns them on. The switches live on `SettingPreferencesNotificationPage`, reachable from easy mode under Me.
- A generation that stops for an approval ends normally, so today it is treated as "done": at most a plain done notification whose text is the start of the partner's message, and only when the switch above is on.
- Tapping a notification with the `conversationId` extra already opens the partner's thread in easy mode (`ui/im/ImChatRedirect.kt`); keep that working.
- Easy-mode messages are sent with `immediately = false` and dispatched from the session queue (`ConversationSession`, `ChatService.sendMessageNow`); check that this path keeps the foreground service and the events identical to professional mode.

## Required behavior

### A. Live updates and reply notifications on by default
- Change both defaults to `true`, so a user who never touched the switches gets reply notifications and live updates. Explicit choices already saved by a user must keep working (the settings use kotlinx defaults, so only users without a saved value change).
- Confirm on the emulator that, with defaults, an easy-mode reply in the background posts the live update notification on `CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID` with the promoted-ongoing request, updates while streaming, and is cancelled at the end. Also find and fix any other reason easy mode skips it (queue dispatch, foreground service acquisition, `isForeground` tracking, sender name). Write down what you found in the notes file.
- If `POST_NOTIFICATIONS` is never requested on a fresh easy-mode install, report it in the notes; do not build any new screen or dialog for it (UI belongs to Claude).

### B. Approval notification
- When a generation stops with at least one pending approval (`Conversation.hasPendingToolApprovals()`, `ToolApprovalState.Pending`) and the app is in the background, post an approval notification instead of the "done" notification.
- New channel, importance high: id `chat_approval`, name `notification_channel_approval`. It is not gated by `enableNotificationOnMessageGeneration`; approvals block the partner, and the user can still turn the channel off in system settings. It still requires the notification permission.
- One notification per conversation (stable id derived from the conversation id, distinct from the live update id 1 / foreground service id and the done id). Category `CATEGORY_MESSAGE` or `CATEGORY_REMINDER`, `autoCancel = true`, content intent identical to the existing one (`conversationId` extra), so tapping opens the partner's thread with the card.
- Title: `notification_approval_title` with the sender name. Text: a summary of the first pending call, plus `notification_approval_more` when there are more:
  - `workspace_shell`: `notification_approval_shell` with the command (first line, at most 80 characters).
  - `computer_*`: `notification_approval_computer` with the computer name (`workspaceTarget.remoteHostName`, fallback `remoteHostLabel`) and the action label from `computerActionTitle()` in `ui/components/message/ComputerToolUI.kt` (label string plus detail when present; move or share that function so the service can use it without depending on Compose).
  - Any other tool: `notification_approval_generic` with the tool name.
- No Allow/Deny action buttons in the notification: the user must see the full card before approving.
- Cancel the approval notification when the conversation no longer has pending approvals: approved, denied, answered, replied in chat (`settleUnfinishedTools`), generation stopped, or the conversation deleted. Emit an event or observe the conversation; keep it out of the generation hot path.
- Keep the summary building in a pure function and unit-test it (shell, computer with and without detail, generic, more than one pending).

## Strings (add to `values` with `tools:ignore="MissingTranslation"` like neighbours, and `values-zh`; then translate into `values-zh-rTW`, `values-ja`, `values-ko-rKR`, `values-ru` using the terms already used there, e.g. partner)

| name | en | zh |
|---|---|---|
| notification_channel_approval | Waiting for your OK | 等你确认 |
| notification_approval_title | %1$s needs your OK | %1$s 需要你的允许 |
| notification_approval_shell | Run command: %1$s | 运行命令：%1$s |
| notification_approval_computer | On %1$s: %2$s | 在 %1$s 上：%2$s |
| notification_approval_generic | Use %1$s | 使用 %1$s |
| notification_approval_more | %1$s, and %2$d more | %1$s，另外还有 %2$d 项 |

## Boundaries
- Do not touch Compose UI files except to move `computerActionTitle` out of `ComputerToolUI.kt` into a non-UI file it can keep importing.
- Do not change approval semantics, `GenerationHandler`, or tool definitions.
- Do not commit or run git write commands; Claude reviews and commits.

## Verification
- `./gradlew :app:testDebugUnitTest --offline` passes (all tests).
- `./gradlew :app:assembleDebug --offline` builds.
- On emulator-5560 (debug app `me.ayuilos.miffan.app.debug`, already configured, easy mode): put the app in the background during a reply and check `adb shell dumpsys notification --noredact` for the live update; trigger an approval (ask the partner to run `uname -a` in the shell; the card appears before anything runs) with the app in the background and check the approval notification and its text; then open the app, tap "Not this time", and check that the notification is gone. Never tap Allow: nothing may run on the user's machine.
- Write `docs/remote-screen/NOTIFY_NOTES.md`: root causes found, what changed, verification output.
