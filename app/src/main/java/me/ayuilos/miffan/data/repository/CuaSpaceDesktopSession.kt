package me.ayuilos.miffan.data.repository

import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.cuaspace.activateWindow
import me.rerere.cuaspace.SpaceClient
import me.rerere.cuaspace.SpaceInput
import me.rerere.cuaspace.SpaceMedia
import me.rerere.cuaspace.SpaceMediaEvent
import me.rerere.workspace.screen.RemoteScreenState
import me.rerere.workspace.screen.RemoteScreenStats

/**
 * A partner's own computer on the screen page: the Space's desktop as H.264 over its media socket, and
 * input as rcdp `interactive_input` (see cuaspace/src/main/proto/MEDIA.md). Text goes as committed text,
 * so any language types as-is; the clipboard goes through the Space's clipboard API.
 */
class CuaSpaceDesktopSession internal constructor(
    private val client: SpaceClient,
    private val media: SpaceMedia,
    private val name: String,
    private val lifetime: CoroutineScope,
    /** Runs after the media session ended, e.g. to close a client this session owns. */
    private val onClosed: () -> Unit = {},
    /**
     * The single window shown, on a computer's own desktop. Input goes to whatever is on top where the
     * pointer lands, so the window is brought to the front (switching to its desktop) before the first
     * input and again after a pause, while that input waits.
     */
    private val window: me.rerere.cuaspace.SpaceWindow? = null,
    /** -1 flips wheel direction (a Mac's window with natural scrolling). */
    private val scrollSign: Double = 1.0,
) : RemoteDesktopSession, RemoteSurfaceTarget {
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private var watcher: Job? = null
    private var target: Surface? = null
    private var paused = false
    private val decoder = SpaceVideoDecoder(needKeyframe = media::requestKeyframe, rendered = ::rendered)
    private val _state = MutableStateFlow<RemoteScreenState>(RemoteScreenState.Connecting)
    private val _videoSize = MutableStateFlow<RemoteVideoSize?>(null)
    private val _stats = MutableStateFlow(RemoteScreenStats())
    private val _suspended = MutableStateFlow<String?>(null)
    /** Why capture is paused (a window minimized or hidden…), as the server says it; null while it runs. */
    val suspended: StateFlow<String?> = _suspended
    private var received = 0L
    private var renderedFrames = 0
    private var buttons = 0
    private var lastInputAt = 0L
    private var raising = false
    /** Whether the next input must bring [window] to the front first; set again by [noteFront]. */
    private var raiseNeeded = true
    private var raisedAt = 0L
    private var frontCheckedAt = 0L
    private val heldInput = mutableListOf<SpaceInput>()
    /** What the phone last put on the Space's clipboard, so it does not come back as the Space's own copy. */
    @Volatile private var sentClipboard: String? = null

    override val protocol = RemoteDesktopProtocol.SPACE
    override val surface: RemoteSurfaceTarget get() = this
    override val videoSize: StateFlow<RemoteVideoSize?> = _videoSize
    override val state: StateFlow<RemoteScreenState> = _state
    override val clipboard = MutableSharedFlow<String>()
    override val bytesReceived: Long get() = received
    override val certificateSha256: StateFlow<String?> = MutableStateFlow(null)
    override val rdpStats: StateFlow<me.rerere.rdp.RdpStats>? = null
    override val stats: StateFlow<RemoteScreenStats> = _stats
    override var statsLogger: ((RemoteScreenStats) -> Unit)? = null

    override fun start(scope: CoroutineScope) {
        check(!closed.get() && started.compareAndSet(false, true)) { "Session already started or closed" }
        watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        lifetime.launch { media.events.collect(::handle) }
        lifetime.launch(Dispatchers.IO) {
            // The Space has no clipboard push; what is copied there reaches the phone within a moment.
            var generation = runCatching { client.clipboard().first }.getOrNull()
            while (isActive) {
                delay(CLIPBOARD_POLL_MILLIS)
                val (now, text) = runCatching { client.clipboard() }.getOrNull() ?: continue
                if (now != generation) {
                    generation = now
                    if (text != null && text != sentClipboard) clipboard.emit(text)
                }
            }
        }
        lifetime.launch {
            // Frames only come when the desktop changes, so fps is what was drawn in the last second.
            while (isActive) {
                delay(1_000)
                val now = RemoteScreenStats(fps = renderedFrames.toDouble(), encodings = setOf("H.264"))
                renderedFrames = 0
                _stats.value = now
                statsLogger?.invoke(now)
            }
        }
    }

    private fun handle(event: SpaceMediaEvent) {
        when (event) {
            is SpaceMediaEvent.Opened -> resize(event.width, event.height)
            is SpaceMediaEvent.Geometry -> resize(event.width, event.height)
            is SpaceMediaEvent.Frame -> {
                received += event.data.size
                if (event.width > 0 && event.height > 0 && _videoSize.value != RemoteVideoSize(event.width, event.height)) {
                    resize(event.width, event.height)
                }
                if (!paused) decoder.frame(event)
            }
            is SpaceMediaEvent.Closed -> if (!closed.get()) {
                _state.value = RemoteScreenState.Closed(event.error ?: SpaceMediaClosedException(event.code, event.reason))
            }
            is SpaceMediaEvent.Suspended -> _suspended.value = event.reason ?: "suspended"
            SpaceMediaEvent.Resumed -> _suspended.value = null
            is SpaceMediaEvent.Other -> Unit
        }
    }

    private fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        _videoSize.value = RemoteVideoSize(width, height)
        _state.value = RemoteScreenState.Connected(name, width, height, 1)
    }

    private fun rendered(sequence: Long) {
        renderedFrames++
        media.ack(sequence)
    }

    @Synchronized override fun setSurface(surface: Surface?) {
        target = surface
        decoder.setSurface(if (paused) null else surface)
        if (surface != null && !paused) media.requestKeyframe()
    }

    @Synchronized override fun setPaused(paused: Boolean) {
        this.paused = paused
        decoder.setSurface(if (paused) null else target)
        if (!paused && target != null) media.requestKeyframe()
    }

    override fun setMaxFps(fps: Int) {}

    /** [x], [y] are frame pixels; [mask] is VNC's: 1 left, 2 middle, 4 right, 8/16 wheel up/down, 32/64 left/right. */
    @Synchronized override fun pointer(x: Int, y: Int, buttons: Int) {
        if (closed.get()) return
        val size = _videoSize.value ?: return
        val nx = (x.toDouble() / size.width).coerceIn(0.0, 1.0)
        val ny = (y.toDouble() / size.height).coerceIn(0.0, 1.0)
        val events = mutableListOf<SpaceInput>(SpaceInput.Pointer("move", null, nx, ny))
        for ((bit, name) in listOf(1 to "left", 2 to "middle", 4 to "right")) {
            val now = buttons and bit != 0
            if (now != (this.buttons and bit != 0)) events += SpaceInput.Pointer(if (now) "down" else "up", name, nx, ny)
        }
        // Wheel bits are presses: one notch each time a bit turns on.
        for ((bit, delta) in listOf(8 to (0.0 to -WHEEL), 16 to (0.0 to WHEEL), 32 to (-WHEEL to 0.0), 64 to (WHEEL to 0.0))) {
            if (buttons and bit != 0 && this.buttons and bit == 0) events += SpaceInput.Scroll(nx, ny, delta.first * scrollSign, delta.second * scrollSign)
        }
        this.buttons = buttons
        deliver(events)
    }

    override fun key(keysym: Int, down: Boolean) {
        if (closed.get()) return
        val key = spaceKeyName(keysym) ?: return
        deliver(listOf(SpaceInput.Key(key, down)))
    }

    override fun typeText(text: String): Boolean = !closed.get() && deliver(listOf(SpaceInput.Text(text)))

    /**
     * What the computer says is in front: when [window] is not (someone switched desktop or app there),
     * the next input brings it back first. Ignored right after a raise, which it may predate.
     */
    @Synchronized fun noteFront(inFront: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (raising || now - raisedAt < RAISE_SETTLE_MILLIS) return
        frontCheckedAt = now
        raiseNeeded = !inFront
    }

    /**
     * Sends [events], first bringing [window] to the front when it is not (see [noteFront]), or, when
     * nothing said lately, when input starts again after a pause.
     */
    @Synchronized private fun deliver(events: List<SpaceInput>): Boolean {
        val shown = window ?: return media.send(events)
        val now = android.os.SystemClock.elapsedRealtime()
        val unsure = now - frontCheckedAt > FRONT_STALE_MILLIS && now - lastInputAt > RAISE_AFTER_IDLE_MILLIS
        lastInputAt = now
        if (raising) { heldInput += events; return true }
        if (!raiseNeeded && !unsure) return media.send(events)
        raising = true
        raiseNeeded = false
        heldInput += events
        lifetime.launch(Dispatchers.IO) {
            // macOS may report the raise unverified although it happened; the input goes either way.
            withTimeoutOrNull(RAISE_TIMEOUT_MILLIS) { runCatching { client.activateWindow(shown) } }
            synchronized(this@CuaSpaceDesktopSession) {
                raising = false
                raisedAt = android.os.SystemClock.elapsedRealtime()
                if (!closed.get()) media.send(heldInput.toList())
                heldInput.clear()
            }
        }
        return true
    }

    override fun sendClipboard(text: String) {
        if (closed.get()) return
        sentClipboard = text
        lifetime.launch(Dispatchers.IO) { runCatching { client.setClipboardText(text) } }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        watcher?.cancel()
        decoder.close()
        _state.value = RemoteScreenState.Closed(null)
        // The session is ours alone: end it, not only the socket, so the Space stops encoding.
        lifetime.launch(NonCancellable + Dispatchers.IO) {
            media.closeSession()
            runCatching(onClosed)
            withContext(Dispatchers.Default) { lifetime.cancel() }
        }
    }

    private companion object {
        const val RAISE_AFTER_IDLE_MILLIS = 8_000L
        const val RAISE_TIMEOUT_MILLIS = 3_000L
        const val RAISE_SETTLE_MILLIS = 1_500L
        const val FRONT_STALE_MILLIS = 5_000L
        /** One wheel notch in the pixel deltas a browser sends. */
        const val WHEEL = 100.0
        const val CLIPBOARD_POLL_MILLIS = 1_500L
    }
}

class SpaceMediaClosedException(val code: Int?, reason: String?) :
    IllegalStateException(listOfNotNull(code?.toString(), reason).joinToString(": ").ifEmpty { "The Space closed the screen" })

/**
 * The rcdp key name for an X11 keysym: DOM key names lower-cased, as cua's own viewer sends them; letters
 * and digits as the key's character. Null for keys the Space has no name for.
 */
internal fun spaceKeyName(keysym: Int): String? = when (keysym) {
    0xff08 -> "backspace"
    0xff09, 0xfe20 -> "tab"
    0xff0d, 0xff8d -> "enter"
    0xff1b -> "escape"
    0xffff, 0xff9f -> "delete"
    0xff63, 0xff9e -> "insert"
    0xff50, 0xff95 -> "home"
    0xff57, 0xff9c -> "end"
    0xff55, 0xff9a -> "pageup"
    0xff56, 0xff9b -> "pagedown"
    0xff51, 0xff96 -> "arrowleft"
    0xff52, 0xff97 -> "arrowup"
    0xff53, 0xff98 -> "arrowright"
    0xff54, 0xff99 -> "arrowdown"
    0xffe1, 0xffe2 -> "shift"
    0xffe3, 0xffe4 -> "control"
    0xffe9, 0xffea -> "alt"
    0xffe7, 0xffe8, 0xffeb, 0xffec -> "meta"
    0xffe5 -> "capslock"
    0xff7f -> "numlock"
    0xff14 -> "scrolllock"
    0xff13 -> "pause"
    0xff61, 0xff15 -> "printscreen"
    0xff67 -> "contextmenu"
    0x20, 0xff80 -> "space"
    in 0xffbe..0xffd5 -> "f${keysym - 0xffbe + 1}"
    in 0xffb0..0xffb9 -> "${keysym - 0xffb0}"
    in 0x21..0x7e -> keysym.toChar().lowercaseChar().toString()
    else -> null
}
