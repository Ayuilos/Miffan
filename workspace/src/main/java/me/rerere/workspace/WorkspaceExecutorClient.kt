package me.rerere.workspace

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/** Main-UID side of the descriptor-only workspace execution broker. */
class WorkspaceExecutorClient(
    context: Context,
    private val localManager: WorkspaceManager,
) : AutoCloseable {
    /** Remote PTY transport is not part of protocol v2; never fall back to the primary app UID. */
    val supportsInteractivePty: Boolean
        get() = false

    private val appContext = context.applicationContext
    private val connectionLock = Any()

    @Volatile
    private var connection: ActiveConnection? = null

    fun identity(): WorkspaceExecutorIdentity = WorkspaceExecutorProtocol.identity(remote())

    fun ensureWorkspace(root: String) = WorkspaceExecutorProtocol.ensureWorkspace(remote(), root)

    fun hasRootfs(root: String): Boolean = WorkspaceExecutorProtocol.hasRootfs(remote(), root)

    fun installRootfs(root: String, source: RootfsArchiveSource, archive: File) {
        require(archive.isFile) { "Verified Rootfs archive is unavailable" }
        ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            WorkspaceExecutorProtocol.installRootfs(remote(), root, source, descriptor)
        }
    }

    fun downloadAndInstallRootfs(
        root: String,
        source: RootfsArchiveSource,
        downloader: RootfsInstaller,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) {
        val brokerDir = File(appContext.cacheDir, BROKER_CACHE_DIR).apply { mkdirs() }
        require(brokerDir.isDirectory) { "Workspace broker cache is unavailable" }
        val maximumArchiveBytes = RootfsInstallLimits().maxDownloadBytes
        require(brokerDir.usableSpace >= maximumArchiveBytes + localManager.resourceLimits.minFreeSpaceBytes) {
            "Insufficient free space for brokered Rootfs download"
        }
        val archive = File.createTempFile("rootfs-", ".${source.format.extension}", brokerDir)
        check(archive.delete()) { "Unable to prepare exclusive Rootfs download target" }
        try {
            downloader.downloadVerifiedArchive(source, archive, onProgress)
            installRootfs(root, source, archive)
            onProgress(RootfsInstallProgress(stage = RootfsInstallStage.INSTALLED))
        } finally {
            archive.delete()
        }
    }

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult = localManager.withFilesSnapshotExchange(root) { encodedSize, export, replace ->
        val brokerDir = File(appContext.cacheDir, BROKER_CACHE_DIR).apply { mkdirs() }
        require(brokerDir.isDirectory) { "Workspace broker cache is unavailable" }
        val snapshot = File.createTempFile("workspace-", ".snapshot", brokerDir)
        try {
            require(brokerDir.usableSpace - encodedSize >= localManager.resourceLimits.minFreeSpaceBytes) {
                "Insufficient free space for brokered workspace snapshot"
            }
            FileOutputStream(snapshot).use(export)
            check(snapshot.length() == encodedSize) { "Workspace changed while its broker snapshot was exported" }
            val result = ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                executeCancellable(descriptor, root, command, cwd, timeoutMillis, stdin)
            }
            FileInputStream(snapshot).use(replace)
            result
        } finally {
            snapshot.delete()
        }
    }

    private fun executeCancellable(
        snapshot: ParcelFileDescriptor,
        root: String,
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
    ): WorkspaceCommandResult {
        val executionId = UUID.randomUUID().toString()
        val binder = remote()
        val task = FutureTask {
            WorkspaceExecutorProtocol.execute(
                remote = binder,
                executionId = executionId,
                root = root,
                command = command,
                cwd = cwd,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                snapshot = snapshot,
            )
        }
        Thread(task, "WorkspaceExecutorCall").apply { isDaemon = true }.start()
        try {
            return task.get()
        } catch (interrupted: InterruptedException) {
            val cancellation = runCatching {
                WorkspaceExecutorProtocol.cancelExecution(binder, executionId)
            }
            try {
                task.get(CANCEL_WAIT_SECONDS, TimeUnit.SECONDS)
            } catch (_: ExecutionException) {
                // The interrupted remote execution is expected to surface as a Binder failure.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (timeout: TimeoutException) {
                interrupted.addSuppressed(
                    IllegalStateException("Workspace executor did not stop after cancellation", timeout)
                )
            }
            cancellation.exceptionOrNull()?.let(interrupted::addSuppressed)
            throw interrupted
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    fun cleanupTempDirs() = WorkspaceExecutorProtocol.cleanupTempDirs(remote())

    fun deleteWorkspace(root: String): Boolean =
        WorkspaceExecutorProtocol.deleteWorkspace(remote(), root)

    fun listFiles(
        root: String,
        path: String,
        area: WorkspaceStorageArea,
    ): List<WorkspaceFileEntry> = WorkspaceExecutorProtocol.listFiles(remote(), root, path, area)

    fun fileSize(root: String, path: String, area: WorkspaceStorageArea): Long =
        WorkspaceExecutorProtocol.fileSize(remote(), root, path, area)

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea,
        outputStream: OutputStream,
    ) {
        val size = fileSize(root, path, area)
        require(size <= localManager.resourceLimits.maxShellFileBytes) {
            "Executor file exceeds broker transfer limit"
        }
        withOutputTransfer(outputStream, localManager.resourceLimits.maxShellFileBytes) { descriptor ->
            WorkspaceExecutorProtocol.exportFile(remote(), root, path, area, descriptor)
        }
    }

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withInputTransfer(inputStream, localManager.resourceLimits.maxShellFileBytes) {
        WorkspaceExecutorProtocol.importFile(
            remote(), root, destinationPath, area, fileName, it,
        )
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean,
        area: WorkspaceStorageArea,
    ): Boolean = WorkspaceExecutorProtocol.deleteFile(remote(), root, path, recursive, area)

    fun rootfsFileSize(root: String, path: String): Long =
        WorkspaceExecutorProtocol.rootfsFileSize(remote(), root, path)

    fun exportRootfsFile(root: String, path: String, outputStream: OutputStream) =
        withOutputTransfer(outputStream, MAX_ROOTFS_EXPORT_BYTES) {
            WorkspaceExecutorProtocol.exportRootfsFile(remote(), root, path, it)
        }

    fun writeRootfsText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return withInputTransfer(bytes.inputStream(), MAX_ROOTFS_TEXT_BYTES) {
            WorkspaceExecutorProtocol.writeRootfsText(remote(), root, path, overwrite, it)
        }
    }

    override fun close() {
        synchronized(connectionLock) {
            connection?.let { runCatching { appContext.unbindService(it) } }
            connection = null
        }
    }

    private fun remote(): IBinder {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Workspace executor IPC must not run on the main thread"
        }
        connection?.remote?.takeIf { it.isBinderAlive }?.let { return it }
        synchronized(connectionLock) {
            connection?.remote?.takeIf { it.isBinderAlive }?.let { return it }
            val remoteRef = AtomicReference<IBinder>()
            val failure = AtomicReference<Throwable>()
            val connected = CountDownLatch(1)
            val newConnection = ActiveConnection(
                onConnected = { binder ->
                    remoteRef.set(binder)
                    connected.countDown()
                },
                onFailure = { error ->
                    failure.set(error)
                    connected.countDown()
                },
                onDied = { connection = null },
            )
            val bound = appContext.bindService(
                WorkspaceExecutorProtocol.bindIntent(),
                newConnection,
                Context.BIND_AUTO_CREATE,
            )
            check(bound) { "RikkaHub Workspace Executor companion is not installed" }
            check(connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                runCatching { appContext.unbindService(newConnection) }
                "Workspace executor bind timed out"
            }
            failure.get()?.let { throw it }
            val binder = requireNotNull(remoteRef.get()) { "Workspace executor returned no Binder" }
            connection = newConnection.apply { remote = binder }
            return binder
        }
    }

    private fun <T> withInputTransfer(
        inputStream: InputStream,
        maxBytes: Long,
        block: (ParcelFileDescriptor) -> T,
    ): T {
        val transfer = createTransferFile()
        try {
            inputStream.use { input ->
                FileOutputStream(transfer).use { output -> input.copyBoundedTo(output, maxBytes) }
            }
            return ParcelFileDescriptor.open(transfer, ParcelFileDescriptor.MODE_READ_ONLY).use(block)
        } finally {
            transfer.delete()
        }
    }

    private fun withOutputTransfer(
        outputStream: OutputStream,
        maxBytes: Long,
        block: (ParcelFileDescriptor) -> Unit,
    ) {
        val transfer = createTransferFile()
        try {
            ParcelFileDescriptor.open(transfer, ParcelFileDescriptor.MODE_READ_WRITE).use(block)
            require(transfer.length() <= maxBytes) {
                "Executor export exceeds broker transfer limit"
            }
            outputStream.use { output -> FileInputStream(transfer).use { it.copyTo(output) } }
        } finally {
            transfer.delete()
        }
    }

    private fun createTransferFile(): File {
        val brokerDir = File(appContext.cacheDir, BROKER_CACHE_DIR).apply { mkdirs() }
        require(brokerDir.isDirectory) { "Workspace broker cache is unavailable" }
        require(brokerDir.usableSpace >=
            localManager.resourceLimits.maxShellFileBytes + localManager.resourceLimits.minFreeSpaceBytes
        ) { "Insufficient free space for workspace broker transfer" }
        return File.createTempFile("transfer-", ".bin", brokerDir)
    }

    private fun InputStream.copyBoundedTo(output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Workspace broker transfer cancelled")
            }
            val read = read(buffer)
            if (read < 0) return
            if (read == 0) continue
            copied = Math.addExact(copied, read.toLong())
            require(copied <= maxBytes) { "Workspace broker transfer exceeds $maxBytes bytes" }
            output.write(buffer, 0, read)
        }
    }

    private class ActiveConnection(
        private val onConnected: (IBinder) -> Unit,
        private val onFailure: (Throwable) -> Unit,
        private val onDied: () -> Unit,
    ) : ServiceConnection {
        @Volatile
        var remote: IBinder? = null

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) onFailure(IllegalStateException("Workspace executor returned null"))
            else onConnected(service)
        }

        override fun onNullBinding(name: ComponentName?) =
            onFailure(IllegalStateException("Workspace executor returned a null binding"))

        override fun onServiceDisconnected(name: ComponentName?) = onDied()
        override fun onBindingDied(name: ComponentName?) = onDied()
    }

    private companion object {
        private const val BIND_TIMEOUT_SECONDS = 5L
        private const val CANCEL_WAIT_SECONDS = 10L
        private const val BROKER_CACHE_DIR = "workspace-broker"
        private const val MAX_ROOTFS_TEXT_BYTES = 2L * 1024 * 1024
        private const val MAX_ROOTFS_EXPORT_BYTES = 8L * 1024 * 1024
    }
}
