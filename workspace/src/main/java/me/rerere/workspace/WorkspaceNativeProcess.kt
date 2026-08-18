package me.rerere.workspace

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A native process-group launcher whose monitor remains a direct child of the Android app.
 *
 * The monitor arms a parent-death signal before it publishes its PID, owns a dedicated session,
 * and acts as a child subreaper so even descendants that leave the command group are removed. This
 * closes the ProcessBuilder-to-record crash window for AI shell commands while preserving the
 * standard [Process] stream contract.
 */
internal class WorkspaceNativeProcess private constructor(
    private val monitorProcessId: Int,
    private val commandProcessId: Int,
    stdinFd: Int,
    stdoutFd: Int,
    stderrFd: Int,
) : Process() {
    private val processInput = ParcelFileDescriptor.AutoCloseOutputStream(
        ParcelFileDescriptor.adoptFd(stdinFd)
    )
    private val processOutput = ParcelFileDescriptor.AutoCloseInputStream(
        ParcelFileDescriptor.adoptFd(stdoutFd)
    )
    private val processError = ParcelFileDescriptor.AutoCloseInputStream(
        ParcelFileDescriptor.adoptFd(stderrFd)
    )
    private val waitLock = Any()

    @Volatile
    private var observedExitCode: Int? = null

    override fun getOutputStream(): OutputStream = processInput

    override fun getInputStream(): InputStream = processOutput

    override fun getErrorStream(): InputStream = processError

    override fun waitFor(): Int {
        while (true) {
            observeExit()?.let { return it }
            Thread.sleep(WAIT_POLL_MILLIS)
        }
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        require(timeout >= 0) { "Timeout must not be negative" }
        val timeoutNanos = unit.toNanos(timeout)
        val startedAt = System.nanoTime()
        while (true) {
            if (observeExit() != null) return true
            val remaining = timeoutNanos - (System.nanoTime() - startedAt)
            if (remaining <= 0) return false
            Thread.sleep(
                minOf(
                    WAIT_POLL_MILLIS,
                    TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1),
                )
            )
        }
    }

    override fun exitValue(): Int =
        observeExit() ?: throw IllegalThreadStateException("Workspace process is still running")

    override fun isAlive(): Boolean = observeExit() == null

    override fun destroy() {
        WorkspaceNativeBridge.signalProcessGroup(commandProcessId, SIGTERM)
    }

    override fun destroyForcibly(): Process {
        WorkspaceNativeBridge.signalProcessGroup(commandProcessId, SIGKILL)
        return this
    }

    fun nativePid(): Long = commandProcessId.toLong()

    fun nativeMonitorPid(): Long = monitorProcessId.toLong()

    private fun observeExit(): Int? = synchronized(waitLock) {
        observedExitCode?.let { return@synchronized it }
        val result = WorkspaceNativeBridge.waitForProcess(monitorProcessId)
        if (result == STILL_RUNNING) return@synchronized null
        runCatching { processInput.close() }
        observedExitCode = result
        result
    }

    companion object {
        const val PROCESS_NAME = "rk-ws-launcher"

        fun start(
            command: List<String>,
            environment: Map<String, String>,
            workingDirectory: File,
        ): WorkspaceNativeProcess {
            require(command.isNotEmpty() && command.first().isNotEmpty()) {
                "Workspace process command is required"
            }
            require(workingDirectory.isDirectory) {
                "Workspace process working directory is unavailable"
            }
            command.forEach { value -> requireValidValue(value, "Command argument") }
            val environmentEntries = environment.entries
                .sortedBy { it.key }
                .map { (name, value) ->
                    require(name.isNotEmpty() && '=' !in name && '\u0000' !in name) {
                        "Invalid workspace process environment name"
                    }
                    requireValidValue(value, "Environment value")
                    "$name=$value"
                }
            val result = WorkspaceNativeLaunchThread.submit {
                WorkspaceNativeBridge.spawn(
                    command.map(::encodeUtf8).toTypedArray(),
                    environmentEntries.map(::encodeUtf8).toTypedArray(),
                    encodeUtf8(workingDirectory.absolutePath),
                )
            }
            check(result.size == SPAWN_RESULT_SIZE) { "Invalid native process result" }
            return WorkspaceNativeProcess(
                monitorProcessId = result[0],
                commandProcessId = result[1],
                stdinFd = result[2],
                stdoutFd = result[3],
                stderrFd = result[4],
            )
        }

        private fun requireValidValue(value: String, label: String) {
            require('\u0000' !in value) { "$label contains NUL" }
            require(value.toByteArray(Charsets.UTF_8).size <= MAX_ENTRY_BYTES) {
                "$label is too large"
            }
        }

        private fun encodeUtf8(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

        private const val SPAWN_RESULT_SIZE = 5
        private const val STILL_RUNNING = Int.MIN_VALUE
        private const val MAX_ENTRY_BYTES = 1024 * 1024
        private const val SIGTERM = 15
        private const val SIGKILL = 9
        private const val WAIT_POLL_MILLIS = 10L
    }
}

/**
 * Linux ties PR_SET_PDEATHSIG to the thread that called fork(). Dispatchers.IO workers may retire,
 * so every native launch is serialized through one daemon thread that lives with the app process.
 */
private object WorkspaceNativeLaunchThread {
    private val queue = LinkedBlockingQueue<FutureTask<*>>()

    init {
        Thread(
            {
                while (true) {
                    try {
                        queue.take().run()
                    } catch (_: InterruptedException) {
                        // The launcher thread is process-scoped; an interrupt must not orphan its
                        // already-started native children by terminating their registered parent.
                    }
                }
            },
            "WorkspaceNativeLauncher",
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun <T> submit(block: () -> T): T {
        val task = FutureTask(Callable(block))
        queue.put(task)
        var interrupted = false
        try {
            while (true) {
                try {
                    return task.get()
                } catch (_: InterruptedException) {
                    // Do not lose a successfully forked process between JNI and registration.
                    interrupted = true
                } catch (error: ExecutionException) {
                    throw error.cause ?: error
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
