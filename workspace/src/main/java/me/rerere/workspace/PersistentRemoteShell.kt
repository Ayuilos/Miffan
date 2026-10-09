package me.rerere.workspace

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runInterruptible

/** One POSIX shell and one PTY for all commands. No per-command process or subshell wrapper. */
class PersistentRemoteShell(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeTransport: () -> Unit,
    private val onClosed: () -> Unit = {},
) : Closeable {
    private class Invocation(val onOutput: suspend (ByteArray) -> Unit) {
        val protocol = PersistentShellProtocol(UUID.randomUUID().toString().replace("-", ""))
        val completed = CompletableDeferred<Int>()
        val started = CompletableDeferred<Unit>()
        val ready = Channel<Unit>(Channel.CONFLATED)
        private val captured = ByteArrayOutputStream()
        private var truncated = false
        @Synchronized fun append(bytes: ByteArray) {
            val count = minOf(bytes.size, 64 * 1024 - captured.size())
            captured.write(bytes, 0, count)
            truncated = truncated || count < bytes.size
        }
        @Synchronized fun result(code: Int, timedOut: Boolean = false) = WorkspaceCommandResult(
            exitCode = code, stdout = captured.toString(Charsets.UTF_8.name()), stderr = "",
            timedOut = timedOut, truncated = truncated,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private val outputLock = Any()
    private val closed = AtomicBoolean(false)
    @Volatile private var active: Invocation? = null
    val isOpen: Boolean get() = !closed.get()
    private var dispatched = false

    init {
        scope.launch {
            try {
                val buffer = ByteArray(8192)
                while (isOpen) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val invocation = active ?: continue
                    val chunk = invocation.protocol.feed(buffer.copyOf(count))
                    if (chunk.ready) invocation.ready.trySend(Unit)
                    if (chunk.started) invocation.started.complete(Unit)
                    if (chunk.output.isNotEmpty()) {
                        invocation.append(chunk.output)
                        invocation.onOutput(chunk.output)
                    }
                    chunk.exitCode?.let { invocation.completed.complete(it) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally {
                active?.let { invocation -> invocation.append(invocation.protocol.remainder()) }
                close()
            }
        }
    }

    suspend fun execute(
        command: String,
        cwd: String? = null,
        timeoutMillis: Long? = 30_000,
        onOutput: suspend (ByteArray) -> Unit = {},
        onReady: suspend () -> Unit = {},
        beforeDispatch: suspend () -> Unit = {},
        initialCwd: String? = null,
    ): WorkspaceCommandResult = commands.withLock {
        beforeDispatch()
        check(isOpen) { "Terminal session is closed. Its remote outcome is unknown; do not retry automatically." }
        RemoteTerminalCommandSpec(command, cwd.orEmpty()) // same command validation as the user card
        val invocation = Invocation(onOutput)
        active = invocation
        val timedOut = AtomicBoolean(false)
        val timeoutJob = timeoutMillis?.let { timeout -> scope.launch {
            delay(timeout)
            timedOut.set(true)
            close()
        } }
        try {
            for (upload in invocation.protocol.uploads(command, cwd ?: initialCwd.takeUnless { dispatched })) {
                runInterruptible(Dispatchers.IO) { write(upload.toByteArray()) }
                invocation.ready.receive()
            }
            runInterruptible(Dispatchers.IO) { write(invocation.protocol.invocation().toByteArray()) }
            dispatched = true
            // BEGIN confirms the remote parser consumed the wrapper. Before that, shells
            // such as dash can read ahead and strand keyboard bytes in their parser buffer.
            invocation.started.await()
            onReady()
            invocation.result(invocation.completed.await(), timedOut.get())
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        } catch (error: Exception) {
            close()
            // Retain partial output, but never invent an exit code after disconnection/exit/exec.
            invocation.result(-1, timedOut.get())
        } finally {
            timeoutJob?.cancel()
            active = null
        }
    }

    /** Caller must use an IO dispatcher. Input bytes are never captured in command output. */
    fun write(bytes: ByteArray) = synchronized(outputLock) {
        check(isOpen) { "Terminal session is closed" }
        output.write(bytes)
        output.flush()
    }

    /** Bind a terminal keyboard to this invocation so delayed input cannot reach the next call. */
    fun inputWriter(): (ByteArray) -> Unit {
        val invocation = requireNotNull(active) { "No active terminal command" }
        return { bytes ->
            synchronized(outputLock) {
                if (active === invocation && !invocation.completed.isCompleted && isOpen) write(bytes)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active?.completed?.complete(-1)
        active?.started?.completeExceptionally(IllegalStateException("Terminal session is closed"))
        active?.ready?.close()
        // Closing the transport must unblock a reader suspended in InputStream.read().
        try { closeTransport() } finally {
            scope.cancel()
            onClosed()
        }
    }
}
