package me.rerere.cuaspace

import cua.env.v1.ProcessSelector
import cua.env.v1.Signal
import cua.env.v1.StartProcessResponse
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A process running under a PTY in a Space, as blocking streams (the shape the persistent shell and
 * terminal pages already consume). Output arrives on the StartProcess stream; input goes out as
 * SendInput calls. Closing ends the stream, and `kill_on_disconnect` ends the process.
 */
class SpacePty internal constructor(
    private val client: SpaceClient,
    private val process: ProcessSelector,
    events: () -> Flow<StartProcessResponse>,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)
    @Volatile var exitCode: Int = -1
        private set
    @Volatile private var ended = false
    // Input addressed to the process by its tag fails until the server has registered it.
    private val started = CompletableDeferred<Unit>()

    val isConnected: Boolean get() = !closed.get() && !ended

    val input: InputStream = object : InputStream() {
        private var current: ByteArray = EMPTY
        private var position = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (position >= current.size) {
                val next = try { chunks.take() } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted", error)
                }
                if (next === END) {
                    chunks.offer(END)
                    return -1
                }
                current = next
                position = 0
            }
            val count = minOf(length, current.size - position)
            System.arraycopy(current, position, buffer, offset, count)
            position += count
            return count
        }

        override fun available(): Int = current.size - position
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            if (!isConnected) throw IOException("Space terminal is closed")
            val bytes = buffer.copyOfRange(offset, offset + length)
            runBlocking(Dispatchers.IO) {
                awaitStarted()
                client.sendInput(process, bytes, pty = true)
            }
        }
    }

    init {
        scope.launch {
            try {
                events().collect { response ->
                    val event = response.event ?: return@collect
                    if (event.start != null) started.complete(Unit)
                    event.data_?.let { data -> (data.pty ?: data.stdout ?: data.stderr)?.let { chunks.offer(it.toByteArray()) } }
                    event.end?.let { end ->
                        exitCode = end.exit_code ?: if (end.signal_number != 0) 128 + end.signal_number else -1
                    }
                }
            } catch (error: Exception) {
                // The stream ending for any reason closes the terminal; readers see EOF.
                started.completeExceptionally(IOException("Space terminal did not start", error))
            } finally {
                started.completeExceptionally(IOException("Space terminal ended"))
                ended = true
                chunks.offer(END)
            }
        }
    }

    /** Suspends until the process runs; fails when it could not start or ended first. */
    suspend fun awaitStarted(timeoutMillis: Long = START_TIMEOUT_MILLIS) {
        withTimeout(timeoutMillis) { started.await() }
    }

    fun resize(columns: Int, rows: Int) {
        require(columns in 1..4096 && rows in 1..4096) { "Invalid terminal size" }
        scope.launch { runCatching { awaitStarted(); client.resize(process, columns, rows) } }
    }

    fun interrupt() {
        scope.launch { runCatching { awaitStarted(); client.signal(process, Signal.SIGNAL_INT) } }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        started.completeExceptionally(IOException("Space terminal is closed"))
        scope.cancel()
        chunks.offer(END)
    }

    private companion object {
        const val START_TIMEOUT_MILLIS = 20_000L
        val EMPTY = ByteArray(0)
        val END = ByteArray(0)
    }
}
