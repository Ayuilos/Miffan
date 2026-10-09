package me.rerere.rdp

import android.media.MediaCodecList
import android.os.Build
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.workspace.screen.RemoteScreenFrameSink
import me.rerere.workspace.screen.RemoteScreenState
import me.rerere.workspace.screen.RfbCursor
import me.rerere.workspace.screen.RfbRect

/**
 * One connection over an owned, already-open transport. Closing [transport] must unblock reads
 * and writes. Sink callbacks run on the RDP thread and should return promptly.
 */
class RdpSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val transport: Closeable,
    private val credentials: RdpCredentials,
    private val options: RdpOptions,
    private val sink: RemoteScreenFrameSink,
) : Closeable {
    private val mutableState = MutableStateFlow<RemoteScreenState>(RemoteScreenState.Connecting)
    val state: StateFlow<RemoteScreenState> = mutableState.asStateFlow()
    private val fingerprint = MutableStateFlow<String?>(null)
    val certificateSha256: StateFlow<String?> = fingerprint.asStateFlow()
    private val mutableStats = MutableStateFlow(RdpStats())
    val stats: StateFlow<RdpStats> = mutableStats.asStateFlow()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val bufferLock = Object()
    private val buffer = ByteArray(1024 * 1024)
    private var head = 0
    private var count = 0
    private var eof = false
    private var received = 0L
    private var sent = 0L
    @Volatile private var failure: Throwable? = null
    @Volatile private var paused = false
    private val commands = ArrayBlockingQueue<IntArray>(4096)
    private var cancellation: Job? = null
    @Volatile private var reader: Thread? = null
    @Volatile private var worker: Thread? = null
    private var frames = 0L
    private var windowFrames = 0L
    private var windowStart = System.nanoTime()

    @Synchronized fun start(scope: CoroutineScope) {
        check(!closed.get() && scope.isActive) { "Session is closed or scope is inactive" }
        check(started.compareAndSet(false, true)) { "Session already started" }
        try {
            Native.load()
        } catch (error: Throwable) {
            failure = error
            close()
            throw error
        }
        reader = Thread({ pump() }, "RDP-stream-reader").also { it.start() }
        worker = Thread({
            try {
                val directory = java.io.File(
                    requireNotNull(System.getProperty("java.io.tmpdir")), "miffan-rdp",
                ).apply { check(mkdirs() || isDirectory) }.absolutePath
                nativeRun(
                    credentials.username, credentials.password, credentials.domain ?: "",
                    options.width, options.height, options.security.ordinal, directory,
                )
            } catch (error: Throwable) {
                if (!closed.get()) failure = error
            } finally {
                close()
                reader?.join()
                cancellation?.cancel()
            }
        }, "RDP-session").also { it.start() }
        cancellation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        if (closed.get()) cancellation?.cancel()
    }

    fun setPaused(paused: Boolean) { this.paused = paused }
    fun pointer(x: Int, y: Int, buttons: Int) = enqueue(intArrayOf(1, x.coerceIn(0, 65535), y.coerceIn(0, 65535), buttons and 127))
    fun key(keysym: Int, down: Boolean) {
        RdpKeys.scancode(keysym)?.let { enqueue(intArrayOf(2, it, if (down) 1 else 0)) }
    }
    /** UTF-16 units, including surrogate pairs, are sent as Unicode down/up events. */
    fun typeText(text: String) { text.forEach { enqueue(intArrayOf(3, it.code)) } }
    private fun enqueue(command: IntArray) {
        if (!closed.get() && !commands.offer(command)) {
            failure = IllegalStateException("RDP input queue overflow")
        }
    }

    private fun pump() {
        val chunk = ByteArray(32768)
        try {
            while (!closed.get()) {
                val length = input.read(chunk)
                if (length < 0) break
                var offset = 0
                synchronized(bufferLock) {
                    received += length
                    while (offset < length && !closed.get()) {
                        while (count == buffer.size && !closed.get()) bufferLock.wait()
                        val tail = (head + count) % buffer.size
                        val copy = minOf(length - offset, buffer.size - count, buffer.size - tail)
                        chunk.copyInto(buffer, tail, offset, offset + copy)
                        count += copy
                        offset += copy
                        bufferLock.notifyAll()
                    }
                }
            }
        } catch (error: Throwable) {
            if (!closed.get()) failure = error
        } finally {
            synchronized(bufferLock) { eof = true; bufferLock.notifyAll() }
        }
    }

    // JNI entry points. The byte stream is nonblocking to FreeRDP, even for blocking SSH streams.
    private fun readTransport(bytes: ByteArray): Int = synchronized(bufferLock) {
        if (closed.get()) return -1
        if (count == 0) return if (eof) -1 else 0
        val length = minOf(count, bytes.size)
        val first = minOf(length, buffer.size - head)
        buffer.copyInto(bytes, 0, head, head + first)
        if (first < length) buffer.copyInto(bytes, first, 0, length - first)
        head = (head + length) % buffer.size
        count -= length
        bufferLock.notifyAll()
        length
    }
    private fun writeTransport(bytes: ByteArray): Boolean = try {
        if (closed.get()) false else { output.write(bytes); output.flush(); sent += bytes.size; true }
    } catch (error: Throwable) { if (!closed.get()) failure = error; false }
    private fun waitTransport(timeout: Int): Boolean = synchronized(bufferLock) {
        if (count == 0 && !eof && !closed.get()) bufferLock.wait(timeout.coerceIn(1, 50).toLong())
        count > 0 || eof || closed.get()
    }
    private fun isClosed() = closed.get() || failure != null
    private fun isPaused() = paused
    private fun nextCommand(): IntArray? = commands.poll()
    private fun verifyCertificate(actual: String): Boolean {
        fingerprint.value = actual
        return CertificateFingerprint.matches(options.certificateSha256, actual).also {
            if (!it) failure = SecurityException("RDP certificate SHA-256 mismatch")
        }
    }
    private fun onSize(width: Int, height: Int, security: String) {
        if (closed.get()) return
        sink.onSize(width, height, 1)
        mutableState.update { current ->
            if (current is RemoteScreenState.Closed) current else RemoteScreenState.Connected("RDP", width, height, 1)
        }
        mutableStats.value = mutableStats.value.copy(security = security)
    }
    private fun onPixels(x: Int, y: Int, width: Int, height: Int, pixels: IntArray) {
        if (!paused && !closed.get()) sink.onPixels(RfbRect(x, y, width, height), pixels)
    }
    private fun onFrame() {
        if (!paused && !closed.get()) { sink.onFrameComplete(); frames++; windowFrames++ }
        publishStats()
    }
    private fun onCursor(width: Int, height: Int, x: Int, y: Int, pixels: IntArray) {
        if (!closed.get()) sink.onCursor(RfbCursor(width, height, x, y, pixels))
    }
    private fun onEncoding(encoding: String, decoder: String?) {
        if (mutableStats.value.encoding == encoding && mutableStats.value.decoder == decoder) return
        val hardware = if (decoder != null && Build.VERSION.SDK_INT >= 29) {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == decoder }?.isHardwareAccelerated
        } else null
        mutableStats.value = mutableStats.value.copy(encoding = encoding, decoder = decoder, h264HardwareAccelerated = hardware)
    }
    private fun publishStats() {
        val now = System.nanoTime()
        val elapsed = now - windowStart
        if (elapsed < 500_000_000) return
        mutableStats.value = mutableStats.value.copy(frames = frames, framesPerSecond = windowFrames * 1e9 / elapsed,
            bytesReceived = synchronized(bufferLock) { received }, bytesSent = sent)
        windowFrames = 0
        windowStart = now
    }
    private fun onFailure(code: Long) {
        if (code != 0L && !closed.get() && failure == null) failure = IllegalStateException("FreeRDP error 0x${code.toString(16)}")
    }
    /** Closes the owned transport to unblock both IO threads; native cleanup happens on the worker. */
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { transport.close() }
        synchronized(bufferLock) { bufferLock.notifyAll() }
        commands.clear()
        mutableState.value = RemoteScreenState.Closed(failure)
        cancellation?.cancel()
    }
    /** For tests/background callers only; never blocks the main thread. */
    internal fun awaitStopped(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        listOfNotNull(worker, reader).forEach { it.join(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)) }
        return worker?.isAlive != true && reader?.isAlive != true
    }
    private external fun nativeRun(username: String, password: String, domain: String, width: Int, height: Int, security: Int, directory: String)
    private object Native { fun load() { System.loadLibrary("miffanrdp") } }
}
