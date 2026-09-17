package me.ayuilos.miffan.data.repository

/** Rootfs file-tool paths use /workspace, while remote shell output contains real absolute paths. */
internal fun mapRemoteRootfsPath(remoteRoot: String, path: String): String {
    val root = remoteRoot.trimEnd('/').ifEmpty { "/" }
    return when {
        path == root -> ""
        root != "/" && path.startsWith("$root/") -> path.removePrefix("$root/")
        path == "/workspace" -> ""
        path.startsWith("/workspace/") -> path.removePrefix("/workspace/")
        root == "/" && path.startsWith("/") -> path.removePrefix("/")
        else -> error("Path is outside remote workspace: $path")
    }
}
