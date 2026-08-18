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
     both surfaces consume the same manager-owned bind definition. Serialize installs, AI commands,
     direct writes, cleanup, and deletion per workspace.
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
     runs. DNS, hosts, hostname, locale, group, and temp-directory patching reject unsafe path
     shapes and cap maintenance-file reads/writes. The known `/etc/resolv.conf` leaf symlink is
     replaced without following its target.
   - Workspace deletion, temp cleanup, install staging, rollback, and replacement use recursive
     deletion that never traverses symbolic links. RootFS health checks reject a symbolic RootFS or
     `/etc` directory and reject required entrypoints whose resolved files leave the RootFS tree.
     Workspace creation and both AI/terminal launch paths also require real, non-symlink managed
     `files`, `linux`, and `tmp` directories before any bind mount or host working directory is used.
6. **Session-escape descendant reaping (implemented with kernel-dependent defense in depth)**
   - Run both AI commands and interactive PTYs below a dedicated native child-subreaper monitor.
     When procfs child enumeration is available, the monitor adopts, kills, and reaps every
     remaining descendant. Independently, PRoot's `--kill-on-exit` traces guest processes that use
     `setsid()` or `setpgid()` to escape the original host process group.
   - Use the Linux procfs direct-child interface when the device exposes it. Android kernels that
     omit that optional file still launch correctly, reap the killed command group with bounded
     waits, and rely on PRoot's `--kill-on-exit` tracing for guest descendants. A raw host process
     that escapes its group cannot be identified safely under the shared app UID on such kernels;
     moving execution to a dedicated UID remains the architectural closure for that case. PTY
     waiters resolve the published PRoot PID to its private monitor instead of assuming PRoot
     remains a direct child of the Android process.
7. **Descriptor-relative RootFS patching (implemented)**
   - Android-side RootFS patching anchors lookup at the UID-owned package data directory below an
     Android-managed private-data mount, opens every app-controlled component with directory FDs
     plus `openat`/`mkdirat` and `O_NOFOLLOW`, and validates the opened object with `fstat` before
     reading, truncating, writing, or changing permissions. This avoids SELinux-denied reads of
     host `/` and Android's seccomp-denied `openat2` while carrying no guest-controlled security
     decision forward as a reusable pathname.
   - Reject maintenance files with multiple hard links or an unexpected owner, as well as symbolic
     links and non-regular files. Create files with `O_CREAT|O_EXCL|O_NOFOLLOW`; only the expected
     `/etc/resolv.conf` leaf link may be removed and recreated. Apply exact `01777` modes to `/tmp`
     and `/var/tmp` and `0700` to `/root` through already-validated directory FDs.
8. **Descriptor-relative lifecycle maintenance (implemented)**
   - Android workspace deletion, temp cleanup, install staging cleanup, rollback cleanup, and old
     RootFS removal now recurse from validated directory FDs. Each child is inspected with
     `fstatat(AT_SYMLINK_NOFOLLOW)`, directories are opened with `openat(O_NOFOLLOW)`, and leaf names
     are removed with `unlinkat`; links are unlinked rather than traversed. Directory identity is
     checked again before removal, with bounded depth and operation counts that fail closed.
   - RootFS staging, backup, recovery, and rollback use `renameat2(RENAME_NOREPLACE)` between
     descriptor-anchored parents. The moved directory must be app-owned and its device/inode
     identity is verified at the destination. A concurrently-created destination is never
     overwritten.
9. **Descriptor-relative model file I/O (implemented)**
   - `workspace_write_file` and `workspace_edit_file` now create or overwrite files through the
     native RootFS bridge. Parent components below the verified package-data capability root are
     opened with `O_NOFOLLOW`; the leaf is opened with `O_NOFOLLOW`, inspected after open, and
     hard-linked or non-regular targets are rejected before truncation. `overwrite=false` is
     enforced by the same native decision that opens the leaf rather than by an earlier Java
     existence check.
   - Write-growth quota preflight queries the existing leaf through the same descriptor-relative
     no-follow bridge. A link or non-regular replacement cannot inflate the apparent old size and
     reduce the capacity reserved for the subsequent safe write.
   - `workspace_read_file` size checks and exports use descriptor-relative, bounded regular-file
     reads for `/workspace`, RootFS paths, `/skills`, `/upload`, and scoped `/tool_outputs`.
     Symbolic-link components and leaves are rejected, while ordinary uploaded files and legitimate
     read-only hard links remain readable. The export repeats the 8 MiB cap while reading, so growth
     after the preliminary size query cannot produce an unbounded allocation.
10. **Workspace mutation and discovery boundary (implemented)**
   - Android streaming imports obtain an `O_CREAT|O_EXCL|O_NOFOLLOW` file descriptor from the native
     bridge and write only through that descriptor. Conflict-name selection is bounded; quota or I/O
     failure removes the created leaf through the descriptor-relative deletion path.
   - Workspace delete enforces the recursive-directory decision inside the native operation. Move
     rejects symbolic/special sources and ancestor/descendant targets, removes an overwrite target
     without following it, then uses `renameat2(RENAME_NOREPLACE)` and verifies source identity at
     the destination.
   - Regular UI text reads/writes use the same descriptor file boundary. On Android, directory
     listing, glob, and grep candidates are enumerated from directory FDs with
     `fstatat(AT_SYMLINK_NOFOLLOW)` and verified child-directory identities. Links and special files
     are skipped, traversal depth and scanned entries are bounded, and grep reopens every selected
     file through the no-follow read bridge.
11. **Descriptor-relative streaming export (implemented)**
   - Android UI exports and their size queries now open regular files below the selected workspace
     area with the same `openat(O_NOFOLLOW)` directory-FD chain as model reads. The exported stream
     stays attached to that opened inode, so a concurrent same-UID pathname replacement cannot
     redirect it after validation.
   - UI exports remain streaming and may exceed the model tool's 8 MiB read limit. Bounded model
     reads inspect the opened descriptor size before producing output and continue counting bytes
     while copying, so concurrent file growth still fails at the configured cap.
12. **Descriptor-relative RootFS extraction (implemented)**
   - Android archive extraction writes through a dedicated directory-FD sink. Parent directories
     are opened or created with `openat`/`mkdirat` plus `O_NOFOLLOW`; regular files remove only a
     validated non-directory leaf and are recreated with `O_EXCL|O_NOFOLLOW` before bytes are
     streamed to the returned descriptor. Archive modes are applied with `fchmod` and special mode
     bits are stripped.
   - Archive symlinks use `symlinkat` without host-side traversal. A later regular-file entry safely
     unlinks that symlink instead of following it. Hard links use `linkat` where Android policy
     permits it; when app-private hard links are denied, extraction materializes an exact regular
     file copy through verified source/target descriptors. Both paths charge the source's logical
     size to the extraction quota. Modification times are applied below an opened parent FD without
     following links. JVM tests use a no-follow NIO sink with matching failure rules.
13. **Executable Android device gate (implemented)**
   - `workspace/scripts/verify-android-device.sh` builds the instrumentation APK without Gradle UTP,
     verifies the selected serial/ABI/API, installs it directly with ADB, runs the native process,
     PTY, RootFS host-boundary, extraction, and mutation suite, and rejects crashes or test failures.
     It writes a device-fingerprinted report below `workspace/build/reports/android-device`.
   - The gate discovers only complete NDK installs containing `source.properties`, so a broken
     default NDK is reported as an environment problem rather than a code failure. Pass
     `--expected-abi arm64-v8a` on a physical device and `--expected-abi x86_64` on an emulator/CI
     host to complete the release matrix.
14. **Isolation alternatives (future)**
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
20. Run `RootfsHostBoundaryInstrumentedTest` on arm64 and x86_64. Confirm the native patch bridge
    rejects hard-linked maintenance files without changing their peers, replaces but never follows
    the expected `/etc/resolv.conf` symlink, and applies `01777` to `/tmp` and `/var/tmp` and `0700`
    to `/root`. In a debug stress fixture, repeatedly exchange nested directories with symlinks
    during patching; every run must either finish inside the RootFS or fail without changing a
    sentinel outside it.
21. In `RootfsHostBoundaryInstrumentedTest`, confirm Android selected the native host-maintenance
    backend. Delete a tree through an absolute parent symlink and confirm the operation is rejected
    without touching its target. Attempt a RootFS-style rename onto an existing symlink and confirm
    it is rejected; remove that link and confirm the same source is atomically moved to the empty
    destination. Repeat install interruption at each swap point and verify the previous healthy
    RootFS is either active or recoverable from `.linux-backup`.
22. Read a normal upload through `workspace_read_file`, then replace both an `/upload` leaf and a
    `/workspace` parent with links to sentinel data outside their mapped roots. Confirm reads and
    writes reject both links without disclosing or modifying the sentinels. Repeat a write with
    `overwrite=false` against an existing regular file and confirm its content is unchanged. Grow a
    file beyond 8 MiB between the size query and export and confirm the FD read stops at the cap.
23. Import the same file name twice and confirm conflict naming is deterministic, then interrupt an
    over-quota import and confirm no partial file remains. Move a regular file over a symlink with
    overwrite enabled and confirm the link is removed without changing its target. Verify
    non-recursive directory deletion is rejected natively, recursive deletion succeeds, and
    list/glob/grep neither returns nor reads a symlink leaf pointing at an outside sentinel.
24. Export a binary file larger than 8 MiB from the Workspace UI and confirm it is byte-identical;
    the UI export must not inherit the model read cap. Replace its leaf and a parent directory with
    symlinks to outside sentinels and confirm both size and export operations reject the path without
    disclosing sentinel bytes. Concurrently exchange the pathname while exporting and confirm the
    output stays attached to the inode opened before the exchange.
25. List, glob, and grep a tree containing Unicode names plus symlink leaves and symlinked
    directories aimed at outside sentinels. Confirm only regular files and real directories below
    the workspace are returned, grep never reads the sentinels, and listing a symlink path is
    rejected. Repeat while exchanging a nested directory with a symlink; traversal must either stay
    on the verified opened directory inode or skip the changed entry.
26. Build a test tar that creates a symlink to an outside sentinel and then places a regular file at
    the same archive path. Extraction must replace the link locally and leave the sentinel unchanged.
    Repeat with a symlink used as a later entry's parent and confirm extraction fails closed. Create
    native hard links and verify either the destination inode matches the inspected source or,
    where Android policy denies `linkat`, the materialized file is byte-identical with the expected
    mode; in both cases its logical size must count toward the expanded-byte quota.
