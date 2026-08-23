# Miffan Acceptance Checklist

## Identity and persistence

- A newly created assistant uses Miffan Classic without requiring avatar setup.
- A legacy assistant with `Avatar.Dummy` still renders Miffan Classic.
- Each of the six palettes persists after leaving and reopening assistant settings.
- Each of the four character kinds persists after leaving and reopening assistant settings.
- Each of the three motion profiles persists after leaving and reopening assistant settings.
- Changing palette does not reset motion profile, and changing motion profile does not reset palette.
- Changing character kind preserves palette and motion profile.
- Theme sync can be enabled per assistant and persists after reopening settings.
- Disabling theme sync restores the assistant's previously selected Miffan palette.
- Two assistants can use different palettes and remain visually distinguishable in the picker, drawer, message list, and settings.
- Custom emoji and image avatars continue to render and edit normally.
- Resetting an assistant custom avatar returns to Miffan Classic.

## Rendering

- Bowl, face, and contents remain readable at 28 dp, 32 dp, 40 dp, 80 dp, and the large empty-chat size.
- Rice, Sprout, Dumpling, and Stargazer remain distinguishable by silhouette or content at 40 dp and above.
- Material details support recognition at large sizes without making the face noisy at avatar sizes.
- Every palette has sufficient contrast in light and dark app themes.
- Theme-synced colors update for dynamic, preset, and custom themes in both light and dark mode.
- Theme sync changes colors without changing kind, material, contents, accessories, or motion profile.
- Thinking, happy, error, idle, focused, and typing states work for every palette.
- Decorative input cues use palette-derived colors rather than Classic-only hard-coded colors.
- No palette changes geometry or interaction hit targets.
- Lively, Calm, and Curious share the same semantic states without duplicated drawing implementations.
- Rice, Sprout, Dumpling, and Stargazer each have one recognizable motion signature at 80 dp and above.
- Character signatures stay restrained during idle and become clearer for thinking, typing, submit, and happy states.
- Error states settle their signature instead of looking celebratory.
- Motion profile still controls the overall tempo and amplitude of every character signature.
- Reduced motion preserves each signature's meaning with substantially lower movement.

## Interaction

- Tap reactions transition smoothly and use light compression.
- Eyes attend to taps in the chat's available blank space.
- Focusing and typing in the input produces a distinct but restrained reaction.
- Keyboard movement does not cover half of the mascot.
- Sending triggers the one-shot submit reaction and the mascot remains present while the assistant responds.
- Loading completion transitions briefly through happy and then returns to the appropriate idle phase.
- Lively is visibly quicker but keeps tap compression light.
- Calm has the smallest movement amplitude and slowest rhythm.
- Curious moves its eyes before its delayed body lean.

## Regression and delivery

- Avatar serialization round-trips for all Miffan presets.
- Avatar serialization round-trips for every palette and motion-profile combination.
- Avatar serialization round-trips for every character-kind, palette, and motion-profile combination.
- Miffan JSON without a character-kind field decodes as Rice.
- Miffan JSON without a color-source field keeps using its saved palette.
- Legacy `dummy`, emoji, and image avatar decoding tests pass.
- Avatar-policy tests cover Miffan, legacy Dummy, and custom-avatar preference behavior.
- `./gradlew :app:compileDebugKotlin` passes.
- Focused JVM unit tests pass.
- `./gradlew assembleDebug` passes.
- Universal and arm64 Debug APKs are produced and signature verification succeeds.
