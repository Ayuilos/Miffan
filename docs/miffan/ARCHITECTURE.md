# Miffan Architecture

## Additional character families

`Avatar.WhaleGirl` serializes as `whale_girl`. Its legacy motion-profile field remains
readable for compatibility but no longer changes whale behavior or appears in its UI.
It is independent of `MiffanAppearance` and `MiffanKind`. `isCharacterAvatar`
is the shared identity policy; `isMiffanAvatar` retains its bowl-only meaning.
`AssistantCharacterMascot` dispatches semantic scene inputs to `WhaleGirlMascot` or
`MiffanMascot`. The existing handoff host and successful-reply feedback remain shared.
Assistant pages use the adapter and never draw a character themselves.

`WhaleGirlMascot` now delegates through `WhaleGirlAnimatedPortrait` to the native
`WhaleGirlLineArtPortrait`. This supersedes the earlier atlas-only art restriction:
the user approved the juvenile two-color design on 2026-09-08. Cached Compose paths
share the subsequently approved static face contour; cached eye/mouth/jaw regions,
eye closure, cheeks and semantic mouth details supply eight expressions. Small avatars
receive optical line weight without changing the large reference geometry.
Flat color regions are traced inside the approved contours and cached as Compose paths.
Hair, fins, bow, face and frills keep distinct roles in both palettes, selected by
Material background luminance independently of time-of-day sleeping behavior.
`WhaleGirlActing` samples eased acting beats from the existing foreground clock; it
coordinates food reach, jaw volume, swallowing, breathing and head movement without
creating another timer.
The area outside the head remains transparent. Launcher and platform splash resources
are separate assets and are not changed by this renderer migration.

Pages continue to pass semantic generation phases: unfinished reasoning selects
Thinking, ordinary waiting selects Eating, and text streaming selects Chewing.
Input focus alone never pretends that the model is reasoning. Confirmed reply success
selects the brief proud expression. Errors retain the semantic error badge.

Focused and Typing resolve to dedicated native clips with downward gaze and restrained
tracking. A changed submitId plays a finite Submitted nod, using the existing foreground
clock and completion callback (also with reduced motion); initial composition does not
replay a historical submission. Error and completion interrupt it. After acknowledgement,
the latest generation phase resumes. Attention targets are clamped normalized coordinates
and interpolated inside the renderer. The Lab exposes all eleven clips.

A foreground frame clock pauses below RESUMED without catching up on resume.
Clip/replay changes reset reaction time while eye and cheek parameters transition in
place. Petting completes once per replay, including with reduced motion. Reduced
motion and historical portraits stop ambient clocks and draw meaningful stills;
only an outstanding finite reaction callback needs a clock in reduced motion.
The compatibility entry point retains poster arguments but never loads posters or
RGBA atlases. Existing source footage stays as historical material.

Native visual and playback tests cover the eight expressions, five avatar sizes,
day/night palettes, same-instance transitions, foreground time and replay behavior.
Production Idle is also pixel-compared with the approved static renderer at multiple
sizes in both palettes, and all expressions retain transparent exterior corners.
See `whale-girl/line-art/README.md` for current validation evidence and limitations.

Launcher choice lives in PackageManager component state, not a second settings field.
Three launcher aliases target the always-enabled `RouteActivity` and own both launcher
and SEND filters. Process-text, camera-shortcut and the two OAuth deep-link families
each expose matching icon aliases. Concrete targets stay enabled for explicit intents.
All 15 aliases switch in one transaction; application startup reconciles newly added
external aliases against the existing launcher choice after an upgrade. Android 13+ uses atomic component updates; older APIs
enable the selected alias before disabling alternatives and attempt rollback on failure.
Device launchers control icon refresh timing and existing home-screen placement.
PROCESS_TEXT aliases also declare their selected icon and localized translation label
on the intent filter, exposing both directly through ResolveInfo instead of requiring
ActivityInfo fallback. Tests cover unscoped browser-style queries with flags 0 as well
as GET_RESOLVED_FILTER, alongside actual icon pixels for the two choices and legacy alias migration.

Onboarding writes its collection shortcut through one atomic `SettingsStore.update`:
palette and dedicated assistant creation/selection change together through the shared
trial helper. Turning it off restores only the palette. Its content consumes the
active Material theme; classic colors are used only when the whale collection is off.

`AppStartupAppearanceController` resolves startup identity from the saved whale theme,
launcher choice and color mode. A small preferences mirror serves the loading view
before DataStore emits. Android 12+ receives a stable named splash style through
`SplashScreen.setSplashScreenTheme`, persisted by the OS for subsequent cold starts;
this does not change launcher aliases. The first launch after an upgrade may precede
the initial settings sync; the manifest fallback uses the selected component icon.
Startup uses a static portrait and never decodes animation atlases.

`WhaleThemeDiscoveryMigration` initializes the independent `whale_theme_discovery`
preference once, using an existing launch count or saved provider configuration as
evidence of an upgrade. Fresh installations are already introduced by onboarding.
The persisted discovery timestamp prevents subsequent versions from resetting the
30-day settings badge. `WhaleThemeDiscoveryHost` is mounted only on eligible normal
chat launches, with saveable dialog state and durable acknowledgement; external
intents and database migration do not consume the introduction. A single preview
player cycles clips only while resumed and respects reduced motion.
Trial/restore use atomic SettingsStore transforms and a saved palette snapshot.
`dedicatedAssistantId` identifies the independently created assistant; repeated trials reuse
it without overwriting edits. Missing/deleted ids cause creation only on confirmation.
`createWhaleAssistant(id)` supplies a complete editable preset and resets all configuration
using the existing id. Palette restore does not mutate assistants. Legacy avatar backup
fields remain deserializable but are no longer applied. Successful trial navigation opens
a fresh chat after persistence, keeping previous conversation ownership unchanged.
The optional PackageManager change completes in a short non-cancellable operation
with appearance persistence and rolls back its icon choice if persistence fails.
The startup launch counter also uses an atomic SettingsStore transform so it cannot
overwrite a concurrently persisted introduction acknowledgement with stale settings.

## Model boundary

`Avatar.Miffan` is the persistent assistant-avatar value. It owns a serializable `MiffanAppearance` and a separate `MiffanMotionProfile`. Appearance stores a preset palette plus a palette/theme color-source choice; Character V1 adds a curated Miffan kind; Motion V1 stores Lively, Calm, or Curious.

`Avatar.Dummy` remains valid for backward compatibility and for the procedural user avatar. In assistant-only UI it is interpreted as legacy Miffan Classic. New assistants default to `Avatar.Miffan()`.

The model layer contains no Compose colors or drawing primitives. UI code resolves palette identifiers or the active Material `ColorScheme` into `MiffanColors`, and character kinds into one content/material/accessory treatment, keeping serialized data stable if visual details are tuned later.

## Rendering boundary

`MiffanMascot` is the renderer. Its public inputs are semantic:

- appearance;
- motion profile;
- mascot state;
- time-of-day phase;
- input scene state;
- attention target and event identifiers.

`AssistantAvatar` is the policy adapter. It chooses animated Miffan for `Avatar.Miffan` and legacy `Avatar.Dummy`, while delegating custom emoji/image avatars to `UIAvatar`.

Feature pages must not inspect palette colors or duplicate mascot drawing logic. They pass avatar/model state through the adapter.

## Scene coordination

The chat page owns transient scene state. `ChatInput` emits input activity; `ChatPage` maps it to mascot scene input; `ChatList` renders the mascot. Neither the input nor mascot holds a reference to the other.

Use monotonically increasing event identifiers for one-shot reactions such as attention and submit. Use enum/state values for durable conditions such as focused, typing, loading, and error.

`MiffanHandoff` owns only layout interpolation. `ChatList` supplies measured empty/waiting slots
through `MiffanHandoffAnchor`; one renderer stays outside lazy item lifetimes. Root coordinates
are translated into the clipped scene viewport. Destination changes animate, while movement
within a settled slot (including scrolling) tracks directly. Missing/offscreen slots fade out
without leaving an interactive ghost. Each conversation has its own transient host.

`ChatService.assistantReplyCompleted` is a non-replaying, best-effort feedback stream emitted
only after a reply succeeds and is saved without pending approvals. It is separate from the
existing generation-done stream, which also covers non-reply operations. `ChatMascotScene`
awaits the originating job, rejects cancellation or replacement, and expires the reply feedback;
it does not change session ownership or queue dispatch. `AssistantAvatar` never infers success
from a falling loading flag. Submit reactions follow active job changes, not enqueue button taps.

`MiffanPresentation.Avatar` lowers active movement and stops ambient scheduling at rest.
Renderer cycles settle before stopping; saved appearance/profile data remains unchanged.
`MiffanSystemMotion` shares one application-context observer of the system animator setting;
it is released when no active avatar subscribes. The renderer also honors a disabled Compose
`MotionDurationScale`. Both system reduction and explicit preview reduction stop ambient timers
and skip spatial interpolation. No feature page reads Android animation settings itself.

## Conversation follow-up queue

The native composer submits new messages to a FIFO queue owned by `ConversationSession`.
The queue is scoped to the conversation, not the screen or assistant, and keeps its session
alive when the user navigates away. It is in-memory state; process termination does not restore
unsent messages. No provider-specific steering API is required.

An immediate send cancels the current turn, waits for all interrupted turns to finish cleanup,
and starts the chosen message with the current conversation context. Other queued messages
retain their order. Inputs interrupted before being added to history are returned to the queue.
Old completion callbacks must never clear a replacement job or advance its queue.

Successful turns automatically dispatch the next message. Pending tool approvals block dispatch.
Stopping or failing a turn pauses the queue without deleting it; the user can resume, send a
specific item immediately, or remove an item. Conversation deletion discards the queue and
awaits generation cleanup before deleting history. REST sends retain their immediate behavior.

## Compatibility rules

- Decoding legacy `dummy` avatars must continue to succeed.
- A legacy assistant `Dummy` renders exactly like Miffan Classic.
- A legacy assistant `Dummy`, or Miffan data without a kind field, resolves to Rice.
- Miffan data without a color-source field uses its saved palette.
- Legacy `Dummy` and Miffan data without a motion field use the Curious profile.
- Selecting a Miffan palette writes an explicit `Avatar.Miffan` value.
- Resetting an assistant avatar writes `Avatar.Miffan()`; resetting the user avatar continues to write `Avatar.Dummy`.
- Copying an assistant preserves a Miffan appearance. Image avatars may still reset according to the existing file-ownership policy.
- Changing kind or palette preserves motion profile, and changing motion profile preserves the complete appearance.
- Enabling theme sync does not erase the saved palette; disabling it restores that palette.

## Workspace ownership and Assistant scopes

A Workspace owns one Rootfs and one process/session coordination domain. Multiple Assistants may
bind that Workspace; package installation and changes under `/bin`, `/usr`, `/etc`, and the rest of
the Rootfs are intentionally shared. The session registry remains keyed by Workspace, with one
active session per Workspace, so different Assistant scopes do not concurrently mutate the shared
Rootfs in the first implementation.

Each new Assistant binding also stores a stable file-scope identity equal to the Assistant UUID.
The host layout is `scopes/<assistant-id>/{files,home,tmp,var-tmp,proot-tmp}` below the Workspace,
while the guest consistently sees that scope as `/workspace`, `/root`, `/tmp`, and `/var/tmp`.
Sibling scope roots are not mounted. Model file tools, Shell cwd validation, completion, file
pickers, Skills, and Artifact UI all use the same `(workspaceId, scopeId)` mapping.

Conversation artifacts use a prompt-level convention within that file scope:
`/workspace/conversations/<conversation-id>/`. The stable conversation UUID is passed through the
generation pipeline to `WorkspaceReminderTransformer`. The Agent is instructed to create this
directory when first saving an artifact and reuse it across turns, regenerations, title changes,
and cwd changes. Newly generated outputs and related task files belong there unless the user
explicitly requests another location or an in-place project edit. `workspaceCwd` remains the input
and project context. This adds no filesystem enforcement, automatic directory creation, or legacy
file migration; existing scope boundaries, tool approvals, and artifact publishing are unchanged.

Missing `workspaceScopeId` is an explicit legacy whole-workspace mode. It continues to expose the
historical `files/` directory without moving data. Re-selecting the same binding keeps this mode.
Artifacts created after this architecture persist scope identity; historical Artifacts without it
stay in the legacy view. `.miffan/skills` is private to the selected file scope, with no implicit
shared Skills scan. Persistent Shell approval is stored per Assistant binding and resets when the
binding changes.

These are repository, validation, and mount boundaries for normal product operations, not a claim
that PRoot isolates malicious commands. PRoot processes run under the Miffan application UID; the
full residual trust boundary is documented in `workspace/SECURITY.md`.

## Evolution path

Character V1 stores one curated kind in `MiffanAppearance`. Each kind resolves in the renderer to a coherent content, material, and accessory treatment. Future customization fields also belong in `MiffanAppearance`, with defaults for backward-compatible decoding:

- material or surface pattern;
- bowl contents;
- accessory set;
- optional custom color tokens.

`MiffanMotionProfile` resolves to one immutable `MiffanMotionTuning` table. The renderer applies those parameters to shared breathing, gaze, attention, input, submit, and semantic-state animations. Page inputs should converge on a single `MiffanSceneState`; appearance and motion profile must not encode runtime animation state.

`MiffanMotion.kt` owns renderer-only face parameters, gaze destinations, and attention timing.
Face parameters and attention targets use persistent Compose springs; cancelling an attention timer
must not reset an animated value. The drawing layer reads animation state during drawing, and uses
one mouth contour for idle, thinking, happy, error, and input expressions. Semantic blend weights
smooth body bobbing, signature strength, and error settling without changing serialized models.

`MiffanKind` also resolves to one immutable `MiffanKindBehavior`. This renderer-owned table selects a single signature motion and its relative strength for idle, focused, typing, thinking, submitted, happy, and error conditions. The final frame is semantic state × motion tuning × kind behavior. No signature behavior is serialized, and feature pages must not branch on character kind.

Theme-aware color resolves from `MaterialTheme.colorScheme` inside the renderer. It must not read `SettingsStore`, theme IDs, dynamic-color flags, or custom-theme records. This keeps the mascot coupled only to Material semantic color roles and makes every upstream theme source update automatically.

## Validation

The Debug page contains Miffan Lab, a deterministic visual matrix for palette, motion profile, semantic state, size, and time-of-day inspection. Data-model, tuning-order, and avatar-policy behavior require JVM tests. Every milestone runs Kotlin compilation, focused tests, and a Debug APK build before delivery.
