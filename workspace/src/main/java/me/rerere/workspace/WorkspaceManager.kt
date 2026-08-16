package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
) {
    private val fileSystem = WorkspaceFileSystem(config)

    // 按 target 长度降序, 保证 /a/b 优先于 /a 匹配
    private val sortedBindMounts = bindMounts.sortedByDescending { it.guestTarget.value.length }
    private val executionLocks = ConcurrentHashMap<String, ReentrantLock>()

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean = File(linuxDir(root), "bin/sh").isFile

    fun deleteWorkspace(root: String): Boolean {
        return withExclusiveAccess(root) {
            workspaceDir(root).deleteRecursively()
        }
    }

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * 映射目录的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类工具专用路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(root: String, path: String): RootfsLocation {
        val guestPath = GuestPath.parse(path)

        sortedBindMounts.forEach { mount ->
            if (guestPath.isWithin(mount.guestTarget)) {
                return RootfsLocation(
                    rootDir = mount.source,
                    relativePath = guestPath.relativeTo(mount.guestTarget),
                    guestPath = guestPath,
                    writable = mount.writableByTools,
                )
            }
        }

        if (guestPath.isWithin(ROOTFS_WORKSPACE_PATH)) {
            return RootfsLocation(
                rootDir = filesDir(root),
                relativePath = guestPath.relativeTo(ROOTFS_WORKSPACE_PATH),
                guestPath = guestPath,
            )
        }

        // 内核伪文件系统: 显式拒绝, 而不是回落到一个必然读不到的物理路径
        KERNEL_FS_PATHS.firstOrNull { guestPath.isWithin(it) }?.let {
            error("${it.value} is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return RootfsLocation(
            rootDir = linuxDir(root),
            relativePath = guestPath.relativeTo(GuestPath.ROOT),
            guestPath = guestPath,
        )
    }

    fun rootfsFileSize(root: String, path: String): Long =
        resolveRootfsFile(root, path).also { it.requireReadableFile(path) }.length()

    fun exportRootfsFile(root: String, path: String, outputStream: OutputStream) {
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    fun writeRootfsText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = withExclusiveAccess(root) {
        val location = resolveRootfsPath(root, path)
        require(location.writable) { "Path is read-only: ${location.guestPath.value}" }
        fileSystem.writeTextNoFollow(
            root = location.rootDir,
            path = location.relativePath,
            text = text,
            overwrite = overwrite,
            charset = charset,
        ).copy(
            path = location.guestPath.value,
            name = location.guestPath.name,
        )
    }

    private fun resolveRootfsFile(root: String, path: String): File {
        val location = resolveRootfsPath(root, path)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.move(filesDir(root), source, target, overwrite)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }
        // Never pass the caller's possibly aliased spelling into PRoot. The host-side validation
        // above and the guest process must use the same canonical workspace-relative directory.
        val canonicalCwd = workingDir.relativeTo(filesDir(root).canonicalFile).invariantSeparatorsPath

        return withExclusiveAccess(root, interruptible = true) {
            shellRunner.execute(
                WorkspaceShellContext(
                    root = root,
                    command = command,
                    cwd = canonicalCwd,
                    filesDir = filesDir(root),
                    linuxDir = linuxDir(root),
                    tempDir = tempDir(root),
                    workingDir = workingDir,
                    timeoutMillis = timeoutMillis,
                    stdin = stdin,
                    bindMounts = bindMounts,
                )
            )
        }
    }

    internal fun <T> withExclusiveAccess(
        root: String,
        interruptible: Boolean = false,
        block: () -> T,
    ): T {
        requireValidRoot(root)
        val lock = executionLocks.computeIfAbsent(root) { ReentrantLock() }
        if (interruptible) lock.lockInterruptibly() else lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX)) continue
            withExclusiveAccess(root) {
                // PRoot temp files
                tempDir(root).let { if (it.exists()) it.deleteRecursively() }
                // Rootfs /tmp and /var/tmp
                File(linuxDir(root), "tmp").let { if (it.exists()) it.deleteRecursively() }
                File(linuxDir(root), "var/tmp").let { if (it.exists()) it.deleteRecursively() }
            }
        }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"
        val ROOTFS_WORKSPACE_PATH: GuestPath = GuestPath.parse(ROOTFS_WORKSPACE_DIR)

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")
        private val KERNEL_FS_PATHS = KERNEL_FS_MOUNTS.map { GuestPath.parse(it) }

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}

/** Rootfs 内绝对路径在宿主机上的落点 */
data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
    val guestPath: GuestPath,
    val writable: Boolean = true,
)
