package me.rerere.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

data class WorkspaceBindMount(
    val source: File,
    val target: String,
    /** Whether this host directory is exposed to commands running under PRoot. */
    val exposeToShell: Boolean = true,
    /** Whether dedicated workspace file tools may write through this mapping. */
    val writableByTools: Boolean = true,
    /** Resolve the host source under a workspace-specific child directory. */
    val workspaceScoped: Boolean = false,
) {
    internal val guestTarget = GuestPath.parse(target, "Bind mount target")

    init {
        require(guestTarget != GuestPath.ROOT) { "Bind mount target must not replace the Rootfs root" }
    }

    internal fun sourceFor(root: String): File = if (workspaceScoped) File(source, root) else source
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!RootfsHealth.isHealthy(context.linuxDir)) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        require(Files.isDirectory(context.filesDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Workspace files directory must not be a symbolic link"
        }
        require(Files.isDirectory(context.tempDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Workspace temp directory must not be a symbolic link"
        }
        patcher.patch(context.linuxDir)
        val process = WorkspaceNativeProcess.start(
            command = ProotExecutionSpec.nonInteractiveCommand(context, proot),
            environment = ProotExecutionSpec.hostEnvironment(loader, context.tempDir),
            workingDirectory = context.filesDir,
        )

        return process.readTrackedResult(
            context = context,
            isolatedProcessGroup = true,
            commandIdentity = proot.absolutePath,
        )
    }

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
    }
}
