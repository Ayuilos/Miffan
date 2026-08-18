package me.rerere.workspace

import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor

data class WorkspaceExecutorIdentity(
    val protocolVersion: Int,
    val processUid: Int,
    val processId: Int,
    val packageName: String,
    val hasInternetPermission: Boolean,
)

object WorkspaceExecutorProtocol {
    const val VERSION = 2
    const val PACKAGE = "me.rerere.rikkahub.workspace.executor"
    const val SERVICE = "$PACKAGE.WorkspaceExecutorService"
    const val PERMISSION = "me.rerere.rikkahub.permission.BIND_WORKSPACE_EXECUTOR"
    const val DESCRIPTOR = "me.rerere.workspace.IWorkspaceExecutor.v1"

    const val TRANSACTION_IDENTITY = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_EXECUTE_PROBE = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_ENSURE_WORKSPACE = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TRANSACTION_HAS_ROOTFS = IBinder.FIRST_CALL_TRANSACTION + 3
    const val TRANSACTION_INSTALL_ROOTFS = IBinder.FIRST_CALL_TRANSACTION + 4
    const val TRANSACTION_EXECUTE = IBinder.FIRST_CALL_TRANSACTION + 5
    const val TRANSACTION_DELETE_WORKSPACE = IBinder.FIRST_CALL_TRANSACTION + 6
    const val TRANSACTION_LIST_FILES = IBinder.FIRST_CALL_TRANSACTION + 7
    const val TRANSACTION_FILE_SIZE = IBinder.FIRST_CALL_TRANSACTION + 8
    const val TRANSACTION_EXPORT_FILE = IBinder.FIRST_CALL_TRANSACTION + 9
    const val TRANSACTION_IMPORT_FILE = IBinder.FIRST_CALL_TRANSACTION + 10
    const val TRANSACTION_DELETE_FILE = IBinder.FIRST_CALL_TRANSACTION + 11
    const val TRANSACTION_ROOTFS_FILE_SIZE = IBinder.FIRST_CALL_TRANSACTION + 12
    const val TRANSACTION_EXPORT_ROOTFS_FILE = IBinder.FIRST_CALL_TRANSACTION + 13
    const val TRANSACTION_WRITE_ROOTFS_TEXT = IBinder.FIRST_CALL_TRANSACTION + 14
    const val TRANSACTION_DEBUG_SNAPSHOT_ROUND_TRIP = IBinder.FIRST_CALL_TRANSACTION + 15
    const val TRANSACTION_CANCEL_EXECUTION = IBinder.FIRST_CALL_TRANSACTION + 16
    const val TRANSACTION_CLEANUP_TEMP_DIRS = IBinder.FIRST_CALL_TRANSACTION + 17

    fun bindIntent(): Intent = Intent().setComponent(ComponentName(PACKAGE, SERVICE))

    fun identity(remote: IBinder): WorkspaceExecutorIdentity = transact(
        remote = remote,
        code = TRANSACTION_IDENTITY,
        writeRequest = {},
        readResponse = { response ->
            WorkspaceExecutorIdentity(
                protocolVersion = response.readInt(),
                processUid = response.readInt(),
                processId = response.readInt(),
                packageName = requireNotNull(response.readString()),
                hasInternetPermission = response.readInt() != 0,
            )
        },
    )

    fun executeProbe(
        remote: IBinder,
        command: String,
        timeoutMillis: Long = 10_000,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Executor probe command is required" }
        require(command.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES) {
            "Executor probe command is too large"
        }
        require(timeoutMillis in 1..MAX_PROBE_TIMEOUT_MS) { "Invalid executor probe timeout" }
        return transact(
            remote = remote,
            code = TRANSACTION_EXECUTE_PROBE,
            writeRequest = { request ->
                request.writeString(command)
                request.writeLong(timeoutMillis)
            },
            readResponse = { response ->
                WorkspaceCommandResult(
                    exitCode = response.readInt(),
                    stdout = requireNotNull(response.readString()),
                    stderr = requireNotNull(response.readString()),
                    timedOut = response.readInt() != 0,
                    truncated = response.readInt() != 0,
                    resourceLimitExceeded = response.readInt() != 0,
                )
            },
        )
    }

    fun ensureWorkspace(remote: IBinder, root: String) {
        transact(remote, TRANSACTION_ENSURE_WORKSPACE, { it.writeRoot(root) }, {})
    }

    fun hasRootfs(remote: IBinder, root: String): Boolean = transact(
        remote,
        TRANSACTION_HAS_ROOTFS,
        { it.writeRoot(root) },
        { it.readInt() != 0 },
    )

    fun installRootfs(
        remote: IBinder,
        root: String,
        source: RootfsArchiveSource,
        archive: ParcelFileDescriptor,
    ) {
        transact(remote, TRANSACTION_INSTALL_ROOTFS, { request ->
            request.writeRoot(root)
            request.writeString(source.version)
            request.writeString(source.androidAbi)
            request.writeString(source.url)
            request.writeString(source.sha256)
            request.writeString(source.format.name)
            archive.writeToParcel(request, 0)
        }, {})
    }

    fun execute(
        remote: IBinder,
        executionId: String,
        root: String,
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
        snapshot: ParcelFileDescriptor,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Executor command is required" }
        require(command.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES) {
            "Executor command is too large"
        }
        require(cwd.toByteArray(Charsets.UTF_8).size <= MAX_CWD_BYTES) {
            "Executor working directory is too large"
        }
        require(timeoutMillis in 1..MAX_EXECUTION_TIMEOUT_MS) { "Invalid executor timeout" }
        require(stdin == null || stdin.size <= MAX_STDIN_BYTES) { "Executor stdin is too large" }
        return transact(remote, TRANSACTION_EXECUTE, { request ->
            requireValidExecutionId(executionId)
            request.writeString(executionId)
            request.writeRoot(root)
            request.writeString(command)
            request.writeString(cwd)
            request.writeLong(timeoutMillis)
            request.writeByteArray(stdin)
            snapshot.writeToParcel(request, 0)
        }, ::readCommandResult)
    }

    fun cancelExecution(remote: IBinder, executionId: String): Boolean = transact(
        remote,
        TRANSACTION_CANCEL_EXECUTION,
        { request ->
            requireValidExecutionId(executionId)
            request.writeString(executionId)
        },
        { it.readInt() != 0 },
    )

    fun cleanupTempDirs(remote: IBinder) {
        transact(remote, TRANSACTION_CLEANUP_TEMP_DIRS, {}, {})
    }

    fun deleteWorkspace(remote: IBinder, root: String): Boolean = transact(
        remote,
        TRANSACTION_DELETE_WORKSPACE,
        { it.writeRoot(root) },
        { it.readInt() != 0 },
    )

    fun listFiles(
        remote: IBinder,
        root: String,
        path: String,
        area: WorkspaceStorageArea,
    ): List<WorkspaceFileEntry> = transact(remote, TRANSACTION_LIST_FILES, { request ->
        request.writeRoot(root)
        request.writeString(path)
        request.writeString(area.name)
    }, { response ->
        val count = response.readInt()
        require(count in 0..MAX_LIST_ENTRIES) { "Invalid executor file-list size: $count" }
        List(count) { response.readFileEntry() }
    })

    fun fileSize(
        remote: IBinder,
        root: String,
        path: String,
        area: WorkspaceStorageArea,
    ): Long = transact(remote, TRANSACTION_FILE_SIZE, { request ->
        request.writeRoot(root)
        request.writeString(path)
        request.writeString(area.name)
    }, Parcel::readLong)

    fun exportFile(
        remote: IBinder,
        root: String,
        path: String,
        area: WorkspaceStorageArea,
        output: ParcelFileDescriptor,
    ) {
        transact(remote, TRANSACTION_EXPORT_FILE, { request ->
            request.writeRoot(root)
            request.writeString(path)
            request.writeString(area.name)
            output.writeToParcel(request, 0)
        }, {})
    }

    fun importFile(
        remote: IBinder,
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea,
        fileName: String,
        input: ParcelFileDescriptor,
    ): WorkspaceFileEntry = transact(remote, TRANSACTION_IMPORT_FILE, { request ->
        request.writeRoot(root)
        request.writeString(destinationPath)
        request.writeString(area.name)
        request.writeString(fileName)
        input.writeToParcel(request, 0)
    }, { it.readFileEntry() })

    fun deleteFile(
        remote: IBinder,
        root: String,
        path: String,
        recursive: Boolean,
        area: WorkspaceStorageArea,
    ): Boolean = transact(remote, TRANSACTION_DELETE_FILE, { request ->
        request.writeRoot(root)
        request.writeString(path)
        request.writeInt(if (recursive) 1 else 0)
        request.writeString(area.name)
    }, { it.readInt() != 0 })

    fun rootfsFileSize(remote: IBinder, root: String, path: String): Long = transact(
        remote,
        TRANSACTION_ROOTFS_FILE_SIZE,
        { request -> request.writeRoot(root); request.writeString(path) },
        Parcel::readLong,
    )

    fun exportRootfsFile(
        remote: IBinder,
        root: String,
        path: String,
        output: ParcelFileDescriptor,
    ) {
        transact(remote, TRANSACTION_EXPORT_ROOTFS_FILE, { request ->
            request.writeRoot(root)
            request.writeString(path)
            output.writeToParcel(request, 0)
        }, {})
    }

    fun writeRootfsText(
        remote: IBinder,
        root: String,
        path: String,
        overwrite: Boolean,
        input: ParcelFileDescriptor,
    ): WorkspaceFileEntry = transact(remote, TRANSACTION_WRITE_ROOTFS_TEXT, { request ->
        request.writeRoot(root)
        request.writeString(path)
        request.writeInt(if (overwrite) 1 else 0)
        input.writeToParcel(request, 0)
    }, { it.readFileEntry() })

    fun debugSnapshotRoundTrip(
        remote: IBinder,
        root: String,
        snapshot: ParcelFileDescriptor,
    ) {
        transact(remote, TRANSACTION_DEBUG_SNAPSHOT_ROUND_TRIP, { request ->
            request.writeRoot(root)
            snapshot.writeToParcel(request, 0)
        }, {})
    }

    private fun <T> transact(
        remote: IBinder,
        code: Int,
        writeRequest: (Parcel) -> Unit,
        readResponse: (Parcel) -> T,
    ): T {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(DESCRIPTOR)
            writeRequest(request)
            check(remote.transact(code, request, response, 0)) {
                "Workspace executor rejected Binder transaction $code"
            }
            response.readException()
            return readResponse(response)
        } finally {
            response.recycle()
            request.recycle()
        }
    }

    private fun Parcel.writeRoot(root: String) {
        require(root.toByteArray(Charsets.UTF_8).size <= MAX_ROOT_BYTES) {
            "Workspace root is too large"
        }
        writeString(root)
    }

    private fun readCommandResult(response: Parcel): WorkspaceCommandResult = WorkspaceCommandResult(
        exitCode = response.readInt(),
        stdout = requireNotNull(response.readString()),
        stderr = requireNotNull(response.readString()),
        timedOut = response.readInt() != 0,
        truncated = response.readInt() != 0,
        resourceLimitExceeded = response.readInt() != 0,
    )

    fun writeFileEntry(parcel: Parcel, entry: WorkspaceFileEntry) {
        parcel.writeString(entry.path)
        parcel.writeString(entry.name)
        parcel.writeInt(if (entry.isDirectory) 1 else 0)
        parcel.writeLong(entry.sizeBytes)
        parcel.writeLong(entry.updatedAt)
    }

    fun requireValidExecutionId(executionId: String) {
        require(executionId.matches(EXECUTION_ID_REGEX)) { "Invalid executor execution id" }
    }

    private fun Parcel.readFileEntry(): WorkspaceFileEntry = WorkspaceFileEntry(
        path = requireNotNull(readString()),
        name = requireNotNull(readString()),
        isDirectory = readInt() != 0,
        sizeBytes = readLong(),
        updatedAt = readLong(),
    )

    private const val MAX_COMMAND_BYTES = 16 * 1024
    private const val MAX_PROBE_TIMEOUT_MS = 30_000L
    private const val MAX_ROOT_BYTES = 128
    private const val MAX_CWD_BYTES = 4096
    private const val MAX_STDIN_BYTES = 256 * 1024
    private const val MAX_EXECUTION_TIMEOUT_MS = 10 * 60_000L
    const val MAX_LIST_ENTRIES = 500
    private val EXECUTION_ID_REGEX =
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}
