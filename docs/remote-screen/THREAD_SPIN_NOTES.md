# Welcome-thread main-thread investigation

## Status

Investigated on 2026-10-08, base `58fc0a13` (`feature/remote-screen-ui`). **The ANR/root-cause fix is not complete.** High welcome-page process CPU was reproduced, but this run did not reproduce a new ANR or an unbounded main-thread coroutine/effect loop. There is no evidence-backed production fix in this worktree. Only this report remains changed; all temporary instrumentation was removed.

Do not interpret the measurements below as a before/after fix or mark the spec's performance acceptance criteria satisfied. Disabling animations, changing the welcome layout, removing glass effects, or changing auto-scroll speculatively would not establish the reported ANR's cause.

## Environment and method

- Device: `emulator-5560`, `me.ayuilos.miffan.app.debug`, easy mode, Default Assistant, welcome divider “Last chat · 4 hours ago”. Existing data was preserved.
- Device was already root. Global animator duration scale was `1`, transition scale `1.0`; animations were not disabled for the experiment.
- Process/main-thread CPU sampled from `/proc/<pid>/stat` and `/proc/<pid>/task/<pid>/stat`: delta of user + system ticks, HZ=100, divided by elapsed host monotonic time. Nominal samples were 10 seconds; actual elapsed time was retained (some ADB reads were slow). Percentages use one CPU core as 100%, not all emulator cores.
- Avoided UI dumps and method profiling during CPU sample intervals. Samples are short and noisy; startup, JIT, host/emulator scheduling and animation activity can affect them. They are not a latency benchmark.
- Temporary `ThreadSpin` logs covered the listed page effects, `scrollToLatest`, every page `loadMore` call site, top/bottom `onSizeChanged`, and (second diagnostic build) page composition.
- An A/B diagnostic build read a temporary `debug.miffan.spin.nohaze` property on page entry to omit only the `LazyColumn.hazeSource` modifier. Both A/B branches used the same installed APK and process, fresh page entries. This was a diagnostic experiment, not a proposed UI change.
- Captured a current-process SIGQUIT and two eight-second `am profile start --sampling` traces (1000µs idle, 500µs focused). Profiling perturbs execution; those intervals were separate from the CPU figures.

## Measurements (process / main-thread CPU)

| State | CPU | Notes |
| --- | --- | --- |
| Original installed package, profile idle | approximately 6% / 6% | Initial 10-second tick delta; profile was already open |
| Original package, welcome idle | 62.4% / 21.0% | PID 12747; actual interval 15.01s |
| Original package, focused draft | 67.1% / 19.0% | PID 12747; 11.00s |
| Diagnostic package, settled Chats home | 7.1% / 7.0% | PID 13575; 10.09s |
| Diagnostic package, welcome idle | 74.1% / 14.3% | 10.36s |
| Diagnostic package, focused long draft | 29.9% / 5.3% | 10.06s; illustrates substantial variability |
| Diagnostic package, repeated typing | 63.8% / 13.0% | 12.05s |
| Diagnostic package, profile after navigation | 13.8% / 12.4% | 10.12s; includes settling after entry |
| A/B: source omitted, first / next interval | 51.2% / 17.1%; 69.1% / 22.7% | PID 14266; 10.26s each |
| A/B: normal source, first / next interval | 58.0% / 9.5%; 70.6% / 12.6% | Same PID; 11.10s / 10.66s |

Omitting the blur source did not remove the high CPU. These short samples do not establish the blur source as the cause, nor prove it has no cost.

## Findings and excluded hypotheses

### Page effects did not loop in the observed runs

For the first instrumented welcome entry (17:46:28–29, PID 13575):

- Top and bottom chrome settled at `1080 x 301` and `1080 x 232` pixels, respectively; one size callback each on entry.
- Timeline/loading effects ran for initial load, then stopped. `scrollToLatest` ran once after loading.
- No `loadMore` calls occurred during the whole captured typing/navigation sequence.
- Focusing the composer produced successive bottom sizes during the normal IME animation (`374`, `549`, `885`, …, `1052` pixels), with corresponding bottom-chrome effect/scroll calls. They stopped when the IME settled. Wrapping the draft caused another finite size/scroll update.
- In the second diagnostic build, the no-source welcome entry composed 5 times while entering/loading, with no continuing idle composition stream. The normal-source entry composed 4 times while entering/loading. Both scrolled once after loading.

These observations do not support an idle loop involving `followLatest`, `atBottom`, `fillParentMaxHeight`, chrome size callbacks, or hidden-history pagination under the tested conditions. No speculative changes were made to those mechanisms.

### Current samples did not match the historical spinning stack

- Current SIGQUIT `/data/anr/trace_08`, PID **12747**, at **17:41:40**, placed the main thread in `HardwareRenderer.nSyncAndDrawFrame`, waiting in the native rendering path. This is a single sample, not proof of a rendering deadlock.
- `/data/anr/trace_07` did contain `AndroidUiDispatcher.nextTask` / `performTrampolineDispatch`, but was PID **12225** at **17:32:58**, before this run. It was initially read before the new dump appeared and was explicitly excluded from current-run evidence.
- In the eight-second current idle method trace, approximately **6.69s** of sampled main-thread wall time was attributed to `MessageQueue.nativePollOnce`, and **0.72s** to `HardwareRenderer.nSyncAndDrawFrame`. The focused trace had approximately **5.26s** and **1.94s**, respectively. Animation/frame callbacks were present, but no sustained trampoline-dispatch hotspot comparable to the reported stuck state was observed.
- The device's historical events confirm an app KeyEvent ANR at **17:30:48**, PID **11534**, waiting **28906ms**. They also include an earlier system-server input timeout and a Google Play services startup timeout. These are historical observations, not enough to assign the app ANR to either an application loop or the emulator.
- No new app `am_anr` event appeared during this run.

### Profile and welcome do not run identical animation behavior

In this base, `ImPartnerProfilePage` calls `AssistantAvatar`, which passes `MiffanPresentation.Avatar`. `miffanRunsAmbientMotion` returns false for an idle Avatar. `ThreadWelcomeMascot` uses `MiffanPresentation.Scene`, which runs ambient motion. Thus the spec's comparison with an “animated 128dp profile mascot” is not a like-for-like idle animation comparison in this version.

The welcome's recurring frame work is consistent with its scene animation, but **this does not establish the historical ANR's root cause or rule out a rarer animation/scheduling bug**. The process CPU figure alone does not demonstrate a main-thread infinite loop.

## Input and behavior checks

- Entered only unsent draft text: `spincheck`, a longer lowercase/digit string, uppercase alphabet/digits, and another typing round with keyboard close/reopen.
- Fresh UI dumps confirmed the drafts appeared, the welcome hero still filled the available area, and it resized above the keyboard. Navigation back to Chats and into the partner/profile continued to work.
- One repeated-input dump showed reordered adjacent characters in the injected text. Input was therefore not assessed as perfect; no quantitative key-to-display latency was recorded.
- No messages were sent, no remote-computer commands were executed, and no approval was granted.
- Live generation/shared-partner handoff was not exercised. Since no production code change remains, this report does not claim new functional regression coverage of those paths.

## Checks and cleanup

- `./gradlew :app:testDebugUnitTest :app:assembleDebug --offline`: **BUILD SUCCESSFUL** (20s); same tasks required by the spec, invoked together.
- Unit tests: **606 tests, 105 suites, 0 failures, 0 errors, 0 skipped**.
- No new unit test: no confirmed logic defect or production change exists to test.
- Restored `AgentThreadPage.kt` byte-for-byte from its pre-instrumentation copy. No temporary logs, A/B switches, layout changes, or animation changes remain in source.
- Restored the normal APK after the experiment. Temporary diagnostic property cleared. `adb unroot` returned `restarting adbd as non root`; a subsequent `adb shell id` returned **`uid=2000(shell)`**, confirming root was revoked.
- No Git write operation was performed.
- QEMU remained active during the checked stalls (47.9% and 64.4% host CPU); the spec's whole-VM-freeze criterion was not observed, so no cold boot was performed.

Local raw diagnostics are under `/tmp/thread-spin/` (not committed): `before-trace-current.txt`, `before.trace`, `before-focus.trace`, `effects*.log`, `ab-*-effects.log`, `final-checks.log`, and measurement/trace parsing helpers. They are temporary local artifacts, not durable repository fixtures.

## Remaining work

A reproducible occurrence of the **actual stuck state** is still needed to complete the requested fix. On that occurrence, capture matching PID/timestamps, consecutive main-thread stacks and a short method trace while it is stuck, together with the effect/size counters. That can distinguish queue producers, rendering stalls, and emulator/system-wide starvation. Current evidence is insufficient to choose a minimal production fix safely, and the CPU-close-to-profile acceptance target has not been achieved.
