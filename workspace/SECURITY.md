# Workspace / PRoot security boundary

PRoot is used as a local Linux compatibility environment. It translates paths and traces guest
processes while all code still runs with RikkaHub's Android application identity. It is not a
Docker-, namespace-, or VM-grade security boundary. Guest commands can consume the CPU, memory,
network access, and app-private storage that the Android process makes available.

## Hardening phases

1. **Tool and installation boundary (implemented)**
   - Parse every model-supplied Rootfs file path as one strict absolute POSIX `GuestPath` for both
     approval and execution. Reject `..`, `.`, NUL, backslashes, repeated separators, trailing
     separators, symbolic-link writes, and writes through read-only application mappings.
   - Keep `/skills`, `/upload`, and `/tool_outputs` available to `workspace_read_file`, but do not
     bind these cross-workspace host directories into either PRoot shell surface.
   - Select a pinned Ubuntu Base archive for `arm64-v8a` or `x86_64`; require HTTPS, same-host HTTPS
     redirects, a compiled-in SHA-256, download/extraction quotas, health checks, and rollback.
   - Share PRoot arguments and environment construction between AI commands and the terminal;
     serialize installs, AI commands, direct writes, cleanup, and deletion per workspace.
2. **Resource governance (next)**
   - Add per-workspace disk accounting and free-space reservations for `/workspace`, Rootfs, temp,
     terminal output, and tool outputs.
   - Add explicit concurrent-session limits and durable process-group/cgroup-style cleanup where
     supported. `--kill-on-exit` plus best-effort descendant cleanup is not a kernel boundary.
3. **Isolation alternatives (future)**
   - Evaluate a dedicated Android process/UID and brokered network/filesystem access. Treat a real
     VM or kernel-enforced container as a separate execution backend rather than describing PRoot
     as equivalent.

## Android device / emulator verification checklist

Run this checklist on both an arm64 device and an x86_64 emulator after the JVM suite:

1. Install Rootfs from the Workspace screen. Confirm the matching architecture is selected, a
   shell starts, and `uname -m`, `/bin/bash -lc 'echo ok'`, and `/usr/bin/env` succeed.
2. Upload a text and binary file. Confirm `workspace_read_file` can read `/upload/<name>` while
   `workspace_shell` cannot see or modify that file at `/upload/<name>`.
3. Confirm `workspace_read_file` can read a known `/skills/<name>/SKILL.md` and a surfaced
   `/tool_outputs/<name>`, while neither global directory appears as a PRoot bind mount.
4. Request `workspace_write_file` for each path below:
   - `/workspace/ok.txt` and `/tmp/ok.txt`: succeeds without the mandatory outside-root gate.
   - `/skills/no.txt`, `/upload/no.txt`, `/tool_outputs/no.txt`, `/etc/no.txt`: requires approval;
     the first three remain read-only even after approval.
   - `/workspace/../skills/no.txt`, `/workspace//no.txt`, a backslash path, and a NUL-bearing path:
     is rejected and creates no file.
5. Run `ln -s /etc /workspace/escape` in the terminal, then request a write to
   `/workspace/escape/no.txt`. Confirm the write is rejected and `/etc/no.txt` is absent.
6. Compare `env`, `pwd`, and visible bind paths in an interactive terminal and `workspace_shell`.
   The common clean environment and `/workspace` mapping must match.
7. Start `sh -c 'sleep 600 & wait'` through `workspace_shell`, then cancel and repeat with a timeout.
   Use `adb shell ps -A | grep -E 'proot|sleep'` to confirm no child remains. Repeat rapid command
   submissions and verify they serialize per workspace.
8. Interrupt a reinstall (including force-stopping the app) during download, extraction, and swap.
   A previously healthy Rootfs must remain usable; `.linux-backup` must be restored on retry.
9. Attempt oversized and highly expanded test archives in a debug build. Verify the 128 MiB
   download, 1 GiB expanded, 256 MiB entry, and 100,000-entry limits fail without replacing the
   previous installation.
