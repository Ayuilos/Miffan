package me.rerere.workspace

import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.os.Parcel

data class WorkspaceExecutorIdentity(
    val protocolVersion: Int,
    val processUid: Int,
    val processId: Int,
    val packageName: String,
    val hasInternetPermission: Boolean,
)

object WorkspaceExecutorProtocol {
    const val VERSION = 1
    const val PACKAGE = "me.rerere.rikkahub.workspace.executor"
    const val SERVICE = "$PACKAGE.WorkspaceExecutorService"
    const val PERMISSION = "me.rerere.rikkahub.permission.BIND_WORKSPACE_EXECUTOR"
    const val DESCRIPTOR = "me.rerere.workspace.IWorkspaceExecutor.v1"

    const val TRANSACTION_IDENTITY = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_EXECUTE_PROBE = IBinder.FIRST_CALL_TRANSACTION + 1

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

    private const val MAX_COMMAND_BYTES = 16 * 1024
    private const val MAX_PROBE_TIMEOUT_MS = 30_000L
}
