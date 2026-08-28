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
- Neutral and thinking mouths stay round; mouth corners remain smooth through expression changes, taps, and yawns rather than forming pointed almond shapes.
- Update-available state remains readable for every character and palette at avatar sizes.
- An idle Miffan on the empty chat page receives the update-available semantic state; chat errors still take priority.
- Decorative input cues use palette-derived colors rather than Classic-only hard-coded colors.
- No palette changes geometry or interaction hit targets.
- Lively, Calm, and Curious share the same semantic states without duplicated drawing implementations.
- Rice, Sprout, Dumpling, and Stargazer each have one recognizable motion signature at 80 dp and above.
- Each signature changes the mascot silhouette or whole-body pose, not only a small interior detail.
- Character signatures stay restrained during idle and become clearer for thinking, typing, submit, and happy states.
- Error states settle their signature instead of looking celebratory.
- Motion profile still controls the overall tempo and amplitude of every character signature.
- Reduced motion preserves each signature's meaning with substantially lower movement.
- Miffan Lab provides an isolated full-strength Signature mode without shared idle gestures.

## Interaction

- Tap reactions transition smoothly and use light compression.
- Eyes attend to taps in the chat's available blank space.
- Focusing and typing in the input produces a distinct but restrained reaction.
- Keyboard movement does not cover half of the mascot.
- Sending triggers the one-shot submit reaction and the mascot remains present while the assistant responds.
- A confirmed successful reply transitions briefly through happy and then returns to idle; cancellation, failure, user-only sends, and pending tool approval never celebrate.
- First active send shrinks the same empty-chat mascot into the waiting slot without restarting its face or gaze.
- Waiting avatars remain aligned while scrolling; offscreen/disposed slots leave no visible or interactive ghost.
- Short conversations already at the bottom do not repeatedly request the same scroll position during generation.
- A quick completion or cancellation during the handoff fades safely; changing conversation does not reuse the previous scene.
- Enqueueing during generation does not replay submit/handoff, and the next active generation clears an old celebration.
- Historical avatars remain still; active 28/32/48 dp avatars have restrained motion.
- Retry/loading overrides old reminders, errors override celebration, and focused/typing input overrides update reminders.
- Turning system animations off and back on updates the mascot without reopening the screen. With animations off, expressions remain readable and handoff/ambient movement stops.
- Lively is visibly quicker but keeps tap compression light.
- Calm has the smallest movement amplitude and slowest rhythm.
- Curious moves its eyes before its delayed body lean.
- Face changes stay continuous when interrupted by a new semantic state.
- Rapid taps in opposite directions do not reset the body pose; the latest reaction settles fully.
- Thinking gaze holds a destination and periodically returns to center, while input and taps override it.
- Entering error settles character signatures gradually rather than snapping them to the rest pose.
- Miffan Lab's Transitions mode repeats focus, typing, rapid taps, submit, thinking, error, retry, and completion.
- Expression and avatar-size screenshots are checked in light, dark, and reduced-motion previews.

## Regression and delivery

- In a Debug build, Settings > About > long-press Version opens Debug Mode, where the update-reminder preview can deterministically drive the drawer badge, Settings banner, and Miffan update-available state without a real release.
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
