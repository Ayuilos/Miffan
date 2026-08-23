# Miffan Acceptance Checklist

## Identity and persistence

- A newly created assistant uses Miffan Classic without requiring avatar setup.
- A legacy assistant with `Avatar.Dummy` still renders Miffan Classic.
- Each of the six palettes persists after leaving and reopening assistant settings.
- Two assistants can use different palettes and remain visually distinguishable in the picker, drawer, message list, and settings.
- Custom emoji and image avatars continue to render and edit normally.
- Resetting an assistant custom avatar returns to Miffan Classic.

## Rendering

- Bowl, face, and contents remain readable at 28 dp, 32 dp, 40 dp, 80 dp, and the large empty-chat size.
- Every palette has sufficient contrast in light and dark app themes.
- Thinking, happy, error, idle, focused, and typing states work for every palette.
- Decorative input cues use palette-derived colors rather than Classic-only hard-coded colors.
- No palette changes geometry or interaction hit targets.

## Interaction

- Tap reactions transition smoothly and use light compression.
- Eyes attend to taps in the chat's available blank space.
- Focusing and typing in the input produces a distinct but restrained reaction.
- Keyboard movement does not cover half of the mascot.
- Sending triggers the one-shot submit reaction and the mascot remains present while the assistant responds.
- Loading completion transitions briefly through happy and then returns to the appropriate idle phase.

## Regression and delivery

- Avatar serialization round-trips for all Miffan presets.
- Legacy `dummy`, emoji, and image avatar decoding tests pass.
- Avatar-policy tests cover Miffan, legacy Dummy, and custom-avatar preference behavior.
- `./gradlew :app:compileDebugKotlin` passes.
- Focused JVM unit tests pass.
- `./gradlew assembleDebug` passes.
- Universal and arm64 Debug APKs are produced and signature verification succeeds.
