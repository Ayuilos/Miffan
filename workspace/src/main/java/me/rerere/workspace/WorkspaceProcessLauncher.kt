package me.rerere.workspace

import java.io.File

/** Public narrow entry point used by the separately signed/installed Workspace executor host. */
object WorkspaceProcessLauncher {
    fun start(
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: File,
    ): Process = WorkspaceNativeProcess.start(command, environment, workingDirectory)
}
