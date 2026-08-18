package me.rerere.rikkahub.workspace.executor

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import me.rerere.workspace.WorkspaceExecutorProtocol
import me.rerere.workspace.WorkspaceProcessLauncher
import me.rerere.workspace.readResult
import java.io.File
import java.util.concurrent.locks.ReentrantLock

class WorkspaceExecutorService : Service() {
    private val executionLock = ReentrantLock()
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

    private fun Throwable.asBinderException(): Exception = when (this) {
        is Exception -> this
        else -> IllegalStateException(message ?: "Workspace executor failed", this)
    }
}
