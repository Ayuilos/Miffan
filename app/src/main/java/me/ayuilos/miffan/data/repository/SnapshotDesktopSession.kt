package me.ayuilos.miffan.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool as McpTool
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rdp.RdpStats
import me.rerere.workspace.RemoteChannelStream
import me.rerere.workspace.screen.RemoteScreenFrameSink
import me.rerere.workspace.screen.RemoteScreenState
import me.rerere.workspace.screen.RemoteScreenStats
import me.rerere.workspace.screen.RfbRect

private const val TAG = "SnapshotScreen"

/** How a snapshot is captured: the long edge in pixels and the JPEG quality (20–90) it is sent with. */
data class SnapshotQuality(val maxDimension: Int, val jpegQuality: Int)

/**
 * The screen as still pictures, for a screen opened through a relay, where a stream is slow: every
 * [intervalMillis] (and shortly after each action) cua-driver captures the desktop on the computer,
 * the helper sends it back as a JPEG unless nothing changed, and taps, keys and text become
 * cua-driver actions in the picture's pixels. Both run on the relay's screen channel: [process] is
 * cua-driver's MCP, and [pictures] the helper's `snapshot serve`, which sends each picture as raw bytes.
 *
 * Every refresh is SSH traffic through the relay, and each minute with traffic counts against the
 * computer's daily relay allowance. So after [stopAfterStillMillis] with an unchanged picture and
 * no activity (input, [refresh], [setQuality], [setPaused] false, [noteComputerAction]), refreshing
 * stops until the next activity: [autoRefreshStopped].
 */
class SnapshotDesktopSession internal constructor(
    private val process: RemoteChannelStream,
    /** The helper's `snapshot serve`, see [SnapshotPictures]. */
    private val pictures: RemoteChannelStream,
    private val sink: RemoteScreenFrameSink,
    private val name: String,
    macos: Boolean,
    windows: Boolean,
    quality: SnapshotQuality,
    private val onClose: () -> Unit,
    private val intervalMillis: Long = 2_000,
    private val idleMillis: Long = 5_000,
    /** Null refreshes for as long as the screen is open. */
    private val stopAfterStillMillis: Long? = 30_000,
    private val nowNanos: () -> Long = System::nanoTime,
) : RemoteDesktopSession {
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private var job: Job? = null
    private val client = Client(clientInfo = Implementation(name = "miffan", version = "1.0"))
    private val label = "miffan-view-" + UUID.randomUUID().toString().take(8)
    private var tools: Map<String, McpTool> = emptyMap()
    private val calls = Mutex()
    private val input = SnapshotInput(macos, windows)
    private val actions = Channel<SnapshotAction>(Channel.UNLIMITED)
    private val nudges = Channel<Unit>(Channel.CONFLATED)
    private val paused = MutableStateFlow(false)
    private val _autoRefreshStopped = MutableStateFlow(false)
    override val autoRefreshStopped: StateFlow<Boolean> = _autoRefreshStopped
    private val pacing = SnapshotPacing(intervalMillis, idleMillis, stopAfterStillMillis)
    /** The last change of picture or activity, which keeps automatic refreshing going. */
    @Volatile private var lastActivityNanos = nowNanos()
    @Volatile private var quality = quality
    /** Bytes of pictures cua-driver returned itself, when it ignores the output file. */
    @Volatile private var inline = 0L
    private val serve = SnapshotPictures(pictures.input, pictures.output)
    /** Where cua-driver writes each capture, named by `snapshot serve`. */
    private var shotPath = ""
    private var width = 0
    private var height = 0

    private val _state = MutableStateFlow<RemoteScreenState>(RemoteScreenState.Connecting)
    private val _stats = MutableStateFlow(RemoteScreenStats(encodings = setOf("snapshot")))

    override val protocol = RemoteDesktopProtocol.SNAPSHOT
    override val state: StateFlow<RemoteScreenState> = _state
    override val clipboard = MutableSharedFlow<String>()
    override val bytesReceived: Long get() = inline + serve.bytesRead
    override val certificateSha256: StateFlow<String?> = MutableStateFlow(null)
    override val rdpStats: StateFlow<RdpStats>? = null
    override val stats: StateFlow<RemoteScreenStats> = _stats
    override var statsLogger: ((RemoteScreenStats) -> Unit)? = null

    override fun start(scope: CoroutineScope) {
        check(!closed.get() && started.compareAndSet(false, true)) { "Session already started or closed" }
        job = scope.launch(Dispatchers.IO) {
            try {
                pictures.errors?.let { errors -> launch { logErrors(errors) } }
                connect()
                launch { runActions() }
                runCaptures()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "snapshot screen ended", error)
                _state.value = RemoteScreenState.Closed(error)
            } finally {
                withContext(NonCancellable) {
                    runCatching { withTimeoutOrNull(2_000) { client.close() } }
                    release()
                }
            }
        }
    }

    /** The helper's greeting and cua-driver's MCP handshake are independent: wait for both at once. */
    private suspend fun connect() = coroutineScope {
        val ready = async { blocking(30_000) { serve.ready() } }
        try {
            val transport = StdioClientTransport(
                input = process.input.asSource().buffered(),
                output = process.output.asSink().buffered(),
                error = process.errors?.asSource()?.buffered(),
            )
            withTimeout(30.seconds) { client.connect(transport) }
            tools = client.listTools().tools.associateBy { it.name }
            check("get_desktop_state" in tools) { "cua-driver cannot capture the desktop" }
            shotPath = ready.await()
        } catch (error: Throwable) {
            // The greeting's blocking read ends only when its channel closes.
            runCatching { pictures.close() }
            throw error
        }
        if ("start_session" in tools) call("start_session", JsonObject(emptyMap()))
    }

    /**
     * Captures until closed: new pictures every [intervalMillis], every [idleMillis] while nothing
     * changes, and none at all once still for [stopAfterStillMillis] until an activity nudges.
     */
    private suspend fun runCaptures() {
        var since: String? = null
        var unchanged = 0
        var failures = 0
        var sentQuality = quality
        while (currentJobActive()) {
            paused.first { !it }
            val wanted = quality
            if (wanted != sentQuality) { since = null; sentQuality = wanted }
            try {
                val shot = capture(wanted, since)
                failures = 0
                if (shot == null) unchanged++ else {
                    unchanged = 0
                    since = shot.digest
                    lastActivityNanos = nowNanos()
                    show(shot.bytes)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                throw error // The helper or the channel is gone.
            } catch (error: Throwable) {
                // One slow or failed capture is not the end; three in a row are.
                if (++failures >= 3) throw error
                Log.w(TAG, "capture failed ($failures)", error)
            }
            val wait = pacing.nextWait(unchanged, (nowNanos() - lastActivityNanos) / 1_000_000)
            if (wait == null) {
                _autoRefreshStopped.value = true
                holdSession()
                nudges.receive()
                _autoRefreshStopped.value = false
                holdSession()
                lastActivityNanos = nowNanos()
            } else if (withTimeoutOrNull(wait) { nudges.receive() } != null) {
                lastActivityNanos = nowNanos()
            }
        }
    }

    /** Input counts as activity; while refreshing has stopped it also fetches a picture at once. */
    private fun inputActivity() {
        lastActivityNanos = nowNanos()
        if (_autoRefreshStopped.value) nudges.trySend(Unit)
    }

    override fun refresh() {
        lastActivityNanos = nowNanos()
        nudges.trySend(Unit)
    }

    /** The partner just acted on this computer: the picture is about to change. */
    fun noteComputerAction() = refresh()

    private fun currentJobActive() = job?.isActive != false && !closed.get()

    private class Shot(val bytes: ByteArray, val digest: String?)

    /** The desktop now, or null when it is the same as the picture [since] identifies. */
    private suspend fun capture(wanted: SnapshotQuality, since: String?): Shot? {
        val result = call("get_desktop_state", JsonObject(mapOf(
            "max_image_dimension" to JsonPrimitive(wanted.maxDimension),
            "screenshot_out_file" to JsonPrimitive(shotPath),
        )))
        check(result.isError != true) { result.text().ifBlank { "cua-driver could not capture the desktop" } }
        // A driver that ignores the output file returns the PNG itself.
        result.content.filterIsInstance<ImageContent>().lastOrNull()?.let { image ->
            val bytes = Base64.decode(image.data, Base64.DEFAULT)
            inline += image.data.length
            return Shot(bytes, null)
        }
        return when (val reply = blocking(60_000) { serve.request(wanted.jpegQuality, since) }) {
            is SnapshotPictures.Reply.Same -> null
            is SnapshotPictures.Reply.Picture -> Shot(reply.bytes, reply.digest)
        }
    }

    /**
     * Runs a blocking read or write of [pictures] off the main thread. A stalled helper gets
     * [timeoutMillis]; then its channel is closed, which ends the read and the screen.
     */
    private suspend fun <T> blocking(timeoutMillis: Long, block: () -> T): T = coroutineScope {
        val watchdog = launch {
            delay(timeoutMillis)
            Log.w(TAG, "snapshot serve stalled")
            runCatching { pictures.close() }
        }
        try { withContext(Dispatchers.IO) { block() } } finally { watchdog.cancel() }
    }

    private fun logErrors(errors: InputStream) {
        runCatching {
            errors.bufferedReader().useLines { lines -> lines.forEach { Log.w(TAG, "snapshot serve: ${it.take(300)}") } }
        }
    }

    private fun show(bytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("Unreadable snapshot")
        try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            if (w != width || h != height) {
                width = w
                height = h
                sink.onSize(w, h, 1)
            }
            sink.onPixels(RfbRect(0, 0, w, h), pixels)
            sink.onFrameComplete()
            if (_state.value !is RemoteScreenState.Connected) _state.value = RemoteScreenState.Connected(name, w, h, 1)
            else if ((_state.value as RemoteScreenState.Connected).width != w) _state.value = RemoteScreenState.Connected(name, w, h, 1)
        } finally {
            bitmap.recycle()
        }
    }

    /** Sends queued actions in order, folding what piled up while the last one ran, then refreshes the picture. */
    private suspend fun runActions() {
        for (first in actions) {
            val batch = mutableListOf(first)
            while (true) batch += actions.tryReceive().getOrNull() ?: break
            for (action in SnapshotInput.coalesce(batch)) {
                try {
                    val result = perform(action) ?: continue
                    if (result.isError == true) Log.w(TAG, "${action::class.simpleName} refused: ${result.text().take(200)}")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "${action::class.simpleName} failed", error)
                }
            }
            // Let the computer redraw before the next picture.
            delay(ACTION_SETTLE_MILLIS)
            nudges.trySend(Unit)
        }
    }

    private suspend fun perform(action: SnapshotAction): CallToolResult? = when (action) {
        is SnapshotAction.Click -> call("click", desktop(
            "x" to JsonPrimitive(action.x), "y" to JsonPrimitive(action.y),
            "button" to JsonPrimitive(action.button).takeIf { action.button != "left" },
            "count" to JsonPrimitive(action.count).takeIf { action.count > 1 },
        ))
        is SnapshotAction.Drag -> call("drag", desktop(
            "from_x" to JsonPrimitive(action.fromX), "from_y" to JsonPrimitive(action.fromY),
            "to_x" to JsonPrimitive(action.toX), "to_y" to JsonPrimitive(action.toY),
        ))
        is SnapshotAction.Scroll -> call("scroll", desktop(
            "x" to JsonPrimitive(action.x), "y" to JsonPrimitive(action.y),
            "direction" to JsonPrimitive(action.direction), "amount" to JsonPrimitive(action.amount),
        ))
        is SnapshotAction.Key -> if (action.modifiers.isEmpty()) call("press_key", desktop("key" to JsonPrimitive(action.name)))
            else call("hotkey", desktop("keys" to JsonArray((action.modifiers + action.name).map(::JsonPrimitive))))
        is SnapshotAction.Type -> call("type_text", desktop("text" to JsonPrimitive(action.text)))
        is SnapshotAction.Clipboard -> if ("clipboard_write" in tools) call("clipboard_write", JsonObject(mapOf("text" to JsonPrimitive(action.text)))) else null
    }

    private fun desktop(vararg fields: Pair<String, JsonElement?>): JsonObject =
        JsonObject(mapOf<String, JsonElement>("scope" to JsonPrimitive("desktop")) +
            fields.mapNotNull { (key, value) -> value?.let { key to it } })

    /** One cua-driver call under this session's label; captures and actions take turns. */
    private suspend fun call(name: String, arguments: JsonObject): CallToolResult = calls.withLock {
        val withSession = if (tools[name]?.inputSchema?.properties?.containsKey("session") == true)
            JsonObject(arguments + ("session" to JsonPrimitive(label))) else arguments
        client.callTool(
            request = CallToolRequest(params = CallToolRequestParams(name = name, arguments = withSession)),
            options = RequestOptions(timeout = 60.seconds),
        )
    }

    /** Pictures of a different size or quality from the next capture on. */
    fun setQuality(value: SnapshotQuality) {
        quality = value
        refresh()
    }

    override fun setPaused(paused: Boolean) {
        this.paused.value = paused
        holdSession()
        if (!paused) refresh()
    }

    /**
     * A hidden screen, or one that stopped refreshing, does not keep its relay session alive: an
     * idle relay session closes after a few minutes instead of costing a relay minute every few.
     */
    private fun holdSession() {
        val hold = !paused.value && !_autoRefreshStopped.value
        process.holdsSessionOpen = hold
        pictures.holdsSessionOpen = hold
    }

    override fun setMaxFps(fps: Int) {} // Pictures come at their own pace.

    override fun pointer(x: Int, y: Int, buttons: Int) {
        inputActivity()
        input.pointer(x, y, buttons).forEach { actions.trySend(it) }
    }

    override fun key(keysym: Int, down: Boolean) {
        inputActivity()
        input.key(keysym, down)?.let { actions.trySend(it) }
    }

    /** cua-driver types any language as-is, so text never needs the clipboard. */
    override fun typeText(text: String): Boolean {
        inputActivity()
        if (text.isNotEmpty()) actions.trySend(SnapshotAction.Type(text))
        return true
    }

    override fun sendClipboard(text: String) {
        inputActivity()
        actions.trySend(SnapshotAction.Clipboard(text))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job?.cancel()
        // A blocking read of a picture ends only when its channel closes.
        runCatching { pictures.close() }
        if (job == null) release()
        if (_state.value !is RemoteScreenState.Closed) _state.value = RemoteScreenState.Closed(null)
    }

    private val released = AtomicBoolean()

    /** Closes the process and the screen channel; the MCP client is closed by the capture job that opened it. */
    private fun release() {
        if (!released.compareAndSet(false, true)) return
        actions.close()
        runCatching { process.close() }
        runCatching { pictures.close() }
        runCatching { onClose() }
    }

    private companion object {
        const val ACTION_SETTLE_MILLIS = 350L
    }
}

private fun CallToolResult.text() = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

/**
 * When the next snapshot is due: [intervalMillis] after a change, [idleMillis] once three captures
 * in a row were unchanged, and never on its own (null: wait for a nudge) once the picture has been
 * still, without activity, for [stopAfterStillMillis].
 */
internal class SnapshotPacing(
    private val intervalMillis: Long,
    private val idleMillis: Long,
    private val stopAfterStillMillis: Long?,
) {
    fun nextWait(unchanged: Int, stillForMillis: Long): Long? = when {
        stopAfterStillMillis != null && unchanged > 0 && stillForMillis >= stopAfterStillMillis -> null
        unchanged >= 3 -> idleMillis
        else -> intervalMillis
    }
}
