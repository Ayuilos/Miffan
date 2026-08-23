# Miffan Architecture

## Model boundary

`Avatar.Miffan` is the persistent assistant-avatar value. It owns a serializable `MiffanAppearance` and a separate `MiffanMotionProfile`. Appearance V1 stores a preset palette identifier; Character V1 adds a curated Miffan kind; Motion V1 stores Lively, Calm, or Curious.

`Avatar.Dummy` remains valid for backward compatibility and for the procedural user avatar. In assistant-only UI it is interpreted as legacy Miffan Classic. New assistants default to `Avatar.Miffan()`.

The model layer contains no Compose colors or drawing primitives. UI code resolves palette identifiers into `MiffanColors` and character kinds into one content/material/accessory treatment, keeping serialized data stable if visual details are tuned later.

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

## Compatibility rules

- Decoding legacy `dummy` avatars must continue to succeed.
- A legacy assistant `Dummy` renders exactly like Miffan Classic.
- A legacy assistant `Dummy`, or Miffan data without a kind field, resolves to Rice.
- Legacy `Dummy` and Miffan data without a motion field use the Curious profile.
- Selecting a Miffan palette writes an explicit `Avatar.Miffan` value.
- Resetting an assistant avatar writes `Avatar.Miffan()`; resetting the user avatar continues to write `Avatar.Dummy`.
- Copying an assistant preserves a Miffan appearance. Image avatars may still reset according to the existing file-ownership policy.
- Changing kind or palette preserves motion profile, and changing motion profile preserves the complete appearance.

## Evolution path

Character V1 stores one curated kind in `MiffanAppearance`. Each kind resolves in the renderer to a coherent content, material, and accessory treatment. Future customization fields also belong in `MiffanAppearance`, with defaults for backward-compatible decoding:

- material or surface pattern;
- bowl contents;
- accessory set;
- optional custom color tokens.

`MiffanMotionProfile` resolves to one immutable `MiffanMotionTuning` table. The renderer applies those parameters to shared breathing, gaze, attention, input, submit, and semantic-state animations. Page inputs should converge on a single `MiffanSceneState`; appearance and motion profile must not encode runtime animation state.

## Validation

The Debug page contains Miffan Lab, a deterministic visual matrix for palette, motion profile, semantic state, size, and time-of-day inspection. Data-model, tuning-order, and avatar-policy behavior require JVM tests. Every milestone runs Kotlin compilation, focused tests, and a Debug APK build before delivery.
