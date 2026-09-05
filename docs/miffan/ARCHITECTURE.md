# Miffan Architecture

## Additional character families

`Avatar.WhaleGirl` serializes as `whale_girl`. Its legacy motion-profile field remains
readable for compatibility but no longer changes whale behavior or appears in its UI.
It is independent of `MiffanAppearance` and `MiffanKind`. `isCharacterAvatar`
is the shared identity policy; `isMiffanAvatar` retains its bowl-only meaning.
`AssistantCharacterMascot` dispatches semantic scene inputs to `WhaleGirlMascot` or
`MiffanMascot`. The existing handoff host and successful-reply feedback remain shared.
Assistant pages use the adapter and never draw a character themselves.

`WhaleGirlMascot` renders transparent stills and H3 Max footage processed into RGBA
frame atlases. The same assets work on every app surface, with no light/dark portrait
variant or rounded background tile. Launcher icons also use the positive transparent
idle head, on their intentional light/deep-sea adaptive icon backgrounds.
Source illustrations and prompts are in `whale-girl/ASSETS.md`; do not redraw the face
with Compose paths or introduce another manually approximated character.

Eight clips cover Idle, Petting, Success, Surprise, Eating, Chewing, Thinking and
Sleeping. The first five looping conditions (Idle, Eating, Chewing, Thinking, Sleeping)
use 48 frames / 4 seconds / 12 fps; the three reactions use 36 frames / 1.5 seconds /
24 fps. Frames are 384 px in eight-column atlases. No runtime API calls or video
decoders are needed. Pages pass semantic generation phase: a real unfinished reasoning
part selects Thinking, ordinary waiting selects Eating and text streaming selects
Chewing. Input focus alone never pretends that the model is reasoning.

`WhaleGirlTimeline` counts foreground frame-clock time, wraps loops and holds the final
reaction frame. State changes crossfade for 160 ms. Atlas loading happens on the IO
dispatcher with ARGB_8888; the shared cache uses a 32 MiB allocation budget. A poster
is visible only while its atlas is unavailable, never underneath transparent frames.
Eviction does not recycle a bitmap another portrait may still display. Missing assets
fall back to the transparent poster.

Historical idle avatars and reduced-motion previews render only the appropriate still
and never enter atlas loading or playback. Playback pauses below RESUMED and resumes
without catching up background time. The existing semantic handoff and one-shot tap
and submission inputs are preserved. `characterReplyHoldMillis` gives the whale's
1.5-second authored celebration a fixed 1.7-second display window; other avatars keep
their previous reply-feedback duration.

The current generation records and transparency pipeline are in
`output/whale-girl-motion-v2/`; earlier opaque samples remain in
`output/whale-girl-motion/` as historical references. Background removal must preserve
white headband frills, rice, bowl, fine hair and the sleep bubble. Source windows and
frame counts are recorded in metadata and checked against runtime by device tests.

Launcher choice lives in PackageManager component state, not a second settings field.
Three launcher aliases target the always-enabled `RouteActivity`, preserving incoming
shares and explicit shortcuts. Android 13+ uses atomic component updates; older APIs
enable the selected alias before disabling alternatives and attempt rollback on failure.
Device launchers control icon refresh timing and existing home-screen placement.

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
