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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * cua-driver actions in the picture's pixels. Both run over [process], cua-driver's MCP on the relay's
 * screen channel, and [encode], the helper's `snapshot encode` on that same channel.
 */
class SnapshotDesktopSession internal constructor(
    private val process: RemoteChannelStream,
    /** Runs `snapshot encode QUALITY SINCE` on the computer and returns its JSON line. */
    private val encode: (quality: Int, since: String?) -> String,
    /** Where cua-driver writes each capture, from `snapshot path`. */
    private val shotPath: String,
    private val sink: RemoteScreenFrameSink,
    private val name: String,
    macos: Boolean,
    windows: Boolean,
    quality: SnapshotQuality,
    private val onClose: () -> Unit,
    private val intervalMillis: Long = 2_000,
    private val idleMillis: Long = 5_000,
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
    @Volatile private var quality = quality
    @Volatile private var received = 0L
    private var width = 0
    private var height = 0

    private val _state = MutableStateFlow<RemoteScreenState>(RemoteScreenState.Connecting)
    private val _stats = MutableStateFlow(RemoteScreenStats(encodings = setOf("snapshot")))

    override val protocol = RemoteDesktopProtocol.SNAPSHOT
    override val state: StateFlow<RemoteScreenState> = _state
    override val clipboard = MutableSharedFlow<String>()
    override val bytesReceived: Long get() = received
    override val certificateSha256: StateFlow<String?> = MutableStateFlow(null)
    override val rdpStats: StateFlow<RdpStats>? = null
    override val stats: StateFlow<RemoteScreenStats> = _stats
    override var statsLogger: ((RemoteScreenStats) -> Unit)? = null

    override fun start(scope: CoroutineScope) {
        check(!closed.get() && started.compareAndSet(false, true)) { "Session already started or closed" }
        job = scope.launch(Dispatchers.IO) {
            try {
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

    private suspend fun connect() {
        val transport = StdioClientTransport(
            input = process.input.asSource().buffered(),
            output = process.output.asSink().buffered(),
            error = process.errors?.asSource()?.buffered(),
        )
        withTimeout(30.seconds) { client.connect(transport) }
        tools = client.listTools().tools.associateBy { it.name }
        check("get_desktop_state" in tools) { "cua-driver cannot capture the desktop" }
        if ("start_session" in tools) call("start_session", JsonObject(emptyMap()))
    }

    /** Captures until closed: new pictures every [intervalMillis], every [idleMillis] while nothing changes. */
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
                    show(shot.bytes)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // One slow or failed capture is not the end; three in a row are.
                if (++failures >= 3) throw error
                Log.w(TAG, "capture failed ($failures)", error)
            }
            withTimeoutOrNull(if (unchanged >= 3) idleMillis else intervalMillis) { nudges.receive() }
        }
    }

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
            received += image.data.length
            return Shot(bytes, null)
        }
        val line = withContext(Dispatchers.IO) { encode(wanted.jpegQuality, since) }.trim().lineSequence().lastOrNull().orEmpty()
        received += line.length
        val reply = Json.parseToJsonElement(line).jsonObject
        reply.string("error")?.let { error("snapshot encode failed: $it") }
        if (reply["same"]?.jsonPrimitive?.booleanOrNull == true) return null
        val data = requireNotNull(reply.string("data")) { "snapshot encode sent no picture" }
        return Shot(Base64.decode(data, Base64.DEFAULT), reply.string("digest"))
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
        nudges.trySend(Unit)
    }

    override fun setPaused(paused: Boolean) {
        this.paused.value = paused
        if (!paused) nudges.trySend(Unit)
    }

    override fun setMaxFps(fps: Int) {} // Pictures come at their own pace.

    override fun pointer(x: Int, y: Int, buttons: Int) {
        input.pointer(x, y, buttons).forEach { actions.trySend(it) }
    }

    override fun key(keysym: Int, down: Boolean) {
        input.key(keysym, down)?.let { actions.trySend(it) }
    }

    /** cua-driver types any language as-is, so text never needs the clipboard. */
    override fun typeText(text: String): Boolean {
        if (text.isNotEmpty()) actions.trySend(SnapshotAction.Type(text))
        return true
    }

    override fun sendClipboard(text: String) {
        actions.trySend(SnapshotAction.Clipboard(text))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job?.cancel()
        if (job == null) release()
        if (_state.value !is RemoteScreenState.Closed) _state.value = RemoteScreenState.Closed(null)
    }

    private val released = AtomicBoolean()

    /** Closes the process and the screen channel; the MCP client is closed by the capture job that opened it. */
    private fun release() {
        if (!released.compareAndSet(false, true)) return
        actions.close()
        runCatching { process.close() }
        runCatching { onClose() }
    }

    private companion object {
        const val ACTION_SETTLE_MILLIS = 350L
    }
}

private fun CallToolResult.text() = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
