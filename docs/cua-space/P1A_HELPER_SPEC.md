# P1a spec: `miffan space` helper commands

Context: read `docs/CUA_SPACE_PLAN.md` first (product decisions, P0 facts). A partner gets its own
cua Space (a Linux container on the user's computer). The app reaches the host over SSH and runs
`~/.miffan/bin/miffan space …`; the helper wraps the `cua` CLI. This spec covers only the POSIX
helper (`remote-helper/src/miffan.sh`), its docs and its tests. Windows hosts are out of scope.

## Files you own

- `remote-helper/src/miffan.sh` (new `space` command, header comment, usage line, version bump)
- `remote-helper/PROTOCOL.md` (new section for `space`)
- `remote-helper/tests/test_space_helper.py` (new, hermetic, same style as `test_task_helper.py`)
- `remote-helper/tests/shellcheck-baseline.json` only if unavoidable (prefer clean code)
- `app/src/test/java/**/RemoteHelperVersionTest.kt` / `RemoteHelperContractTest.kt` only if the version
  bump or a contract test requires it

Do not touch any other Kotlin, UI or Windows files. Do not commit to `feature/cua-space`; commit on
your own branch in your worktree, and say in the final message which commits are ready.

## Facts you can rely on (verified 2026-10-28)

- `cua` CLI lives in `~/.local/bin/cua` (not on the non-interactive PATH). Daemon: `cua daemon status|start`.
  A daemon started by the GUI app (Cua Spaces.app) does not inherit the user's proxy.
- `cua spaces create linux --on local --kind container --runtime runc --name N --json` creates a Space
  (`local:N`). Output: `{"spaces":[{...,"id":"local:N",...}]}`, or `{"spaces":[{"error":"..."}]}` on failure
  (exit code may still be 0 — check the JSON).
- **Always `--runtime runc`.** gVisor (the default on macOS) loses every file on container restart.
- `cua --json sb info N` → `{"state":"running"|…,"endpoints":{"env":"http://127.0.0.1:PORT"},…}`. The
  loopback PORT changes on every start; never cache it.
- Token: `~/.cua/spaces-credentials.json` → `{"local:N":{"token":"…"}}` (32 chars).
- `cua sb ls` shows `stopped`/`missing` after the runtime VM or container stopped;
  `cua spaces start local:N` brings it back with its files ("Resumed local:N.").
- `cua spaces delete local:N --force` deletes without a prompt.
- `cua sb exec N 'sh line'` runs in the Space as user `cua` (has sudo). `cua sb cp LOCAL N:/path` and
  `cua sb cp N:/path LOCAL` copy files (~40 ms for a small file).
- `cua spaces gpus`, `cua runtime doctor`, `cua images info linux` exist (see `--help`). Linux hosts need a
  working Docker the user can use without sudo.
- Install the CLI only, with no account and no app:
  `curl -fsSL https://cua.ai/install.sh | sh -s -- --cli-only --yes --no-modify-path --no-onboarding`
  with `CUA_TELEMETRY=0`. Reuse the existing `cua_proxy` function for downloads (it reads the macOS system
  proxy or the systemd user environment).

## Command contract

All output is one line of JSON on stdout (last line wins, like other commands); diagnostics go to
stderr. Space names are app-generated `miffan-[0-9a-f]{8}` and are passed as arguments; reject anything
else with `{"error":"bad_name"}` exit 2. Any user text (folder names) comes on stdin as JSON.
Errors are `{"error":"<code>","detail":<string|null>}` with exit 1. Error codes:
`cua_missing`, `daemon_failed`, `needs_docker` (Linux: no docker / no permission), `download_failed`,
`disk_low`, `create_failed`, `not_found`, `start_failed`, `exec_failed`, `bad_name`, `bad_input`.

1. `miffan space probe` → `{"cua":{"path":…|null,"version":…|null},"daemon":{"running":bool},
   "docker":{"ok":bool,"detail":…|null}` (Linux only; macOS uses cua's own runtime: report `null`),
   `"disk_free_gb":int,"spaces":[{"id":"local:N","state":"…"}]}` — only Spaces named `miffan-*`.
2. `miffan space install` → installs the CLI (with proxy), then starts the daemon with the proxy in its
   environment if none runs. `{"cua":{"path","version"},"daemon":{"running":true}}`.
3. `miffan space create N` → ensure the daemon runs (start it with proxy env if not), create with
   `--runtime runc`, then wait until `sb info` says running. `{"id":"local:N","state":"running"}`.
   Map failures to the codes above (look at the JSON `error` text: download/pull → `download_failed`,
   disk/space → `disk_low`, docker → `needs_docker`).
4. `miffan space info N` → if not running, `cua spaces start local:N` first. Then
   `{"id","state","port":int,"token":"…"}`. The token goes to stdout only; never to stderr or files.
5. `miffan space start|stop|delete N` → `{"id","state"}` (`delete` → `{"id","deleted":true}`).
6. `miffan space reset N` → delete then create with the same name. Same output as create.
7. `miffan space netcheck N` → inside the Space: DNS lookup and an HTTPS request to two hosts (one
   global, one reachable from mainland China, e.g. `https://www.baidu.com`), each with a short timeout.
   `{"dns":bool,"https":bool,"detail":…|null,"hint":…|null}`. On Linux, when the check fails and
   `systemctl is-active ufw` is active, `hint` is `"ufw"` (the app shows the fix; never change the
   firewall).
8. `miffan space handoff in N` / `miffan space handoff out N`, stdin `{"folder":"<absolute host path>",
   "path":"<relative path, out only>"}`. The Space side folder is `/home/cua/交接`.
   - `out`: copy `/home/cua/交接/<path>` (file or directory) to `<folder>/`; on a name clash add
     ` (2)`, ` (3)`… before the extension; never overwrite. Reject `..` and absolute paths.
     `{"copied":["<host relative name>",…]}`.
   - `in`: copy every regular file in `<folder>` that is newer than the previous `in` (state file in
     `~/.miffan/space/<N>.handoff`) into the Space folder, same clash rule. `{"copied":[…]}`.
   Create both folders when missing (`<folder>` with mode 700).

## Investigate and report (do not guess)

- Which image digest `create` uses and whether `cua image pull linux` (or another command) refreshes it.
  P0 saw cua-driver 0.38 on the Mac's image but 0.33.1 in an older image cached on CachyOS. Add
  `"driver_version"` to `info` if you find a cheap way to read it (for example `cua sb exec N
  'cua-driver --version'` or the image manifest); otherwise report what you found.
- What `cua daemon start` does when started from a non-interactive SSH session on macOS (no GUI session
  access) — you can only test locally; report.

## Tests

Hermetic Python tests with a stub `cua`/`docker`/`curl`/`systemctl` on PATH and a temp HOME, like
`test_task_helper.py`: every command's success JSON, each error mapping, name validation, token never on
stderr, handoff clash naming and `..` rejection, `info` starting a stopped Space, `--runtime runc` always
passed. Run them and the existing helper tests (`python3 remote-helper/tests/*.py` as CI does; check
`.github/workflows` for the exact commands) plus `python3 remote-helper/tests/check_shellcheck.py` if
shellcheck is available. Run the JVM helper tests: `./gradlew :app:testDebugUnitTest --tests '*RemoteHelper*'`.

## Live check (optional, ask before using)

The Mac this runs on has cua 0.4.4 and a daemon. You may create and delete one Space named
`miffan-0000test` locally to check `create/info/netcheck/handoff/delete` for real; delete it at the end.
Do not touch other Spaces (`direct:…` Omarchy/Pond). Do not log in to cua, register on the relay or share.

## Acceptance

All new tests and existing helper/Kotlin helper tests pass; shellcheck clean; PROTOCOL.md documents the
contract; helper version bumped by one; final message lists commits, test commands run with results,
and the two investigation answers.
