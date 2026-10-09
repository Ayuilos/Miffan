package me.rerere.workspace.screen

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

data class RemoteScreenOptions(
    /** JPEG quality 0..9 for Tight; null asks for lossless encodings only. */
    val jpegQuality: Int? = 6,
    val maxFps: Int = 15,
    /** 16-bit colour: lossless encodings (macOS screen sharing) send a third fewer bytes. */
    val lowColor: Boolean = false,
    /** 1 keeps serial request/decode/upload; 2 overlaps the next request with this update. */
    val pipelineDepth: Int = 2,
)

sealed interface RemoteScreenState {
    data object Connecting : RemoteScreenState

    /** [width]/[height] are server pixels; the phone renders them divided by [scale]. */
    data class Connected(val name: String, val width: Int, val height: Int, val scale: Int) : RemoteScreenState

    data class Closed(val error: Throwable?) : RemoteScreenState
}

/**
 * Receives pixels on the session's reader thread, in output (possibly downscaled) coordinates.
 * Implementations must not block for long: decode and upload still share one reader coroutine.
 */
interface RemoteScreenFrameSink {
    /** Called before the first frame and after every server resize; all pixels follow. */
    fun onSize(width: Int, height: Int, scale: Int)

    /** [pixels] holds [rect] row-major and is only valid during the call. */
    fun onPixels(rect: RfbRect, pixels: IntArray)

    fun onFrameComplete()

    /** The remote pointer shape changed; null pixels with zero size means hidden. */
    fun onCursor(cursor: RfbCursor) {}
}

/**
 * One VNC viewer over an already-open byte stream. The reader paces update requests to
 * [RemoteScreenOptions.maxFps] and stops requesting while paused; input is queued and written
 * from a separate coroutine so callers on the main thread never block on the network.
 */
class RemoteScreenSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val transport: Closeable,
    private val credentials: RfbCredentials?,
    private val jpeg: RfbJpegDecoder?,
    options: RemoteScreenOptions,
    private val sink: RemoteScreenFrameSink,
    private val nanoTime: () -> Long = System::nanoTime,
) : Closeable {
    private sealed interface Input {
        data class Pointer(val x: Int, val y: Int, val buttons: Int) : Input
        data class Key(val keysym: Int, val down: Boolean) : Input
        data class Clipboard(val text: String) : Input
    }

    private val _state = MutableStateFlow<RemoteScreenState>(RemoteScreenState.Connecting)
    val state: StateFlow<RemoteScreenState> = _state.asStateFlow()

    private val _clipboard = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Text the remote side put on its clipboard (UTF-8 when the server sends it, else ISO-8859-1). */
    val clipboard: SharedFlow<String> = _clipboard.asSharedFlow()

    private val _stats = MutableStateFlow(RemoteScreenStats(
        pixelFormat = if (options.lowColor) RfbPixelFormat.RGB565 else RfbPixelFormat.RGB888,
    ))
    /** Rolling statistics over the last [RemoteScreenStats.WINDOW_MILLIS]; updated about twice a second. */
    val stats: StateFlow<RemoteScreenStats> = _stats.asStateFlow()

    /** Called about every two seconds on the reader; null uses java.util.logging. */
    @Volatile var statsLogger: ((RemoteScreenStats) -> Unit)? = null

    private val paused = AtomicBoolean(false)
    private val resumeVersion = AtomicLong()
    private val controls = Channel<Unit>(Channel.CONFLATED)
    private val pipelineDepth = options.pipelineDepth.also { require(it in 1..2) { "pipelineDepth must be 1 or 2" } }
    @Volatile private var maxFps = options.maxFps.coerceIn(1, 60)
    private val jpegQuality = options.jpegQuality
    private val pixelFormat = if (options.lowColor) RfbPixelFormat.RGB565 else RfbPixelFormat.RGB888
    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private var client: RfbClient? = null
    private var reader: Job? = null
    private var writer: Job? = null

    /** Bytes received from the server, for data-usage display. */
    val bytesReceived: Long get() = client?.bytesReceived ?: 0L

    fun start(scope: CoroutineScope) {
        check(reader == null) { "Session already started" }
        reader = scope.launch(Dispatchers.IO) { runReader(this) }
    }

    fun setPaused(value: Boolean) {
        if (paused.getAndSet(value) && !value) resumeVersion.incrementAndGet()
        controls.trySend(Unit)
    }

    fun setMaxFps(value: Int) {
        maxFps = value.coerceIn(1, 60)
        controls.trySend(Unit)
    }

    /** [x]/[y] are server pixels; [buttons] is the RFB mask (1 left, 2 middle, 4 right, 8/16 wheel). */
    fun pointer(x: Int, y: Int, buttons: Int) {
        inputs.trySend(Input.Pointer(x, y, buttons))
    }

    fun key(keysym: Int, down: Boolean) {
        inputs.trySend(Input.Key(keysym, down))
    }

    fun tapKey(keysym: Int) {
        key(keysym, true)
        key(keysym, false)
    }

    /**
     * Types [text] as key presses. Returns false without sending anything when a character has
     * no keysym that servers reliably accept; callers should then paste through the remote
     * clipboard instead.
     */
    fun typeText(text: String): Boolean {
        val keysyms = text.map { RfbKeys.forChar(it) ?: return false }
        keysyms.forEach(::tapKey)
        return true
    }

    fun sendClipboard(text: String) {
        inputs.trySend(Input.Clipboard(text))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun runReader(scope: CoroutineScope) {
        try {
            val rfb = RfbClient(input, output, credentials, jpeg, pixelFormat, nanoTime)
            client = rfb
            val info = rfb.handshake(RfbClient.defaultEncodings(jpegQuality = if (jpeg != null) jpegQuality else null))
            var scale = ScreenScaler.scaleFor(info.width, info.height)
            _state.value = RemoteScreenState.Connected(info.name, info.width, info.height, scale)
            writer = scope.launch { runWriter(rfb) }
            announceSize(rfb.framebuffer, scale)

            val pacer = ScreenRequestPacer(pipelineDepth)
            val window = ScreenStatsWindow(nanoTime(), rfb.bytesReceived)
            var lastPublish = nanoTime()
            var lastLog = lastPublish
            var handledResume = 0L
            var buffer = IntArray(0)
            fun request(atHeader: Boolean = false) {
                val version = resumeVersion.get()
                val incremental = pacer.request(nanoTime(), maxFps, paused.get(), atHeader, version != handledResume) ?: return
                rfb.requestUpdate(incremental)
                handledResume = version
            }
            fun publish() {
                val now = nanoTime()
                if (now - lastPublish < 500_000_000L) return
                val snapshot = window.snapshot(now, rfb.bytesReceived, scale, pixelFormat, pacer.outstanding)
                _stats.value = snapshot
                lastPublish = now
                if (now - lastLog >= 2_000_000_000L) {
                    lastLog = now
                    // A diagnostic callback must never terminate the screen connection.
                    runCatching {
                        statsLogger?.invoke(snapshot) ?: java.util.logging.Logger
                            .getLogger(RemoteScreenSession::class.java.name).info(snapshot.toString())
                    }
                }
            }
            request()
            while (scope.isActive && !closed.get()) {
                // Only the one-byte header read is asynchronous. Body reads, decode, scale,
                // uploads and all update requests stay on this reader, with no frame queue.
                val pendingHeader = scope.async(Dispatchers.IO) { runCatching { rfb.readMessageHeader() } }
                var header: RfbClient.MessageHeader? = null
                while (header == null && scope.isActive && !closed.get()) {
                    request()
                    publish()
                    val now = nanoTime()
                    val waitNanos = minOf(
                        (lastPublish + 500_000_000L - now).coerceAtLeast(0),
                        pacer.waitNanos(now, maxFps, paused.get(), resumeVersion.get() != handledResume),
                    )
                    select<Unit> {
                        pendingHeader.onAwait { header = it.getOrThrow() }
                        controls.onReceive { }
                        // Sleep until a request/statistics deadline; controls wake us immediately.
                        onTimeout((waitNanos / 1_000_000L + 1).coerceAtLeast(1)) { }
                    }
                }
                val received = header ?: break
                val latency = if (received.type == 0) pacer.latency(received.receivedNanos) else null
                val decodeBefore = rfb.decodeNanos
                if (received.type == 0) request(atHeader = true)
                val event = rfb.readMessage(received)
                if (event !is RfbEvent.FramebufferUpdated) {
                    if (event is RfbEvent.CutText) _clipboard.tryEmit(event.text)
                    continue
                }
                // A header is a reply even when it contains no pixels or only a cursor.
                pacer.complete()
                val readTime = rfb.readNanos - received.readBefore
                val decodeTime = rfb.decodeNanos - decodeBefore
                val updateBytes = rfb.bytesRead - received.bytesBefore
                var scaleTime = 0L
                var sinkTime = 0L
                event.cursor?.let(sink::onCursor)
                val fb = rfb.framebuffer
                val dirty = if (event.resized) {
                    pacer.resized(nanoTime())
                    scale = ScreenScaler.scaleFor(fb.width, fb.height)
                    _state.value = RemoteScreenState.Connected(info.name, fb.width, fb.height, scale)
                    announceSize(fb, scale)
                    listOf(RfbRect(0, 0, fb.width, fb.height))
                } else ScreenScaler.merge(event.rects)
                var displayed = false
                for (rect in dirty) {
                    val out = ScreenScaler.outputRect(rect, scale, fb.width, fb.height)
                    val size = out.width * out.height
                    if (size == 0) continue
                    displayed = true
                    if (buffer.size < size) buffer = IntArray(size)
                    var started = nanoTime()
                    ScreenScaler.copy(fb, scale, out, buffer)
                    scaleTime += nanoTime() - started
                    started = nanoTime()
                    sink.onPixels(out, buffer)
                    sinkTime += nanoTime() - started
                }
                val started = nanoTime()
                sink.onFrameComplete()
                sinkTime += nanoTime() - started
                window.add(ScreenStatsWindow.Update(
                    nanoTime(), updateBytes, latency, readTime, decodeTime, scaleTime, sinkTime,
                    event.rects.sumOf { it.width.toLong() * it.height }, displayed,
                    if (event.hasTightJpeg) event.encodings + RfbClient.ENCODING_TIGHT_JPEG else event.encodings,
                ))
                publish()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!closed.get()) _state.value = RemoteScreenState.Closed(error)
        } finally {
            if (_state.value !is RemoteScreenState.Closed) _state.value = RemoteScreenState.Closed(null)
            shutdown()
        }
    }

    private fun announceSize(fb: Framebuffer, scale: Int) {
        val (w, h) = ScreenScaler.outputSize(fb.width, fb.height, scale)
        sink.onSize(w, h, scale)
    }

    private suspend fun runWriter(rfb: RfbClient) {
        try {
            for (event in inputs) {
                when (event) {
                    is Input.Pointer -> rfb.pointer(event.x, event.y, event.buttons)
                    is Input.Key -> rfb.key(event.keysym, event.down)
                    is Input.Clipboard -> rfb.clientCutText(event.text)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!closed.get()) _state.value = RemoteScreenState.Closed(error)
            shutdown()
        }
    }

    private fun shutdown() {
        _stats.value = _stats.value.copy(outstandingRequests = 0)
        inputs.close()
        writer?.cancel()
        runCatching { transport.close() }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            if (_state.value !is RemoteScreenState.Closed) _state.value = RemoteScreenState.Closed(null)
            reader?.cancel()
            shutdown()
        }
    }
}

/** X11 keysyms for the keys a phone keyboard and the screen toolbar can produce. */
object RfbKeys {
    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val RETURN = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val DELETE = 0xFFFF
    const val HOME = 0xFF50
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END = 0xFF57
    const val F1 = 0xFFBE
    const val SHIFT_L = 0xFFE1
    const val CONTROL_L = 0xFFE3
    const val META_L = 0xFFE7
    const val ALT_L = 0xFFE9
    /** macOS servers map Super to Command. */
    const val SUPER_L = 0xFFEB

    fun function(n: Int): Int {
        require(n in 1..12)
        return F1 + n - 1
    }

    /** Latin-1 printable characters map to themselves; other text should go through paste. */
    fun forChar(c: Char): Int? = when (c) {
        '\n' -> RETURN
        '\t' -> TAB
        '\b' -> BACKSPACE
        in ' '..'~', in ' '..'ÿ' -> c.code
        else -> null
    }
}
