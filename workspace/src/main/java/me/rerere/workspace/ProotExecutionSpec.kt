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
            .filter { it.exposeToShell && it.source.exists() }
            .forEach { mount ->
                add("-b")
                add("${mount.source.absolutePath}:${mount.guestTarget.value}")
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
            addAll(baseArguments(context.linuxDir, context.filesDir, cwd, context.bindMounts))
            addAll(guestEnvironment(interactive = false))
            addAll(
                listOf(
                    "-c",
                    // Command and cwd are positional args so the command text is evaluated exactly once.
                    "cd -- \"\$1\" && eval \"\$2\"",
                    "rikkahub",
                    cwd,
                    context.command,
                )
            )
        }
    }

    fun interactiveArguments(
        linuxDir: File,
        filesDir: File,
        bindMounts: List<WorkspaceBindMount> = emptyList(),
    ): List<String> = buildList {
        addAll(
            baseArguments(
                linuxDir = linuxDir,
                filesDir = filesDir,
                cwd = WorkspaceManager.ROOTFS_WORKSPACE_DIR,
                bindMounts = bindMounts,
            )
        )
        addAll(guestEnvironment(interactive = true))
    }
}
