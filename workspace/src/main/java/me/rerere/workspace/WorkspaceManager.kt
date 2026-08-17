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
    private val sessionRegistry: WorkspaceSessionRegistry =
        WorkspaceSessionRegistry(config.resourceLimits),
    private val processSupervisor: WorkspaceProcessSupervisor =
        WorkspaceProcessSupervisor(File(baseDir, "$RUNTIME_DIR/processes")),
) {
    private val fileSystem = WorkspaceFileSystem(config)

    // 按 target 长度降序, 保证 /a/b 优先于 /a 匹配
    private val sortedBindMounts = bindMounts.sortedByDescending { it.guestTarget.value.length }
    private val executionLocks = ConcurrentHashMap<String, ReentrantLock>()

    val resourceLimits: WorkspaceResourceLimits
        get() = config.resourceLimits

    val processRecoveryReport: WorkspaceProcessRecoveryReport

    init {
        baseDir.mkdirs()
        processRecoveryReport = processSupervisor.recoverStaleProcesses()
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
            val deleted = workspaceDir(root).deleteRecursively()
            sortedBindMounts.asSequence()
                .filter { it.workspaceScoped }
                .forEach { it.sourceFor(root).deleteRecursively() }
            deleted
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
    ): WorkspaceFileEntry = withExclusiveAccess(root) {
        val target = fileSystem.resolve(filesDir(root), path)
        requireGrowth(
            root = root,
            area = WorkspaceDiskArea.FILES,
            additionalBytes = positiveGrowth(target, text.toByteArray(charset).size.toLong()),
        )
        fileSystem.writeText(filesDir(root), path, text, overwrite, charset)
    }

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        return withExclusiveAccess(root) {
            val areaRoot = areaDir(root, area)
            val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
            fileSystem.importBytes(
                root = areaRoot,
                path = targetPath,
                inputStream = inputStream,
                maxBytes = remainingGrowth(root, area.toDiskArea()),
            )
        }
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
        requireValidRoot(root)
        val guestPath = GuestPath.parse(path)

        sortedBindMounts.forEach { mount ->
            if (guestPath.isWithin(mount.guestTarget)) {
                return RootfsLocation(
                    rootDir = mount.sourceFor(root),
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
        val target = File(location.rootDir, location.relativePath)
        requireGrowth(
            root = root,
            area = location.diskArea(root),
            additionalBytes = positiveGrowth(target, text.toByteArray(charset).size.toLong()),
        )
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
    ): Boolean = withExclusiveAccess(root) {
        fileSystem.delete(areaDir(root, area), path, recursive)
    }

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        withExclusiveAccess(root) {
            fileSystem.move(filesDir(root), source, target, overwrite)
        }

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
        require(timeoutMillis > 0) { "Command timeout must be positive" }
        return withExclusiveAccess(root, interruptible = true) {
            requireWithinResourceLimits(root)
            val workingDir = fileSystem.resolve(filesDir(root), cwd)
            require(workingDir.exists()) { "Working directory does not exist: $cwd" }
            require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }
            // Never pass the caller's possibly aliased spelling into PRoot. The host-side
            // validation and guest process use the same canonical relative directory.
            val canonicalCwd = workingDir
                .relativeTo(filesDir(root).canonicalFile)
                .invariantSeparatorsPath
            val result = shellRunner.execute(
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
                    maxFileSizeBytes = config.resourceLimits.maxShellFileBytes,
                    maxCpuTimeSeconds = config.resourceLimits.maxShellCpuTimeSeconds,
                    maxVirtualMemoryBytes = config.resourceLimits.maxShellVirtualMemoryBytes,
                    maxProcesses = config.resourceLimits.maxShellProcesses,
                    resourceGuard = commandResourceGuard(root),
                    processSupervisor = processSupervisor,
                )
            )
            val postflightError = runCatching { requireWithinResourceLimits(root) }.exceptionOrNull()
            if (postflightError is WorkspaceResourceLimitException && !result.resourceLimitExceeded) {
                result.copy(
                    exitCode = -1,
                    stderr = buildString {
                        append(result.stderr)
                        if (isNotEmpty() && !endsWith('\n')) appendLine()
                        append("Resource limit exceeded: ${postflightError.message}")
                    },
                    resourceLimitExceeded = true,
                )
            } else {
                result
            }
        }
    }

    fun tryAcquireInteractiveSession(root: String): WorkspaceSessionLease? {
        requireValidRoot(root)
        val lease = sessionRegistry.tryAcquire(root) ?: return null
        return try {
            requireWithinResourceLimits(root)
            lease
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    fun activeSessions(root: String? = null): Int = sessionRegistry.activeSessions(root)

    fun registerInteractiveProcess(
        root: String,
        pid: Long,
        commandIdentity: String,
    ): WorkspaceProcessRegistration {
        requireValidRoot(root)
        return requireNotNull(
            processSupervisor.register(
                root = root,
                pid = pid,
                isolatedProcessGroup = true,
                commandIdentity = commandIdentity,
            )
        ) { "Workspace terminal process exited before it could be registered" }
    }

    fun diskUsage(root: String): WorkspaceDiskUsage {
        requireValidRoot(root)
        val workspace = workspaceDir(root)
        val files = filesDir(root).logicalTreeSize()
        val rootfs = linuxDir(root).logicalTreeSize()
        val temp = tempDir(root).logicalTreeSize()
        val toolOutputs = toolOutputDir(root)?.logicalTreeSize() ?: 0
        val other = workspace.listFiles()
            .orEmpty()
            .asSequence()
            .filterNot { it.name == FILES_DIR || it.name == LINUX_DIR || it.name == TEMP_DIR }
            .fold(0L) { total, file ->
                runCatching { Math.addExact(total, file.logicalTreeSize()) }.getOrDefault(Long.MAX_VALUE)
            }
        val capacityRoot = workspace.takeIf { it.exists() } ?: baseDir
        return WorkspaceDiskUsage(
            filesBytes = files,
            rootfsBytes = rootfs,
            tempBytes = temp,
            otherWorkspaceBytes = other,
            toolOutputBytes = toolOutputs,
            usableSpaceBytes = capacityRoot.usableSpace,
        )
    }

    fun checkResourceLimits(root: String) {
        requireValidRoot(root)
        requireWithinResourceLimits(root)
    }

    fun createResourceGuard(root: String): WorkspaceResourceGuard {
        requireValidRoot(root)
        return commandResourceGuard(root)
    }

    fun writeToolOutput(root: String, fileName: String, text: String): WorkspaceFileEntry =
        withExclusiveAccess(root) {
            require(
                fileName.matches(SAFE_FILE_NAME_REGEX) && fileName != "." && fileName != ".."
            ) { "Invalid tool output file name" }
            val outputDir = toolOutputDir(root) ?: error("Tool output mapping is not configured")
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > config.resourceLimits.maxToolOutputFileBytes) {
                throw WorkspaceResourceLimitException(
                    "Tool output exceeds per-file limit: " +
                        "${bytes.size} bytes used, " +
                        "${config.resourceLimits.maxToolOutputFileBytes} bytes allowed"
                )
            }
            requireGrowth(root, WorkspaceDiskArea.TOOL_OUTPUTS, bytes.size.toLong())
            outputDir.mkdirs()
            fileSystem.writeTextNoFollow(
                root = outputDir,
                path = fileName,
                text = text,
                overwrite = false,
            ).copy(path = "/tool_outputs/$fileName")
        }

    internal fun requireAdditionalCapacity(
        root: String,
        area: WorkspaceDiskArea,
        additionalBytes: Long,
    ) {
        requireGrowth(root, area, additionalBytes)
    }

    internal fun <T> withExclusiveAccess(
        root: String,
        interruptible: Boolean = false,
        block: () -> T,
    ): T {
        requireValidRoot(root)
        val lease = if (interruptible) {
            sessionRegistry.acquire(root)
        } else {
            try {
                sessionRegistry.acquire(root)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
        }
        lease.use {
            val lock = executionLocks.computeIfAbsent(root) { ReentrantLock() }
            if (interruptible) lock.lockInterruptibly() else lock.lock()
            return try {
                block()
            } finally {
                lock.unlock()
            }
        }
    }

    private fun commandResourceGuard(root: String): WorkspaceResourceGuard {
        var lastFullCheck = System.nanoTime()
        return WorkspaceResourceGuard {
            requireFreeSpace(root)
            val now = System.nanoTime()
            if (now - lastFullCheck >=
                config.resourceLimits.fullDiskCheckIntervalMillis * 1_000_000
            ) {
                requireWithinResourceLimits(root)
                lastFullCheck = now
            }
        }
    }

    private fun requireWithinResourceLimits(root: String) {
        val usage = diskUsage(root)
        val limits = config.resourceLimits
        fun requireAtMost(actual: Long, maximum: Long, label: String) {
            if (actual > maximum) {
                throw WorkspaceResourceLimitException(
                    "$label exceeds limit: $actual bytes used, $maximum bytes allowed"
                )
            }
        }
        requireAtMost(usage.filesBytes, limits.maxFilesBytes, "Workspace files")
        requireAtMost(usage.rootfsBytes, limits.maxRootfsBytes, "Workspace Rootfs")
        requireAtMost(usage.tempBytes, limits.maxTempBytes, "Workspace temp")
        requireAtMost(usage.workspaceBytes, limits.maxWorkspaceBytes, "Workspace total")
        requireAtMost(usage.toolOutputBytes, limits.maxToolOutputBytes, "Workspace tool outputs")
        requireFreeSpace(root, usage)
    }

    private fun requireFreeSpace(root: String, usage: WorkspaceDiskUsage? = null) {
        val usableSpace = usage?.usableSpaceBytes
            ?: (workspaceDir(root).takeIf { it.exists() } ?: baseDir).usableSpace
        if (usableSpace < config.resourceLimits.minFreeSpaceBytes) {
            throw WorkspaceResourceLimitException(
                "Device free-space reserve reached: $usableSpace bytes available, " +
                    "${config.resourceLimits.minFreeSpaceBytes} bytes reserved"
            )
        }
    }

    private fun requireGrowth(root: String, area: WorkspaceDiskArea, additionalBytes: Long) {
        require(additionalBytes >= 0) { "Additional bytes must not be negative" }
        if (additionalBytes == 0L) return
        val remaining = remainingGrowth(root, area)
        if (additionalBytes > remaining) {
            throw WorkspaceResourceLimitException(
                "$area write requires $additionalBytes bytes but only $remaining bytes remain"
            )
        }
    }

    private fun remainingGrowth(root: String, area: WorkspaceDiskArea): Long {
        val usage = diskUsage(root)
        val limits = config.resourceLimits
        val areaRemaining = when (area) {
            WorkspaceDiskArea.FILES -> limits.maxFilesBytes - usage.filesBytes
            WorkspaceDiskArea.ROOTFS -> limits.maxRootfsBytes - usage.rootfsBytes
            WorkspaceDiskArea.TEMP -> limits.maxTempBytes - usage.tempBytes
            WorkspaceDiskArea.TOOL_OUTPUTS -> limits.maxToolOutputBytes - usage.toolOutputBytes
            WorkspaceDiskArea.OTHER -> limits.maxWorkspaceBytes - usage.workspaceBytes
        }
        val totalRemaining = if (area == WorkspaceDiskArea.TOOL_OUTPUTS) {
            Long.MAX_VALUE
        } else {
            limits.maxWorkspaceBytes - usage.workspaceBytes
        }
        val freeRemaining = usage.usableSpaceBytes - limits.minFreeSpaceBytes
        return minOf(areaRemaining, totalRemaining, freeRemaining).coerceAtLeast(0)
    }

    private fun positiveGrowth(target: File, newSize: Long): Long =
        (newSize - target.takeIf { it.isFile }?.length().orZero()).coerceAtLeast(0)

    private fun Long?.orZero(): Long = this ?: 0

    private fun WorkspaceStorageArea.toDiskArea(): WorkspaceDiskArea = when (this) {
        WorkspaceStorageArea.FILES -> WorkspaceDiskArea.FILES
        WorkspaceStorageArea.LINUX -> WorkspaceDiskArea.ROOTFS
    }

    private fun RootfsLocation.diskArea(root: String): WorkspaceDiskArea = when (rootDir) {
        filesDir(root) -> WorkspaceDiskArea.FILES
        linuxDir(root) -> WorkspaceDiskArea.ROOTFS
        tempDir(root) -> WorkspaceDiskArea.TEMP
        else -> WorkspaceDiskArea.OTHER
    }

    private fun toolOutputDir(root: String): File? = sortedBindMounts
        .firstOrNull { it.guestTarget == TOOL_OUTPUTS_PATH && it.workspaceScoped }
        ?.sourceFor(root)

    private fun requireValidRoot(root: String) {
        require(
            root != "." && root != ".." && root != RUNTIME_DIR && root.matches(ROOT_NAME_REGEX)
        ) {
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
            if (root == RUNTIME_DIR || !root.matches(ROOT_NAME_REGEX)) continue
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
        private const val RUNTIME_DIR = ".runtime"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"
        val ROOTFS_WORKSPACE_PATH: GuestPath = GuestPath.parse(ROOTFS_WORKSPACE_DIR)

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")
        private val KERNEL_FS_PATHS = KERNEL_FS_MOUNTS.map { GuestPath.parse(it) }

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
        private val SAFE_FILE_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
        private val TOOL_OUTPUTS_PATH = GuestPath.parse("/tool_outputs")
    }
}

/** Rootfs 内绝对路径在宿主机上的落点 */
data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
    val guestPath: GuestPath,
    val writable: Boolean = true,
)
