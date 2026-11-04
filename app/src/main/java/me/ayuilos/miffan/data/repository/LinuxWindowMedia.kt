package me.ayuilos.miffan.data.repository

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import me.rerere.cuaspace.SpaceInput
import me.rerere.cuaspace.SpaceMediaEvent
import me.rerere.cuaspace.SpaceVideoChannel

/**
 * One window of a Linux computer through the helper's `window serve` (remote-helper/PROTOCOL.md): H.264
 * access units with a header line each on its stdout, input as one line per event on its stdin. The
 * helper brings the window to the front itself before input lands, so nothing here raises it.
 */
internal class LinuxWindowMedia(
    input: InputStream,
    private val output: OutputStream,
    /** Closes the SSH channel; the helper stops when its stdin ends. */
    private val onClose: () -> Unit,
) : SpaceVideoChannel {
    private val closed = AtomicBoolean()
    private val _events = MutableSharedFlow<SpaceMediaEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<SpaceMediaEvent> = _events
    private val input = DataInputStream(BufferedInputStream(input, 256 * 1024))
    private val lines = LinkedBlockingQueue<String>()
    @Volatile private var lastKeyframeRequest = 0L

    /** Everything read so far, headers included. */
    @Volatile var bytesRead = 0L
        private set

    init {
        Thread(::read, "linux-window-read").apply { isDaemon = true }.start()
        Thread(::write, "linux-window-write").apply { isDaemon = true }.start()
    }

    private fun read() {
        var said = false
        val ended = runCatching {
            while (!closed.get()) {
                val words = line().split(' ')
                when (words[0]) {
                    "opened" -> _events.tryEmit(SpaceMediaEvent.Opened("linux", words.int(1), words.int(2)))
                    "geometry" -> _events.tryEmit(SpaceMediaEvent.Geometry(words.int(1), words.int(2)))
                    "frame" -> {
                        check(words.size == 8) { "Unexpected frame header" }
                        val length = words.int(7)
                        check(length in 0..MAX_FRAME) { "Unexpected frame length" }
                        val data = ByteArray(length)
                        input.readFully(data)
                        bytesRead += length
                        _events.tryEmit(SpaceMediaEvent.Frame(
                            sequence = words[1].toLong(), codecEpoch = words[2].toLong(), keyframe = words[3] == "1",
                            width = words.int(4), height = words.int(5), timestampUs = words[6].toLong(), data = data,
                        ))
                    }
                    "suspended" -> _events.tryEmit(SpaceMediaEvent.Suspended(words.getOrNull(1)))
                    "resumed" -> _events.tryEmit(SpaceMediaEvent.Resumed)
                    "closed" -> {
                        val reason = words.getOrNull(1) ?: "ended"
                        // The window closed: the same code a Space gives for a target that is gone.
                        _events.tryEmit(SpaceMediaEvent.Closed(if (reason == "window_closed") 4410 else null, reason))
                        said = true
                        return@runCatching null
                    }
                    else -> _events.tryEmit(SpaceMediaEvent.Other(words[0], null))
                }
            }
            null
        }.exceptionOrNull()
        // The helper's own `closed` already said why; the stream ending after it is no news.
        if (!said && !closed.get()) _events.tryEmit(SpaceMediaEvent.Closed(null, if (ended is EOFException) "ended" else ended?.message, ended))
    }

    private fun write() {
        runCatching {
            while (true) {
                val line = lines.take()
                if (line === STOP) break
                output.write(line.toByteArray(Charsets.UTF_8))
                // Batch whatever else is already waiting into one flush.
                while (true) {
                    val next = lines.poll() ?: break
                    if (next === STOP) { output.flush(); return@runCatching }
                    output.write(next.toByteArray(Charsets.UTF_8))
                }
                output.flush()
            }
        }
    }

    override fun send(events: List<SpaceInput>): Boolean {
        if (closed.get()) return false
        events.forEach { event -> lines.add(linuxWindowLine(event) ?: return@forEach) }
        return true
    }

    override fun requestKeyframe() {
        val now = System.nanoTime()
        if (now - lastKeyframeRequest < 1_000_000_000L) return
        lastKeyframeRequest = now
        if (!closed.get()) lines.add("kf\n")
    }

    override fun ack(sequence: Long) {}

    override suspend fun closeSession() {
        if (!closed.compareAndSet(false, true)) return
        lines.add(STOP)
        runCatching { output.close() }
        runCatching(onClose)
    }

    private fun line(): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("window serve ended")
            bytesRead++
            if (b == '\n'.code) break
            check(line.size() < MAX_LINE) { "window header too long" }
            line.write(b)
        }
        return line.toString(Charsets.UTF_8.name()).removeSuffix("\r")
    }

    private fun List<String>.int(index: Int): Int = getOrNull(index)?.toIntOrNull() ?: error("Unexpected window header")

    private companion object {
        const val MAX_LINE = 4096
        const val MAX_FRAME = 16 * 1024 * 1024
        /** Ends the writer; compared by identity. */
        val STOP = String(charArrayOf('\n'))
    }
}

private fun Double.coord() = String.format(Locale.US, "%.5f", coerceIn(0.0, 1.0))
private fun Double.delta() = String.format(Locale.US, "%.1f", this)

/** One input line of `window serve` for [event]; null for what the helper has no use for. */
internal fun linuxWindowLine(event: SpaceInput): String? = when (event) {
    is SpaceInput.Pointer -> when (event.phase) {
        "move" -> "move ${event.x.coord()} ${event.y.coord()}\n"
        "down", "up" -> event.button?.takeIf { it in setOf("left", "middle", "right") }
            ?.let { "${event.phase} $it ${event.x.coord()} ${event.y.coord()}\n" }
        else -> null
    }
    is SpaceInput.Scroll -> "scroll ${event.x.coord()} ${event.y.coord()} ${event.deltaX.delta()} ${event.deltaY.delta()}\n"
    is SpaceInput.Key -> event.key.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
        ?.let { "key $it ${if (event.down) 1 else 0}\n" }
    is SpaceInput.Text -> event.text.takeIf { it.isNotEmpty() }
        ?.let { "text ${java.util.Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))}\n" }
}
