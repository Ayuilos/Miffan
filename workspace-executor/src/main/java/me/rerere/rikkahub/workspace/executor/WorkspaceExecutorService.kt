package me.rerere.rikkahub.workspace.executor

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsArchiveSource
import me.rerere.workspace.RootfsCatalog
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.RootfsPatcher
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceExecutorProtocol
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceProcessLauncher
import me.rerere.workspace.readResult
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.LinkedHashSet
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.ConcurrentHashMap

class WorkspaceExecutorService : Service() {
    private val executionLock = ReentrantLock()
    private val runningExecutions = ConcurrentHashMap<String, Thread>()
    private val executionRegistrationLock = Any()
    private val cancelledExecutions = LinkedHashSet<String>()
    private val rootfsPatcher = RootfsPatcher()
    private val workspaceManager by lazy {
        WorkspaceManager(
            baseDir = File(filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                File(applicationInfo.nativeLibraryDir),
                rootfsPatcher,
            ),
            bindMounts = emptyList(),
        )
    }
    private val rootfsInstaller by lazy { RootfsInstaller(workspaceManager, rootfsPatcher) }
    private val endpoint = object : Binder() {
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(WorkspaceExecutorProtocol.DESCRIPTOR)
                return true
            }
            data.enforceInterface(WorkspaceExecutorProtocol.DESCRIPTOR)
            enforceTrustedCaller()
            val output = requireNotNull(reply) { "Synchronous Workspace executor reply is required" }
            return try {
                when (code) {
                    WorkspaceExecutorProtocol.TRANSACTION_IDENTITY -> {
                        output.writeNoException()
                        output.writeInt(WorkspaceExecutorProtocol.VERSION)
                        output.writeInt(Process.myUid())
                        output.writeInt(Process.myPid())
                        output.writeString(packageName)
                        output.writeInt(
                            if (checkSelfPermission(Manifest.permission.INTERNET) ==
                                PackageManager.PERMISSION_GRANTED
                            ) 1 else 0
                        )
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_EXECUTE_PROBE -> {
                        enforceDebugProbe()
                        val command = requireNotNull(data.readString())
                        val timeoutMillis = data.readLong()
                        val result = executeProbe(command, timeoutMillis)
                        output.writeNoException()
                        output.writeInt(result.exitCode)
                        output.writeString(result.stdout)
                        output.writeString(result.stderr)
                        output.writeInt(if (result.timedOut) 1 else 0)
                        output.writeInt(if (result.truncated) 1 else 0)
                        output.writeInt(if (result.resourceLimitExceeded) 1 else 0)
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_ENSURE_WORKSPACE -> {
                        workspaceManager.ensureWorkspace(requireNotNull(data.readString()))
                        output.writeNoException()
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_HAS_ROOTFS -> {
                        val root = requireNotNull(data.readString())
                        output.writeNoException()
                        output.writeInt(if (workspaceManager.hasRootfs(root)) 1 else 0)
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_INSTALL_ROOTFS -> {
                        val root = requireNotNull(data.readString())
                        val source = RootfsArchiveSource(
                            version = requireNotNull(data.readString()),
                            androidAbi = requireNotNull(data.readString()),
                            url = requireNotNull(data.readString()),
                            sha256 = requireNotNull(data.readString()),
                            format = RootfsInstaller.ArchiveFormat.valueOf(requireNotNull(data.readString())),
                        )
                        require(source == RootfsCatalog.forAndroidAbis(Build.SUPPORTED_ABIS.toList())) {
                            "Executor accepts only the pinned Rootfs artifact for this device ABI"
                        }
                        ParcelFileDescriptor.CREATOR.createFromParcel(data).use { archive ->
                            rootfsInstaller.installFromArchive(
                                root = root,
                                source = source,
                                inputStream = ParcelFileDescriptor.AutoCloseInputStream(
                                    ParcelFileDescriptor.dup(archive.fileDescriptor)
                                ),
                            )
                        }
                        output.writeNoException()
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_EXECUTE -> {
                        val executionId = requireNotNull(data.readString())
                        WorkspaceExecutorProtocol.requireValidExecutionId(executionId)
                        val root = requireNotNull(data.readString())
                        val command = requireNotNull(data.readString())
                        val cwd = requireNotNull(data.readString())
                        val timeoutMillis = data.readLong()
                        val stdin = data.createByteArray()
                        val executionThread = Thread.currentThread()
                        synchronized(executionRegistrationLock) {
                            if (cancelledExecutions.remove(executionId)) {
                                throw InterruptedException("Workspace execution was cancelled before start")
                            }
                            check(runningExecutions.putIfAbsent(executionId, executionThread) == null) {
                                "Duplicate workspace execution id"
                            }
                        }
                        try {
                            ParcelFileDescriptor.CREATOR.createFromParcel(data).use { snapshot ->
                                prepareWorkspaceRoot(root)
                                importSnapshot(root, snapshot)
                                val result = workspaceManager.executeCommand(
                                    root = root,
                                    command = command,
                                    cwd = cwd,
                                    timeoutMillis = timeoutMillis,
                                    stdin = stdin,
                                )
                                exportSnapshot(root, snapshot)
                                output.writeNoException()
                                output.writeCommandResult(result)
                            }
                        } finally {
                            runningExecutions.remove(executionId, executionThread)
                            Thread.interrupted()
                        }
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_CANCEL_EXECUTION -> {
                        val executionId = requireNotNull(data.readString())
                        WorkspaceExecutorProtocol.requireValidExecutionId(executionId)
                        val interrupted = synchronized(executionRegistrationLock) {
                            runningExecutions[executionId]?.let { thread ->
                                thread.interrupt()
                                true
                            } ?: run {
                                if (cancelledExecutions.size >= MAX_PENDING_CANCELLATIONS) {
                                    val oldest = cancelledExecutions.iterator()
                                    if (oldest.hasNext()) {
                                        oldest.next()
                                        oldest.remove()
                                    }
                                }
                                cancelledExecutions.add(executionId)
                                true
                            }
                        }
                        output.writeNoException()
                        output.writeInt(if (interrupted) 1 else 0)
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_CLEANUP_TEMP_DIRS -> {
                        workspaceManager.cleanupAllTempDirs()
                        output.writeNoException()
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_DELETE_WORKSPACE -> {
                        val deleted = workspaceManager.deleteWorkspace(requireNotNull(data.readString()))
                        output.writeNoException()
                        output.writeInt(if (deleted) 1 else 0)
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_LIST_FILES -> {
                        val root = requireNotNull(data.readString())
                        val path = requireNotNull(data.readString())
                        val area = me.rerere.workspace.WorkspaceStorageArea.valueOf(
                            requireNotNull(data.readString())
                        )
                        val entries = workspaceManager.listFiles(root, path, area)
                        require(entries.size <= WorkspaceExecutorProtocol.MAX_LIST_ENTRIES) {
                            "Executor file list exceeds broker entry limit"
                        }
                        output.writeNoException()
                        output.writeInt(entries.size)
                        entries.forEach { WorkspaceExecutorProtocol.writeFileEntry(output, it) }
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_FILE_SIZE -> {
                        val root = requireNotNull(data.readString())
                        val path = requireNotNull(data.readString())
                        val area = me.rerere.workspace.WorkspaceStorageArea.valueOf(
                            requireNotNull(data.readString())
                        )
                        output.writeNoException()
                        output.writeLong(workspaceManager.fileSize(root, path, area))
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_EXPORT_FILE -> {
                        val root = requireNotNull(data.readString())
                        val path = requireNotNull(data.readString())
                        val area = me.rerere.workspace.WorkspaceStorageArea.valueOf(
                            requireNotNull(data.readString())
                        )
                        ParcelFileDescriptor.CREATOR.createFromParcel(data).use { destination ->
                            prepareOutput(destination)
                            workspaceManager.exportFile(
                                root,
                                path,
                                area,
                                ParcelFileDescriptor.AutoCloseOutputStream(
                                    ParcelFileDescriptor.dup(destination.fileDescriptor)
                                ),
                                workspaceManager.resourceLimits.maxShellFileBytes,
                            )
                        }
                        output.writeNoException()
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_IMPORT_FILE -> {
                        val root = requireNotNull(data.readString())
                        val destinationPath = requireNotNull(data.readString())
                        val area = me.rerere.workspace.WorkspaceStorageArea.valueOf(
                            requireNotNull(data.readString())
                        )
                        val fileName = requireNotNull(data.readString())
                        val entry = ParcelFileDescriptor.CREATOR.createFromParcel(data).use { source ->
                            Os.lseek(source.fileDescriptor, 0, OsConstants.SEEK_SET)
                            workspaceManager.importFile(
                                root,
                                destinationPath,
                                area,
                                fileName,
                                ParcelFileDescriptor.AutoCloseInputStream(
                                    ParcelFileDescriptor.dup(source.fileDescriptor)
                                ),
                            )
                        }
                        output.writeNoException()
                        WorkspaceExecutorProtocol.writeFileEntry(output, entry)
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_DELETE_FILE -> {
                        val root = requireNotNull(data.readString())
                        val path = requireNotNull(data.readString())
                        val recursive = data.readInt() != 0
                        val area = me.rerere.workspace.WorkspaceStorageArea.valueOf(
                            requireNotNull(data.readString())
                        )
                        val deleted = workspaceManager.deleteFile(root, path, recursive, area)
                        output.writeNoException()
                        output.writeInt(if (deleted) 1 else 0)
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_ROOTFS_FILE_SIZE -> {
                        val root = requireNotNull(data.readString())
                        val path = requireNotNull(data.readString())
                        output.writeNoException()
                        output.writeLong(workspaceManager.rootfsFileSize(root, path))
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_EXPORT_ROOTFS_FILE -> {
                        val root = requireNotNull(data.readString())
                        val path = requireNotNull(data.readString())
                        ParcelFileDescriptor.CREATOR.createFromParcel(data).use { destination ->
                            prepareOutput(destination)
                            workspaceManager.exportRootfsFile(
                                root,
                                path,
                                ParcelFileDescriptor.AutoCloseOutputStream(
                                    ParcelFileDescriptor.dup(destination.fileDescriptor)
                                ),
                            )
                        }
                        output.writeNoException()
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_WRITE_ROOTFS_TEXT -> {
                        val root = requireNotNull(data.readString())
                        val path = requireNotNull(data.readString())
                        val overwrite = data.readInt() != 0
                        val text = ParcelFileDescriptor.CREATOR.createFromParcel(data).use { source ->
                            Os.lseek(source.fileDescriptor, 0, OsConstants.SEEK_SET)
                            ParcelFileDescriptor.AutoCloseInputStream(
                                ParcelFileDescriptor.dup(source.fileDescriptor)
                            ).use { it.readBoundedUtf8(MAX_ROOTFS_TEXT_BYTES) }
                        }
                        val entry = workspaceManager.writeRootfsText(root, path, text, overwrite)
                        output.writeNoException()
                        WorkspaceExecutorProtocol.writeFileEntry(output, entry)
                        true
                    }

                    WorkspaceExecutorProtocol.TRANSACTION_DEBUG_SNAPSHOT_ROUND_TRIP -> {
                        enforceDebugProbe()
                        val root = requireNotNull(data.readString())
                        ParcelFileDescriptor.CREATOR.createFromParcel(data).use { snapshot ->
                            importSnapshot(root, snapshot)
                            workspaceManager.writeText(
                                root,
                                "executor/identity.txt",
                                "uid=${Process.myUid()};pid=${Process.myPid()}",
                            )
                            exportSnapshot(root, snapshot)
                        }
                        output.writeNoException()
                        true
                    }

                    else -> super.onTransact(code, data, output, flags)
                }
            } catch (error: Throwable) {
                output.writeException(error.asBinderException())
                true
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = endpoint

    private fun enforceTrustedCaller() {
        val callerUid = Binder.getCallingUid()
        if (packageManager.checkSignatures(callerUid, Process.myUid()) !=
            PackageManager.SIGNATURE_MATCH
        ) {
            throw SecurityException("Workspace executor rejected caller UID $callerUid")
        }
    }

    private fun enforceDebugProbe() {
        check(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "Workspace executor probe transactions are unavailable in release builds"
        }
    }

    private fun executeProbe(command: String, timeoutMillis: Long) = executionLock.run {
        require(command.isNotBlank()) { "Executor probe command is required" }
        require(command.toByteArray(Charsets.UTF_8).size <= 16 * 1024) {
            "Executor probe command is too large"
        }
        require(timeoutMillis in 1..30_000) { "Invalid executor probe timeout" }
        lockInterruptibly()
        try {
            val workingDirectory = File(filesDir, "probe").apply { mkdirs() }
            val process = WorkspaceProcessLauncher.start(
                command = listOf("/system/bin/sh", "-c", command),
                environment = mapOf(
                    "HOME" to workingDirectory.absolutePath,
                    "PATH" to "/system/bin",
                    "TMPDIR" to cacheDir.absolutePath,
                ),
                workingDirectory = workingDirectory,
            )
            process.readResult(timeoutMillis)
        } finally {
            unlock()
        }
    }

    private fun importSnapshot(root: String, descriptor: ParcelFileDescriptor) {
        Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
        workspaceManager.replaceFilesSnapshot(
            root,
            ParcelFileDescriptor.AutoCloseInputStream(
                ParcelFileDescriptor.dup(descriptor.fileDescriptor)
            ),
        )
    }

    private fun prepareWorkspaceRoot(root: String) {
        workspaceManager.ensureWorkspace(root)
        if (workspaceManager.hasRootfs(root)) {
            rootfsPatcher.repairExecutionMountPoints(workspaceManager.linuxDir(root))
        }
    }

    private fun exportSnapshot(root: String, descriptor: ParcelFileDescriptor) {
        prepareOutput(descriptor)
        workspaceManager.exportFilesSnapshot(
            root,
            ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.dup(descriptor.fileDescriptor)
            ),
        )
    }

    private fun prepareOutput(descriptor: ParcelFileDescriptor) {
        Os.ftruncate(descriptor.fileDescriptor, 0)
        Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
    }

    private fun InputStream.readBoundedUtf8(maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total = Math.addExact(total, read)
            require(total <= maxBytes) { "Rootfs text write exceeds $maxBytes bytes" }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun Parcel.writeCommandResult(result: WorkspaceCommandResult) {
        writeInt(result.exitCode)
        writeString(result.stdout)
        writeString(result.stderr)
        writeInt(if (result.timedOut) 1 else 0)
        writeInt(if (result.truncated) 1 else 0)
        writeInt(if (result.resourceLimitExceeded) 1 else 0)
    }

    private fun Throwable.asBinderException(): Exception = when (this) {
        is SecurityException -> this
        is IllegalArgumentException -> this
        is NullPointerException -> this
        is IllegalStateException -> this
        is UnsupportedOperationException -> this
        else -> IllegalStateException(message ?: "Workspace executor failed", this)
    }

    private companion object {
        private const val MAX_ROOTFS_TEXT_BYTES = 2 * 1024 * 1024
        private const val MAX_PENDING_CANCELLATIONS = 128
    }
}
