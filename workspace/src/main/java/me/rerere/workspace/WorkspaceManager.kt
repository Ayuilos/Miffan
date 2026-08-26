package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    bindMounts: List<WorkspaceBindMount> = emptyList(),
    private val sessionRegistry: WorkspaceSessionRegistry =
        WorkspaceSessionRegistry(config.resourceLimits),
    private val processSupervisor: WorkspaceProcessSupervisor =
        WorkspaceProcessSupervisor(File(baseDir, "$RUNTIME_DIR/processes")),
) {
    private val fileSystem = WorkspaceFileSystem(config)
    private val workspaceSnapshot = WorkspaceSnapshot(fileSystem, config.resourceLimits)
    private val bindMounts = bindMounts.toList()

    // 按 target 长度降序, 保证 /a/b 优先于 /a 匹配
    private val sortedBindMounts = bindMounts.sortedByDescending { it.guestTarget.value.length }
    private val executionLocks = ConcurrentHashMap<String, ReentrantLock>()

    val resourceLimits: WorkspaceResourceLimits
        get() = config.resourceLimits

    /** The single mount definition consumed by both AI and interactive PRoot specifications. */
    val executionBindMounts: List<WorkspaceBindMount>
        get() = bindMounts.toList()

    val processRecoveryReport: WorkspaceProcessRecoveryReport

    init {
        baseDir.mkdirs()
        require(Files.isDirectory(baseDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Workspace base directory must not be a symbolic link"
        }
        val workspaceStorage = baseDir.toPath().toAbsolutePath().normalize()
        bindMounts.forEach { mount ->
            val source = mount.source.toPath().toAbsolutePath().normalize()
            require(!source.startsWith(workspaceStorage) && !workspaceStorage.startsWith(source)) {
                "Bind mount source must not overlap Workspace storage: ${mount.source}"
            }
        }
        processRecoveryReport = processSupervisor.recoverStaleProcesses()
        recoverInterruptedFileSnapshots()
    }

    fun ensureWorkspace(root: String): File {
        requireValidRoot(root)
        val dir = baseDir.ensureDirectoryNoFollow(root)
        dir.ensureDirectoryNoFollow(FILES_DIR)
        dir.ensureDirectoryNoFollow(LINUX_DIR)
        dir.ensureDirectoryNoFollow(TEMP_DIR)
        return dir
    }

    /** Lazily creates the private directories belonging to one assistant scope. */
    fun ensureScope(root: String, scope: WorkspaceScope): WorkspaceScopeDirectories {
        val workspace = ensureWorkspace(root)
        if (scope.isLegacyWholeWorkspace) {
            return WorkspaceScopeDirectories(
                files = filesDir(root),
                home = File(linuxDir(root), ROOT_HOME_DIR),
                temp = File(linuxDir(root), ROOTFS_TEMP_DIR),
                varTemp = File(linuxDir(root), ROOTFS_VAR_TEMP_DIR),
                prootTemp = tempDir(root),
            )
        }
        val scopes = workspace.ensureDirectoryNoFollow(SCOPES_DIR)
        val scopeRoot = scopes.ensureDirectoryNoFollow(requireNotNull(scope.id))
        return WorkspaceScopeDirectories(
            files = scopeRoot.ensureDirectoryNoFollow(SCOPE_FILES_DIR),
            home = scopeRoot.ensureDirectoryNoFollow(SCOPE_HOME_DIR),
            temp = scopeRoot.ensureDirectoryNoFollow(SCOPE_TEMP_DIR),
            varTemp = scopeRoot.ensureDirectoryNoFollow(SCOPE_VAR_TEMP_DIR),
            prootTemp = scopeRoot.ensureDirectoryNoFollow(SCOPE_PROOT_TEMP_DIR),
        )
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(
        root: String,
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ): File = if (scope.isLegacyWholeWorkspace) {
        File(workspaceDir(root), FILES_DIR)
    } else {
        File(File(File(workspaceDir(root), SCOPES_DIR), requireNotNull(scope.id)), SCOPE_FILES_DIR)
    }

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean =
        Files.isDirectory(workspaceDir(root).toPath(), LinkOption.NOFOLLOW_LINKS) &&
            RootfsHealth.isHealthy(linuxDir(root))

    fun deleteWorkspace(root: String): Boolean {
        return withExclusiveAccess(root) {
            val deleted = baseDir.deleteRelativeTreeNoFollow(root)
            sortedBindMounts.asSequence()
                .filter { it.workspaceScoped }
                .forEach { mount ->
                    val directory = mount.sourceFor(root)
                    if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        requireNotNull(directory.parentFile).deleteRelativeTreeNoFollow(directory.name)
                    }
                }
            deleted
        }
    }

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area, scope), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ): String = fileSystem.readText(filesDir(root, scope), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ): WorkspaceFileEntry = withExclusiveAccess(root) {
        ensureScope(root, scope)
        requireGrowth(
            root = root,
            area = WorkspaceDiskArea.FILES,
            additionalBytes = positiveGrowth(
                rootDir = filesDir(root, scope),
                path = path,
                newSize = text.toByteArray(charset).size.toLong(),
            ),
        )
        fileSystem.writeText(filesDir(root, scope), path, text, overwrite, charset)
    }

    /**
     * Runs one host-side mutation of the workspace files area while shell and file operations are
     * excluded. [additionalBytes] is admitted against both the files-area and total-workspace
     * limits before [block] starts.
     *
     * Callers must keep every write below the supplied directory and must preserve no-follow path
     * handling. This boundary exists for atomic multi-file publishers that cannot use [writeText]
     * one file at a time.
     */
    fun <T> withFilesWriteAccess(
        root: String,
        additionalBytes: Long,
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
        block: (File) -> T,
    ): T = withExclusiveAccess(root) {
        ensureScope(root, scope)
        requireGrowth(root, WorkspaceDiskArea.FILES, additionalBytes)
        block(filesDir(root, scope))
    }

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ): WorkspaceFileEntry {
        return withExclusiveAccess(root) {
            ensureScope(root, scope)
            val areaRoot = areaDir(root, area, scope)
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
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ): Long = fileSystem.fileSizeNoFollow(areaDir(root, area, scope), path)

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
        maxBytes: Long = Long.MAX_VALUE,
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ) {
        outputStream.use { out ->
            fileSystem.exportNoFollow(
                root = areaDir(root, area, scope),
                path = path,
                outputStream = out,
                maxBytes = maxBytes,
            )
        }
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * 映射目录的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类工具专用路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(
        root: String,
        path: String,
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ): RootfsLocation {
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
                rootDir = filesDir(root, scope),
                relativePath = guestPath.relativeTo(ROOTFS_WORKSPACE_PATH),
                guestPath = guestPath,
            )
        }

        if (!scope.isLegacyWholeWorkspace) {
            val directories = ensureScope(root, scope)
            SCOPED_PRIVATE_GUEST_ROOTS.firstOrNull { (guestRoot, _) ->
                guestPath.isWithin(guestRoot)
            }?.let { (guestRoot, area) ->
                val rootDir = when (area) {
                    WorkspaceStorageArea.HOME -> directories.home
                    WorkspaceStorageArea.TEMP -> directories.temp
                    WorkspaceStorageArea.VAR_TEMP -> directories.varTemp
                    else -> error("Invalid private scope mapping")
                }
                return RootfsLocation(
                    rootDir = rootDir,
                    relativePath = guestPath.relativeTo(guestRoot),
                    guestPath = guestPath,
                )
            }
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

    fun rootfsFileSize(
        root: String,
        path: String,
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ): Long {
        val location = resolveRootfsPath(root, path, scope)
        return fileSystem.fileSizeNoFollow(
            root = location.rootDir,
            path = location.relativePath,
            displayPath = location.guestPath.value,
        )
    }

    fun exportRootfsFile(
        root: String,
        path: String,
        outputStream: OutputStream,
        maxBytes: Long = MAX_ROOTFS_TOOL_READ_BYTES,
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ) {
        val location = resolveRootfsPath(root, path, scope)
        outputStream.use { out ->
            fileSystem.exportNoFollow(
                root = location.rootDir,
                path = location.relativePath,
                outputStream = out,
                maxBytes = maxBytes,
                displayPath = location.guestPath.value,
            )
        }
    }

    fun writeRootfsText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ): WorkspaceFileEntry = withExclusiveAccess(root) {
        ensureScope(root, scope)
        val location = resolveRootfsPath(root, path, scope)
        require(location.writable) { "Path is read-only: ${location.guestPath.value}" }
        requireGrowth(
            root = root,
            area = location.diskArea(root),
            additionalBytes = positiveGrowth(
                rootDir = location.rootDir,
                path = location.relativePath,
                newSize = text.toByteArray(charset).size.toLong(),
                displayPath = location.guestPath.value,
            ),
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

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ): Boolean = withExclusiveAccess(root) {
        fileSystem.delete(areaDir(root, area, scope), path, recursive)
    }

    fun moveFile(
        root: String,
        source: String,
        target: String,
        overwrite: Boolean = false,
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ): WorkspaceFileEntry =
        withExclusiveAccess(root) {
            fileSystem.move(filesDir(root, scope), source, target, overwrite)
        }

    fun glob(
        root: String,
        pattern: String,
        path: String = "",
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ): List<WorkspaceFileEntry> = fileSystem.glob(filesDir(root, scope), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root, scope), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        scope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        require(timeoutMillis > 0) { "Command timeout must be positive" }
        return withExclusiveAccess(root, interruptible = true) {
            val scopeDirectories = ensureScope(root, scope)
            requireWithinResourceLimits(root)
            val canonicalCwd = fileSystem.canonicalRelativeDirectoryPath(cwd)
            val workingDir = fileSystem.resolveDirectoryNoFollow(
                scopeDirectories.files,
                canonicalCwd,
            )
            val result = shellRunner.execute(
                WorkspaceShellContext(
                    root = root,
                    command = command,
                    cwd = canonicalCwd,
                    filesDir = scopeDirectories.files,
                    linuxDir = linuxDir(root),
                    tempDir = scopeDirectories.prootTemp,
                    homeDir = scopeDirectories.home.takeUnless { scope.isLegacyWholeWorkspace },
                    guestTempDir = scopeDirectories.temp.takeUnless { scope.isLegacyWholeWorkspace },
                    guestVarTempDir = scopeDirectories.varTemp.takeUnless { scope.isLegacyWholeWorkspace },
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

    fun exportFilesSnapshot(root: String, outputStream: OutputStream) = withExclusiveAccess(root) {
        ensureWorkspace(root)
        recoverFilesSnapshot(workspaceDir(root))
        requireWithinResourceLimits(root)
        workspaceSnapshot.export(filesDir(root), outputStream)
    }

    fun replaceFilesSnapshot(root: String, inputStream: InputStream) = withExclusiveAccess(root) {
        ensureWorkspace(root)
        val workspace = workspaceDir(root)
        recoverFilesSnapshot(workspace)
        val staging = File(workspace, FILES_STAGING_DIR)
        val backup = File(workspace, FILES_BACKUP_DIR)
        staging.deleteAsWorkspaceChild()
        workspace.ensureDirectoryNoFollow(FILES_STAGING_DIR)
        try {
            workspaceSnapshot.import(staging, inputStream)
            swapFilesSnapshot(workspace, staging, backup) { requireWithinResourceLimits(root) }
        } finally {
            staging.deleteAsWorkspaceChild()
        }
    }

    /** Holds the workspace admission/serialization lock across a brokered snapshot round trip. */
    fun <T> withFilesSnapshotExchange(
        root: String,
        block: (
            encodedSize: Long,
            export: (OutputStream) -> Unit,
            replace: (InputStream) -> Unit,
        ) -> T,
    ): T = withExclusiveAccess(root, interruptible = true) {
        ensureWorkspace(root)
        recoverFilesSnapshot(workspaceDir(root))
        requireWithinResourceLimits(root)
        block(
            workspaceSnapshot.encodedSize(filesDir(root)),
            { output -> workspaceSnapshot.export(filesDir(root), output) },
            { input ->
                val workspace = workspaceDir(root)
                val staging = File(workspace, FILES_STAGING_DIR)
                val backup = File(workspace, FILES_BACKUP_DIR)
                staging.deleteAsWorkspaceChild()
                workspace.ensureDirectoryNoFollow(FILES_STAGING_DIR)
                try {
                    workspaceSnapshot.import(staging, input)
                    swapFilesSnapshot(workspace, staging, backup) { requireWithinResourceLimits(root) }
                } finally {
                    staging.deleteAsWorkspaceChild()
                }
            },
        )
    }

    private fun swapFilesSnapshot(
        workspace: File,
        staging: File,
        backup: File,
        validate: () -> Unit,
    ) {
        val files = File(workspace, FILES_DIR)
        val committedBackup = File(workspace, FILES_COMMITTED_BACKUP_DIR)
        committedBackup.deleteAsWorkspaceChild()
        require(files.renameDirectoryNoFollow(backup)) { "Failed to stage workspace files for rollback" }
        try {
            require(staging.renameDirectoryNoFollow(files)) { "Failed to activate workspace files snapshot" }
            validate()
            require(backup.renameDirectoryNoFollow(committedBackup)) {
                "Failed to commit workspace files snapshot"
            }
            // The rename above is the commit point. A failed cleanup is recovered on next access.
            runCatching { committedBackup.deleteAsWorkspaceChild() }
        } catch (error: Throwable) {
            runCatching { files.deleteAsWorkspaceChild() }
            if (!backup.renameDirectoryNoFollow(files)) {
                throw IllegalStateException(
                    "Failed to roll back workspace files snapshot; backup was preserved",
                    error,
                )
            }
            throw error
        }
    }

    private fun recoverInterruptedFileSnapshots() {
        baseDir.listFiles()
            .orEmpty()
            .filter { directory ->
                directory.name != RUNTIME_DIR &&
                    directory.name.matches(ROOT_NAME_REGEX) &&
                    Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
            }
            .forEach(::recoverFilesSnapshot)
    }

    private fun recoverFilesSnapshot(workspace: File) {
        val files = File(workspace, FILES_DIR)
        val staging = File(workspace, FILES_STAGING_DIR)
        val backup = File(workspace, FILES_BACKUP_DIR)
        val committedBackup = File(workspace, FILES_COMMITTED_BACKUP_DIR)
        require(!(Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            Files.exists(committedBackup.toPath(), LinkOption.NOFOLLOW_LINKS))) {
            "Workspace contains conflicting snapshot recovery states"
        }
        if (Files.exists(committedBackup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            committedBackup.deleteAsWorkspaceChild()
        }
        if (Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            files.deleteAsWorkspaceChild()
            require(backup.renameDirectoryNoFollow(files)) {
                "Failed to restore interrupted workspace files snapshot"
            }
        }
        staging.deleteAsWorkspaceChild()
    }

    private fun File.deleteAsWorkspaceChild(): Boolean =
        requireNotNull(parentFile).deleteRelativeTreeNoFollow(name)

    fun tryAcquireInteractiveSession(root: String): WorkspaceSessionLease? {
        requireValidRoot(root)
        val lease = sessionRegistry.tryAcquire(root) ?: return null
        return try {
            ensureWorkspace(root)
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
        val scopes = File(workspace, SCOPES_DIR)
        val scopeFiles = scopes.listFiles()
            .orEmpty()
            .filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            .fold(0L) { total, scopeRoot ->
                val persistent = listOf(SCOPE_FILES_DIR, SCOPE_HOME_DIR)
                    .fold(0L) { scopeTotal, name ->
                        addSaturated(scopeTotal, File(scopeRoot, name).logicalTreeSize())
                    }
                addSaturated(total, persistent)
            }
        val files = addSaturated(filesDir(root).logicalTreeSize(), scopeFiles)
        val rootfs = linuxDir(root).logicalTreeSize()
        val scopeTemp = scopes.listFiles()
            .orEmpty()
            .filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            .fold(0L) { total, scopeRoot ->
                val transient = listOf(SCOPE_TEMP_DIR, SCOPE_VAR_TEMP_DIR, SCOPE_PROOT_TEMP_DIR)
                    .fold(0L) { scopeTotal, name ->
                        addSaturated(scopeTotal, File(scopeRoot, name).logicalTreeSize())
                    }
                addSaturated(total, transient)
            }
        val temp = addSaturated(tempDir(root).logicalTreeSize(), scopeTemp)
        val toolOutputs = toolOutputDir(root)?.logicalTreeSize() ?: 0
        val other = workspace.listFiles()
            .orEmpty()
            .asSequence()
            .filterNot {
                it.name == FILES_DIR || it.name == LINUX_DIR || it.name == TEMP_DIR ||
                    it.name == SCOPES_DIR
            }
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

    private fun positiveGrowth(
        rootDir: File,
        path: String,
        newSize: Long,
        displayPath: String = path,
    ): Long = (newSize - fileSystem.existingFileSizeNoFollow(rootDir, path, displayPath).orZero())
        .coerceAtLeast(0)

    private fun Long?.orZero(): Long = this ?: 0

    private fun WorkspaceStorageArea.toDiskArea(): WorkspaceDiskArea = when (this) {
        WorkspaceStorageArea.FILES,
        WorkspaceStorageArea.HOME,
            -> WorkspaceDiskArea.FILES
        WorkspaceStorageArea.LINUX -> WorkspaceDiskArea.ROOTFS
        WorkspaceStorageArea.TEMP,
        WorkspaceStorageArea.VAR_TEMP,
            -> WorkspaceDiskArea.TEMP
    }

    private fun addSaturated(left: Long, right: Long): Long =
        runCatching { Math.addExact(left, right) }.getOrDefault(Long.MAX_VALUE)

    private fun RootfsLocation.diskArea(root: String): WorkspaceDiskArea {
        val workspace = workspaceDir(root).toPath().toAbsolutePath().normalize()
        val location = rootDir.toPath().toAbsolutePath().normalize()
        val scopes = workspace.resolve(SCOPES_DIR)
        return when {
            location == filesDir(root).toPath().toAbsolutePath().normalize() -> WorkspaceDiskArea.FILES
            location == linuxDir(root).toPath().toAbsolutePath().normalize() -> WorkspaceDiskArea.ROOTFS
            location == tempDir(root).toPath().toAbsolutePath().normalize() -> WorkspaceDiskArea.TEMP
            location.startsWith(scopes) &&
                location.fileName.toString() in setOf(SCOPE_FILES_DIR, SCOPE_HOME_DIR) ->
                WorkspaceDiskArea.FILES
            location.startsWith(scopes) -> WorkspaceDiskArea.TEMP
            else -> WorkspaceDiskArea.OTHER
        }
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

    private fun areaDir(root: String, area: WorkspaceStorageArea, scope: WorkspaceScope): File {
        val directories = ensureScope(root, scope)
        return when (area) {
            WorkspaceStorageArea.FILES -> directories.files
            WorkspaceStorageArea.LINUX -> linuxDir(root)
            WorkspaceStorageArea.HOME -> directories.home
            WorkspaceStorageArea.TEMP -> directories.temp
            WorkspaceStorageArea.VAR_TEMP -> directories.varTemp
        }
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()
            ?.filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            ?: return
        for (dir in roots) {
            val root = dir.name
            if (root == RUNTIME_DIR || !root.matches(ROOT_NAME_REGEX)) continue
            withExclusiveAccess(root) {
                // PRoot temp files
                dir.deleteRelativeTreeNoFollow(TEMP_DIR)
                // Rootfs /tmp and /var/tmp
                dir.deleteRelativeTreeNoFollow("$LINUX_DIR/tmp")
                dir.deleteRelativeTreeNoFollow("$LINUX_DIR/var/tmp")
                File(dir, SCOPES_DIR).listFiles()
                    .orEmpty()
                    .filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
                    .forEach { scopeRoot ->
                        scopeRoot.deleteRelativeTreeNoFollow(SCOPE_TEMP_DIR)
                        scopeRoot.deleteRelativeTreeNoFollow(SCOPE_VAR_TEMP_DIR)
                        scopeRoot.deleteRelativeTreeNoFollow(SCOPE_PROOT_TEMP_DIR)
                    }
            }
        }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        private const val SCOPES_DIR = "scopes"
        private const val SCOPE_FILES_DIR = "files"
        private const val SCOPE_HOME_DIR = "home"
        private const val SCOPE_TEMP_DIR = "tmp"
        private const val SCOPE_VAR_TEMP_DIR = "var-tmp"
        private const val SCOPE_PROOT_TEMP_DIR = "proot-tmp"
        private const val ROOT_HOME_DIR = "root"
        private const val ROOTFS_TEMP_DIR = "tmp"
        private const val ROOTFS_VAR_TEMP_DIR = "var/tmp"
        private const val FILES_STAGING_DIR = "files-staging"
        private const val FILES_BACKUP_DIR = "files-backup"
        private const val FILES_COMMITTED_BACKUP_DIR = "files-backup-committed"
        private const val RUNTIME_DIR = ".runtime"
        private const val MAX_ROOTFS_TOOL_READ_BYTES = 8L * 1024 * 1024
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"
        val ROOTFS_WORKSPACE_PATH: GuestPath = GuestPath.parse(ROOTFS_WORKSPACE_DIR)

        val ROOTFS_HOME_PATH: GuestPath = GuestPath.parse("/root")
        val ROOTFS_TEMP_PATH: GuestPath = GuestPath.parse("/tmp")
        val ROOTFS_VAR_TEMP_PATH: GuestPath = GuestPath.parse("/var/tmp")
        private val SCOPED_PRIVATE_GUEST_ROOTS = listOf(
            ROOTFS_HOME_PATH to WorkspaceStorageArea.HOME,
            ROOTFS_TEMP_PATH to WorkspaceStorageArea.TEMP,
            ROOTFS_VAR_TEMP_PATH to WorkspaceStorageArea.VAR_TEMP,
        )

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
