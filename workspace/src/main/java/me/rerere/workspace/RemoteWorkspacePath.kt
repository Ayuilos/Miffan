package me.rerere.workspace

/** Maps the virtual `/workspace` file view to a selected directory on a remote machine. */
internal class RemoteWorkspacePath(remoteRoot: String, private val aliasRoot: String? = null) {
    val root: String = remoteRoot.trimEnd('/').ifEmpty { "/" }.also { path ->
        require(path.startsWith('/')) { "Remote workspace root must be absolute" }
        require(path == "/" || path.split('/').drop(1).all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Remote workspace root must be a normalized absolute path"
        }
        require(path.none {
            it == '\\' || it == '\u0000' || it == '\n' || it == '\r' ||
                it == '*' || it == '?' || it == '[' || it == ']'
        }) {
            "Remote workspace root contains an invalid character"
        }
    }

    fun relative(path: String, allowRoot: Boolean = false): String {
        val unprefixed = when {
            path == root -> ""
            root != "/" && path.startsWith("$root/") -> path.removePrefix("$root/")
            aliasRoot != null && path == aliasRoot -> ""
            aliasRoot != null && path.startsWith("$aliasRoot/") -> path.removePrefix("$aliasRoot/")
            path == "/workspace" || path == "/workspace/" -> ""
            path.startsWith("/workspace/") -> path.removePrefix("/workspace/")
            root == "/" && path.startsWith('/') -> path.removePrefix("/")
            path.startsWith('/') -> throw IllegalArgumentException("Path must be relative to /workspace")
            else -> path
        }.trimEnd('/')
        if (unprefixed.isEmpty()) {
            require(allowRoot) { "A workspace file path is required" }
            return ""
        }
        require(unprefixed.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Path escapes workspace root: $path"
        }
        require(unprefixed.none {
            it == '\\' || it == '\u0000' || it == '\n' || it == '\r' ||
                it == '*' || it == '?' || it == '[' || it == ']'
        }) {
            "Path contains an invalid character"
        }
        return unprefixed
    }

    fun absolute(path: String, allowRoot: Boolean = false): String {
        val relative = relative(path, allowRoot)
        return if (relative.isEmpty()) root else if (root == "/") "/$relative" else "$root/$relative"
    }

    fun ancestorPaths(path: String): List<String> {
        val segments = absolute(path, allowRoot = true).split('/').filter { it.isNotEmpty() }
        return listOf("/") + segments.indices.map { index ->
            "/" + segments.take(index + 1).joinToString("/")
        }
    }
}
