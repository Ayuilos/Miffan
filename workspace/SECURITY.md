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
2. **Process-local resource governance (implemented)**
   - Account logical bytes without following symlinks. Defaults cap `/workspace` at 512 MiB,
     Rootfs at 1.5 GiB, installation/runtime temp at 1.25 GiB, and the workspace tree at 3 GiB,
     while reserving 256 MiB of device free space. Rootfs installation reserves its maximum
     download and expansion budget before accepting data.
   - Scope `/tool_outputs` by workspace and cap it at 32 MiB total and 2 MiB per file. Rejected
     output is explicitly reported instead of silently filling global application storage.
   - Admit at most three active PRoot/maintenance sessions globally and one per workspace. AI
     commands, installation, writes, cleanup, deletion, and the interactive terminal share this
     process-local registry.
   - Apply an inherited 256 MiB `RLIMIT_FSIZE`, poll free space during commands, scan aggregate
     usage periodically, run a full postflight scan, and wait after forced process termination.
     The terminal uses the same admission/file limit and a lifecycle resource monitor.
3. **Process lifecycle governance (implemented)**
   - Start AI PRoot commands through the Workspace JNI monitor and require the PRoot command PID
     to lead a dedicated process group. Interactive PTYs use the same process-group rule and arm
     `PR_SET_PDEATHSIG` in the native child.
   - Persist a record for each native PRoot process before exposing it to the caller. Records bind
     the PID to its kernel start time, UID, process group, command identity, owner process, and boot
     id when readable. App startup kills only records whose owner is gone and whose complete target
     identity still matches; PID reuse or corrupt records are discarded without signaling.
   - Termination and terminal disposal signal the verified process group before the main PID. Guest
     processes inherit caps of 300 CPU seconds, 1.5 GiB virtual memory per process, 256
     processes/threads counted across the app UID, and the existing 256 MiB output-file limit. A
     stricter inherited limit is kept.
4. **Crash-safe native launch (implemented)**
   - The JNI monitor arms `PR_SET_PDEATHSIG`, creates a new session, places PRoot in its own process
     group, and installs a parent-death handler that kills that complete group. It verifies PRoot
     crossed `execve()` before its PID or streams are returned to Kotlin. PRoot also dies if its
     monitor disappears, and the monitor removes residual group members after PRoot exits.
     JNI forks are serialized on a process-lifetime launcher thread because Linux binds the death
     signal to the parent thread, and coroutine pool workers may retire while the app stays alive.
   - Arguments, environment, and the working directory cross JNI as length-bounded UTF-8 byte
     arrays with NUL rejection. Durable registration continues to match the actual PRoot executable
     together with its PID start time, UID, and process group.
   - Interactive PTY monitors fork only from the Android main thread, clear inherited ART signal
     handlers and masks, and publish the PRoot PID only after it crosses `execve()`. The native
     waiter waits for the monitor, which preserves the main exit status after descendant cleanup.
     AI and PTY launchers now inherit the same host environment plus trusted PRoot overrides; the
     guest environment remains explicitly clean.
5. **Host-side RootFS maintenance boundary (implemented)**
   - Treat the mutable RootFS tree as guest-controlled input whenever Android-side maintenance
     runs. DNS, hosts, hostname, locale, group, and temp-directory patching reject symbolic-link
     components; all file opens use `NOFOLLOW_LINKS`, and maintenance-file reads/writes are capped.
     The known `/etc/resolv.conf` leaf symlink is replaced without following its target.
   - Workspace deletion, temp cleanup, install staging, rollback, and replacement use recursive
     deletion that never traverses symbolic links. RootFS health checks reject a symbolic RootFS or
     `/etc` directory and reject required entrypoints whose resolved files leave the RootFS tree.
     Workspace creation and both AI/terminal launch paths also require real, non-symlink managed
     `files`, `linux`, and `tmp` directories before any bind mount or host working directory is used.
6. **Session-escape descendant reaping (implemented)**
   - Run both AI commands and interactive PTYs below a dedicated native child-subreaper monitor.
     When the main PRoot process exits or its original process group is terminated, the monitor
     adopts, kills, and reaps every remaining descendant, including a guest process that used
     `setsid()` or `setpgid()` to escape the original group.
   - Require the Linux procfs direct-child interface before launch, and keep the monitor alive on
     Android parent death long enough to complete descendant cleanup. PTY waiters resolve the
     published PRoot PID to its private monitor instead of assuming PRoot remains a direct child of
     the Android process.
7. **Isolation alternatives (future)**
   - Evaluate a dedicated Android process/UID, cgroup/job-control integration where Android permits
     it, and brokered network/filesystem access. The polling safeguards above can detect and stop
     overuse but are not atomic aggregate disk, CPU, or memory quotas. `RLIMIT_NPROC` is scoped to
     the shared Android app UID, not to an individual workspace.
   - Treat a real VM or kernel-enforced container as a separate execution backend rather than
     describing PRoot as equivalent.

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
10. Open a terminal, then attempt `workspace_shell`, Rootfs reinstall, and a second terminal for the
    same workspace. Confirm no second session starts. Open terminals for distinct workspaces and
    confirm the fourth global session is refused until one lease is released.
11. In a debug build with reduced resource limits, exceed each of the files, Rootfs, temp, total,
    tool-output, and free-space thresholds. Verify direct writes/imports fail without partial files;
    long-running shell/terminal processes are stopped; a fast command that exits after writing too
    much is reported as `resourceLimitExceeded` by the postflight scan.
12. Generate truncated tool results in two workspaces. Confirm each can read only its own
    `/tool_outputs/<id>.txt`, per-file/aggregate rejection is visible in the tool result, deleting a
    workspace removes its scoped outputs, and `/tool_outputs` remains absent from both shells.
13. Start `sleep 600 & wait` in both AI Shell and the terminal. Cancel/close each surface and use
    `adb shell ps -A -o PID,PGID,NAME | grep -E 'proot|sleep'` to confirm the full process group is
    gone. Repeat while rapidly navigating away during terminal startup.
14. While a shell and terminal are running, force-stop or kill the RikkaHub Java process without a
    normal lifecycle callback. Relaunch the app and confirm `Workspace orphan recovery completed`
    is logged, `run-as <package> ls files/workspaces/.runtime/processes` has no surviving record,
    and no matching PRoot group remains. Create a synthetic stale record with a reused PID but a
    different start time in a debug fixture and confirm that unrelated process is not signaled.
15. In a debug build with reduced limits, run a CPU loop, a virtual-memory allocator, a fork loop,
    and a large single-file write from both shell surfaces. Confirm Bash reports the configured
    soft limits, violations stop the process, and a stricter pre-existing hard/soft limit is never
    raised by the workspace launcher.
16. Run `WorkspaceNativeProcessInstrumentedTest` on both target architectures. Then repeatedly kill
    the app process immediately after submitting an AI shell command and confirm no
    `rk-ws-launcher`, PRoot, shell, or grandchild process remains, including attempts before a JSON
    ownership record appears under `.runtime/processes`.
17. Run `WorkspacePtyInstrumentedTest` on both target architectures. Confirm a worker-thread launch
    is rejected before fork, an invalid executable publishes no PID, and normal shell exit preserves
    its status while removing both a same-group background child and a child that called `setsid()`.
    Force-stop the app during PTY startup and after an interactive background job begins; neither
    PRoot nor a process-group/session escapee may remain.
18. Run `RootfsHostBoundaryInstrumentedTest` on both target architectures. Replace RootFS `/etc`,
    `/tmp`, `/var/tmp`, and maintenance files with links to sentinel files under a separate app
    directory. Patching, cleanup, reinstall/rollback, and workspace deletion must either reject the
    tree or unlink only the guest link; sentinel contents and permissions must remain unchanged.
    Repeat with managed `files`, `linux`, and host `tmp` directories and confirm both shell launch
    surfaces reject them before PRoot starts.
19. Run the explicit `setsid()` cases in `WorkspaceNativeProcessInstrumentedTest` and
    `WorkspacePtyInstrumentedTest`. Confirm the child has a PGID different from PRoot before
    cancellation/normal exit, then disappears before the monitor reports completion. During the
    same cases, confirm no `rk-ws-launcher` or `rk-ws-pty` monitor remains afterward.
