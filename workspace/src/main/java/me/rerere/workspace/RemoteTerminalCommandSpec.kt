package me.rerere.workspace

/** A single user-started command in its own PTY; the channel exits with the command's status. */
data class RemoteTerminalCommandSpec(val command: String, val cwd: String) {
    init {
        require(command.isNotBlank() && command.length <= 32_768) { "Invalid terminal command length" }
        require(command.none { (it.code < 32 && it != '\n' && it != '\t') || it.code == 127 }) {
            "Terminal command contains control characters"
        }
    }

    internal fun shellScript(root: String, platform: RemotePlatform = RemotePlatform.POSIX): String {
        if (!platform.supportsInteractiveTerminal) throw RemoteTerminalUnsupportedException()
        val directory = workingDirectory(root, cwd)
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        return "cd ${quote(directory)} || exit 1\n${RemoteShell.utf8Locale}\nexec /bin/sh -c ${quote(command)}"
    }

    companion object {
        fun workingDirectory(root: String, cwd: String): String =
            RemoteWorkspacePath(root).absolute(cwd, allowRoot = true)
    }
}
