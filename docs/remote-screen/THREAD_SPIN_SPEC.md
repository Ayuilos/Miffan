# Easy chat spins the main thread in the welcome state (ANR)

Owner: Codex (debugging, no design changes). Reviewer: Claude. Base: `feature/remote-screen`.

## Symptom
On emulator-5560 (`me.ayuilos.miffan.app.debug`, easy mode), open the partner thread when the last chat is more than
an hour old, so `ThreadWelcome` shows the big partner ("Last chat · N hours ago", "View earlier chats").
- App CPU (`adb shell top -b -n 1 | grep miffan`): home tab ~4%, partner profile (animated 128dp mascot) ~32%,
  welcome thread idle ~82%, after focusing the composer ~127%.
- Typing then hangs the main thread: "Input dispatching timed out ... spent 29088ms processing KeyEvent", ANR, killed.
- Main thread stack (SIGQUIT trace, `adb root`, /data/anr) is always inside
  `AndroidUiDispatcher.performTrampolineDispatch` → `nextTask`, i.e. coroutines on the UI dispatcher keep re-queuing
  each other (a recomposition / effect / scroll loop), not a single slow call.
- Happens on fdebb52c too (before the latest UI commit), so it predates the live-status work. Not seen in a thread
  that is not in the welcome state.

## Suspects (verify, don't assume)
`ui/im/thread/AgentThreadPage.kt`: `LaunchedEffect(timeline, visibleErrors, loaded)` and `LaunchedEffect(bottomChrome)`
calling `scrollToLatest()` (which scrolls, waits a frame, then `scrollBy`), `followLatest` / `atBottom`
(`derivedStateOf { !listState.canScrollForward }`), the welcome item using `Modifier.fillParentMaxHeight()` when `fill`,
`onSizeChanged` of top/bottom chrome, the `snapshotFlow` that calls `vm.loadMore()` when the first item is visible
(the welcome hero is the first row while history is hidden), and `ThreadWelcome.kt`.

## Task
1. Reproduce and measure as above. Find the loop with evidence (e.g. temporary logs or counters on the suspect
   effects / `loadMore`, Layout Inspector recomposition counts, or `adb shell am profile`), then remove the temporary
   instrumentation.
2. Fix the root cause with the smallest change that keeps behaviour: the welcome still fills the screen when the
   history is hidden, following the newest message still works, and the live status row / shared partner transition
   added in the latest commit keeps working.
3. Measure again: welcome thread idle CPU should be close to the profile page's, and typing must stay responsive.
4. `./gradlew :app:testDebugUnitTest --offline` and `:app:assembleDebug --offline` pass. Add a unit test if the loop is
   in testable logic.
5. If the emulator VM freezes (qemu 0% CPU, adb shell hangs), cold boot:
   `emulator -avd RikkaHub_API_35_16K -port 5560 -no-snapshot-load`. Undo `adb root` when done (`adb unroot`).
6. No git writes; Claude reviews and commits. Write `docs/remote-screen/THREAD_SPIN_NOTES.md` with the cause, the fix
   and before/after numbers.
