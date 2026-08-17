package me.rerere.workspace

import java.io.File

/** Shared PRoot process specification for AI shell commands and interactive terminal sessions. */
object ProotExecutionSpec {
    private const val GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun guestCwd(relativeWorkspaceCwd: String): String {
        val relative = relativeWorkspaceCwd.trim()
        require(!relative.startsWith('/') && !relative.endsWith('/')) {
            "Workspace cwd must be a canonical relative path"
        }
        require(
            relative.isBlank() || relative.split('/').none { it.isBlank() || it == "." || it == ".." }
        ) { "Workspace cwd must be a canonical relative path" }
        return if (relative.isBlank()) {
            WorkspaceManager.ROOTFS_WORKSPACE_DIR
        } else {
            "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/$relative"
        }
    }

    fun baseArguments(
        root: String,
        linuxDir: File,
        filesDir: File,
        cwd: String,
        bindMounts: List<WorkspaceBindMount>,
    ): List<String> = buildList {
        add("--root-id")
        add("--link2symlink")
        add("--kill-on-exit")
        add("-r")
        add(linuxDir.absolutePath)
        add("-w")
        add(cwd)
        add("-b")
        add("${filesDir.absolutePath}:${WorkspaceManager.ROOTFS_WORKSPACE_DIR}")

        bindMounts.asSequence()
            .map { mount -> mount to mount.sourceFor(root) }
            .filter { (mount, source) -> mount.exposeToShell && source.exists() }
            .forEach { (mount, source) ->
                add("-b")
                add("${source.absolutePath}:${mount.guestTarget.value}")
            }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                add("-b")
                add(path)
            }
        }
    }

    fun guestEnvironment(interactive: Boolean): List<String> = buildList {
        add("/usr/bin/env")
        add("-i")
        add("HOME=/root")
        add("PATH=$GUEST_PATH")
        add("TERM=xterm-256color")
        add("LANG=C.UTF-8")
        add("LC_ALL=C.UTF-8")
        add("USER=root")
        add("SHELL=/bin/bash")
        add("/bin/bash")
        if (!interactive) add("-l")
    }

    fun hostEnvironment(loader: File, tempDir: File): Map<String, String> = mapOf(
        "PROOT_LOADER" to loader.absolutePath,
        "PROOT_TMP_DIR" to tempDir.absolutePath,
        "TMPDIR" to tempDir.absolutePath,
    )

    fun nonInteractiveCommand(context: WorkspaceShellContext, proot: File): List<String> {
        val cwd = guestCwd(context.cwd)
        return buildList {
            add(proot.absolutePath)
            addAll(baseArguments(context.root, context.linuxDir, context.filesDir, cwd, context.bindMounts))
            addAll(guestEnvironment(interactive = false))
            addAll(
                listOf(
                    "-c",
                    // Command and cwd are positional args so the command text is evaluated exactly once.
                    resourceLimitScript(
                        maxFileSizeBytes = context.maxFileSizeBytes,
                        maxCpuTimeSeconds = context.maxCpuTimeSeconds,
                        maxVirtualMemoryBytes = context.maxVirtualMemoryBytes,
                        maxProcesses = context.maxProcesses,
                    ) + "cd -- \"\$1\" && eval \"\$2\"",
                    "rikkahub",
                    cwd,
                    context.command,
                )
            )
        }
    }

    fun interactiveArguments(
        root: String,
        linuxDir: File,
        filesDir: File,
        bindMounts: List<WorkspaceBindMount> = emptyList(),
        maxFileSizeBytes: Long? = null,
        maxCpuTimeSeconds: Long? = null,
        maxVirtualMemoryBytes: Long? = null,
        maxProcesses: Int? = null,
    ): List<String> = buildList {
        addAll(
            baseArguments(
                root = root,
                linuxDir = linuxDir,
                filesDir = filesDir,
                cwd = WorkspaceManager.ROOTFS_WORKSPACE_DIR,
                bindMounts = bindMounts,
            )
        )
        addAll(guestEnvironment(interactive = true))
        addAll(
            listOf(
                "-c",
                resourceLimitScript(
                    maxFileSizeBytes = maxFileSizeBytes,
                    maxCpuTimeSeconds = maxCpuTimeSeconds,
                    maxVirtualMemoryBytes = maxVirtualMemoryBytes,
                    maxProcesses = maxProcesses,
                ) + "exec /bin/bash -l",
                "rikkahub",
            )
        )
    }

    fun isolatedHostLaunch(
        command: List<String>,
        toybox: File = File("/system/bin/toybox"),
        setsid: File = File("/system/bin/setsid"),
    ): WorkspaceProcessLaunch? = when {
        toybox.isFile && toybox.canExecute() -> WorkspaceProcessLaunch(
            command = listOf(toybox.absolutePath, "setsid") + command,
            isolatedProcessGroup = true,
        )
        setsid.isFile && setsid.canExecute() -> WorkspaceProcessLaunch(
            command = listOf(setsid.absolutePath) + command,
            isolatedProcessGroup = true,
        )
        else -> null
    }

    private fun resourceLimitScript(
        maxFileSizeBytes: Long?,
        maxCpuTimeSeconds: Long?,
        maxVirtualMemoryBytes: Long?,
        maxProcesses: Int?,
    ): String {
        val limits = buildList {
            maxFileSizeBytes?.let { add("-f" to shellBlocks(it)) }
            maxCpuTimeSeconds?.let { add("-t" to it.toString()) }
            maxVirtualMemoryBytes?.let { add("-v" to shellBlocks(it)) }
            maxProcesses?.let { add("-u" to it.toString()) }
        }
        if (limits.isEmpty()) return ""
        val commands = limits.joinToString(" && ") { (option, value) ->
            "cap_limit $option $value"
        }
        return """
            cap_limit() {
              current="${'$'}(ulimit -S "${'$'}1")" || return 1
              case "${'$'}current" in
                unlimited) ulimit -S "${'$'}1" "${'$'}2" ;;
                ''|*[!0-9]*) return 1 ;;
                *) [ "${'$'}current" -le "${'$'}2" ] || ulimit -S "${'$'}1" "${'$'}2" ;;
              esac
            }
            $commands || { echo 'Unable to apply workspace process limits' >&2; exit 125; }
        """.trimIndent() + "\n"
    }

    private fun shellBlocks(bytes: Long): String =
        (((bytes - 1) / SHELL_FILE_BLOCK_BYTES) + 1).toString()

    // Bash reports RLIMIT_FSIZE in 1024-byte blocks.
    private const val SHELL_FILE_BLOCK_BYTES = 1024L
}

data class WorkspaceProcessLaunch(
    val command: List<String>,
    val isolatedProcessGroup: Boolean,
)
