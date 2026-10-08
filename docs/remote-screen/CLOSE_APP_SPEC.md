# Closing apps, and "could not confirm" is not "failed"

Owner: Codex. Reviewer: Claude. Base: `feature/remote-screen`.

## What happened (user backup, 2026-10-08 13:49, partner 蓝色大肥鱼, CachyOS niri Wayland, cua-driver 0.34)

User: "close the two open terminals, then open Steam". The partner listed windows, then called
`computer_launch_app {"name":"/usr/bin/kill","additional_arguments":["-TERM","188639","553507"]}`.
The kill worked, but cua-driver waits for a new window after a launch and returned an error:
`structured: {"code":"launch_handoff_timeout","effect":"refused","handoff":"dbus_activation",...}` with text
"...no new window appeared within 8s; the launch could not be confirmed. Call list_windows to check...".
`convert()` in `data/ai/tools/ComputerTools.kt` prefixes `{"status":"error"}` for any `isError`, so chat showed
"Computer action failed".

## Changes

1. **Give the partner a real way to close apps.** Add `kill_app` to `ACTION_TOOLS` in `ComputerTools.kt` (approval-gated
   like other actions; the existing capability filter already drops it when a driver lacks it). In
   `computerActionTitle()` (`utils/ComputerActionTitle.kt`) map `kill_app` to a new string `computer_use_close_app`
   with the app/pid as detail when present (look at the driver's schema for argument names).
2. **Steer the model.** In the `## Operating ...` system prompt of `createReadGuideTool` add one line:
   `launch_app only opens GUI apps; never use it to run commands such as kill. To close an app or window use kill_app
   (or the app's own close shortcut via hotkey). For other commands use the shell tool if you have one.`
   Keep the `computer_start` entry prompt unchanged.
3. **"Could not confirm" status.** In `convert()`, when the result is an error whose structured content has
   `code == "launch_handoff_timeout"` (or any code ending in `_timeout` with `effect` not `"failed"`), prefix
   `{"status":"unconfirmed"}` instead of `{"status":"error"}`. Extend `ComputerToolStatus` and `computerToolStatus()`
   (`ui/components/message/ComputerToolUI.kt`) with `UNCONFIRMED` (ERROR still wins if both appear). Wherever the UI
   currently shows `R.string.computer_use_error` for `ComputerToolStatus.ERROR` (grep it; easy-mode summary row and
   professional tool step), show `R.string.computer_use_unconfirmed` for `UNCONFIRMED` in the neutral
   onSurfaceVariant color instead of the error color. Do not change layouts or add components; only this mapping.
   An unconfirmed call is not counted as a failed action in `threadComputerEvidence`.

## Strings (values with tools:ignore="MissingTranslation", values-zh, plus zh-rTW/ja/ko-rKR/ru)
| name | en | zh |
|---|---|---|
| computer_use_close_app | Close app | 关闭应用 |
| computer_use_unconfirmed | Couldn't confirm it worked; check the screen | 没能确认是否成功，可以看一眼屏幕 |

## Verification
- Unit tests: `convert`-level status choice (timeout → unconfirmed, other errors → error), `computerToolStatus` with
  unconfirmed, `computerActionTitle("computer_kill_app", ...)`.
- `./gradlew :app:testDebugUnitTest --offline` and `./gradlew :app:assembleDebug --offline` pass.
- No device test needed. No git writes; Claude reviews and commits. Write a short `docs/remote-screen/CLOSE_APP_NOTES.md`.
