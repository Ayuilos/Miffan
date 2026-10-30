package me.rerere.cuaspace

import cua.env.v1.CloseMediaRequest
import cua.env.v1.CloseMediaResponse
import cua.env.v1.GeometryControl
import cua.env.v1.MediaCodec
import cua.env.v1.MediaTarget
import cua.env.v1.OpenMediaRequest
import cua.env.v1.OpenMediaResponse
import cua.env.v1.SessionPolicy
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** What the media socket reports, in arrival order. */
sealed interface SpaceMediaEvent {
    /** The session is attached; [width]×[height] is the frame size pointer coordinates refer to. */
    data class Opened(val sessionId: String, val width: Int, val height: Int) : SpaceMediaEvent

    /** One H.264 Annex B access unit; keyframes carry SPS/PPS. A new [codecEpoch] needs a decoder reset. */
    class Frame(
        val sequence: Long,
        val codecEpoch: Long,
        val width: Int,
        val height: Int,
        val keyframe: Boolean,
        val timestampUs: Long,
        val data: ByteArray,
    ) : SpaceMediaEvent

    /** The frame size changed (the desktop's resolution); input coordinates follow it. */
    data class Geometry(val width: Int, val height: Int) : SpaceMediaEvent

    /** Capture stopped ([reason] from the server) or resumed. */
    data class Suspended(val reason: String?) : SpaceMediaEvent
    data object Resumed : SpaceMediaEvent

    /** A control message this client does not act on, kept for diagnostics. */
    data class Other(val type: String, val payload: JsonElement?) : SpaceMediaEvent

    /** The socket ended: [code] is the WebSocket close code (4404 session gone, 4410 target gone…), or null on failure. */
    data class Closed(val code: Int?, val reason: String?, val error: Throwable? = null) : SpaceMediaEvent
}

/** One device-semantic input event of rcdp v2 `interactive_input`. Coordinates are 0..1 of the frame. */
sealed interface SpaceInput {
    data class Text(val text: String) : SpaceInput
    data class Key(val key: String, val down: Boolean, val modifiers: List<String> = emptyList(), val repeat: Boolean = false) : SpaceInput
    data class Pointer(val phase: String, val button: String?, val x: Double, val y: Double, val modifiers: List<String> = emptyList()) : SpaceInput
    data class Scroll(val x: Double, val y: Double, val deltaX: Double, val deltaY: Double) : SpaceInput
}

/**
 * A Space's desktop as video: `StreamService.OpenMedia` over gRPC, then the rcdp v2 `/media` WebSocket on
 * the same port (see proto/MEDIA.md). The token never goes on the socket, only the one-session ticket.
 * Events arrive on [events]; [send] queues input, which the server applies in order.
 */
class SpaceMedia internal constructor(
    private val client: SpaceClient,
    private val opened: OpenMediaResponse,
) : Closeable {
    private val closed = AtomicBoolean()
    private val _events = MutableSharedFlow<SpaceMediaEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<SpaceMediaEvent> = _events
    val sessionId: String get() = opened.media_session_id

    /** Next input sequence; the server adopts the first one it sees and corrects us on a gap. */
    private var nextSequence = 1L
    private var socket: WebSocket? = null

    internal fun attach() {
        val http = client.http.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            // The server pings every 20 s and closes after 60 s without a pong; OkHttp answers its pings.
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(client.baseUrl + opened.ws_path)
            .header("Sec-WebSocket-Protocol", "rcdp.v2")
            .build()
        socket = http.newWebSocket(request, Listener())
    }

    /** Queues [events] as one batch; false once the socket is gone. */
    @Synchronized fun send(events: List<SpaceInput>): Boolean {
        if (events.isEmpty()) return true
        val ws = socket ?: return false
        var sent = true
        for (chunk in events.chunked(MAX_EVENTS)) {
            val batch = buildJsonObject {
                put("type", "interactive_input")
                put("payload", buildJsonObject {
                    put("session_id", opened.media_session_id)
                    put("first_sequence", nextSequence)
                    put("events", JsonArray(chunk.map { it.toJson() }))
                })
            }
            nextSequence += chunk.size
            sent = ws.send(batch.toString()) && sent
        }
        return sent
    }

    /** Asks for a keyframe, e.g. after the decoder lost state or got a new surface. At most once a second. */
    fun requestKeyframe() {
        val now = System.nanoTime()
        if (now - lastKeyframeRequest < 1_000_000_000L) return
        lastKeyframeRequest = now
        control("request_keyframe", buildJsonObject { put("session_id", opened.media_session_id) })
    }
    @Volatile private var lastKeyframeRequest = 0L

    /**
     * Reports frame [sequence] decoded. Optional flow control (`frame_ack.v1`): with acks the server keeps
     * only about two seconds of frames unacknowledged, so a slow link drops frames instead of falling behind.
     */
    fun ack(sequence: Long) {
        control("frame_ack", buildJsonObject {
            put("session_id", opened.media_session_id)
            put("sequence", sequence)
        })
    }

    private fun control(type: String, payload: JsonObject) {
        socket?.send(buildJsonObject {
            put("type", type)
            put("payload", payload)
        }.toString())
    }

    /** Closes the socket; the session itself ends with [closeSession]. */
    override fun close() {
        if (closed.compareAndSet(false, true)) socket?.close(1000, null)
    }

    suspend fun closeSession() {
        close()
        runCatching {
            client.grpc.unary("cua.env.v1.StreamService/CloseMedia",
                CloseMediaRequest(media_session_id = opened.media_session_id), CloseMediaResponse.ADAPTER)
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val type = message["type"]?.jsonPrimitive?.contentOrNull ?: return
            val payload = message["payload"]
            when (type) {
                "session_opened" -> {
                    val size = payload?.frameSize()
                    val fallback = opened.geometry?.frame_size
                    _events.tryEmit(SpaceMediaEvent.Opened(opened.media_session_id,
                        size?.first ?: fallback?.width ?: 0, size?.second ?: fallback?.height ?: 0))
                }
                "lifecycle" -> lifecycle(payload)
                "error" -> {
                    // A gap in the input sequence: continue from what the server expects.
                    val expected = (payload as? JsonObject)?.get("expected_sequence")?.jsonPrimitive?.longOrNull
                    if (expected != null) synchronized(this@SpaceMedia) { nextSequence = expected }
                    _events.tryEmit(SpaceMediaEvent.Other(type, payload))
                }
                else -> _events.tryEmit(SpaceMediaEvent.Other(type, payload))
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            frame(bytes.asByteBuffer())?.let { _events.tryEmit(it) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _events.tryEmit(SpaceMediaEvent.Closed(code, reason.ifEmpty { null }))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _events.tryEmit(SpaceMediaEvent.Closed(response?.code, response?.message, t))
        }
    }

    private fun lifecycle(payload: JsonElement?) {
        val event = (payload as? JsonObject) ?: return
        val kind = event.string("event") ?: event.string("kind") ?: event.keys.firstOrNull()
        when (kind) {
            "geometry_changed" -> event.frameSize()?.let { (w, h) -> _events.tryEmit(SpaceMediaEvent.Geometry(w, h)) }
            "suspended" -> _events.tryEmit(SpaceMediaEvent.Suspended(event.string("reason")))
            "resumed" -> _events.tryEmit(SpaceMediaEvent.Resumed)
            else -> _events.tryEmit(SpaceMediaEvent.Other("lifecycle", payload))
        }
    }

    companion object {
        private const val MAX_EVENTS = 256
        private const val MAX_HEADER = 1 shl 20

        internal suspend fun open(client: SpaceClient, maxDimension: Int, maxFps: Int, bitrateKbps: Int): SpaceMedia {
            val response = client.grpc.unary("cua.env.v1.StreamService/OpenMedia", OpenMediaRequest(
                target = MediaTarget(display_id = "primary"),
                codecs = listOf(MediaCodec.MEDIA_CODEC_H264),
                max_fps = maxFps,
                max_dimension = maxDimension,
                bitrate_kbps = bitrateKbps,
                policy = SessionPolicy.SESSION_POLICY_ALLOW_ACTIVATION,
                geometry_control = GeometryControl.GEOMETRY_CONTROL_OBSERVE_ONLY,
            ), OpenMediaResponse.ADAPTER)
            check(response.ws_path.startsWith("/")) { "The Space returned no media socket" }
            return SpaceMedia(client, response).also { it.attach() }
        }

        /**
         * A length-prefixed video packet (`header_len`, `payload_len`, JSON header, payload). Audio packets
         * (first byte `R`, magic `RAU2`) and anything malformed are skipped: they must never close the socket.
         */
        internal fun frame(buffer: ByteBuffer): SpaceMediaEvent.Frame? {
            if (buffer.remaining() < 8 || buffer.get(buffer.position()) == 'R'.code.toByte()) return null
            val headerLength = buffer.int
            val payloadLength = buffer.int
            if (headerLength !in 1..MAX_HEADER || payloadLength < 0 || buffer.remaining() < headerLength + payloadLength) return null
            val headerBytes = ByteArray(headerLength).also(buffer::get)
            val header = runCatching { Json.parseToJsonElement(headerBytes.decodeToString()).jsonObject }.getOrNull() ?: return null
            // The header is a WireHeader: {"direction":"video","message":{…descriptor…}}.
            if (header.string("direction")?.let { it != "video" } == true) return null
            val descriptor = (header["message"] as? JsonObject) ?: header
            if (descriptor.string("codec")?.let { it != "h264" } == true) return null
            val data = ByteArray(payloadLength).also(buffer::get)
            return SpaceMediaEvent.Frame(
                sequence = descriptor.long("sequence") ?: 0,
                codecEpoch = descriptor.long("codec_epoch") ?: 0,
                width = descriptor.long("width_px")?.toInt() ?: 0,
                height = descriptor.long("height_px")?.toInt() ?: 0,
                keyframe = descriptor["keyframe"]?.jsonPrimitive?.booleanOrNull == true,
                timestampUs = descriptor.long("capture_timestamp_us") ?: 0,
                data = data,
            )
        }

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

        /** The frame size in a geometry object (`session_opened`, `geometry_changed`), wherever the server nests it. */
        private fun JsonElement.frameSize(): Pair<Int, Int>? {
            val obj = this as? JsonObject ?: return null
            val width = (obj["width_px"] as? JsonPrimitive)?.intOrNull
            val height = (obj["height_px"] as? JsonPrimitive)?.intOrNull
            if (width != null && height != null && width > 0 && height > 0) return width to height
            for (key in listOf("geometry", "frame_size", "size")) obj[key]?.frameSize()?.let { return it }
            return null
        }

        private fun SpaceInput.toJson(): JsonObject = buildJsonObject {
            when (val event = this@toJson) {
                is SpaceInput.Text -> {
                    put("kind", "text_commit")
                    put("text", event.text)
                }
                is SpaceInput.Key -> {
                    put("kind", "key")
                    put("key", event.key)
                    put("state", if (event.down) "down" else "up")
                    put("modifiers", JsonArray(event.modifiers.map(::JsonPrimitive)))
                    put("repeat", event.repeat)
                }
                is SpaceInput.Pointer -> {
                    put("kind", "pointer")
                    put("phase", event.phase)
                    put("button", event.button?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                    put("x_normalized", event.x)
                    put("y_normalized", event.y)
                    put("modifiers", JsonArray(event.modifiers.map(::JsonPrimitive)))
                }
                is SpaceInput.Scroll -> {
                    put("kind", "scroll")
                    put("x_normalized", event.x)
                    put("y_normalized", event.y)
                    put("delta_x", event.deltaX)
                    put("delta_y", event.deltaY)
                    put("phase", "none")
                    put("momentum_phase", "none")
                    put("precise", false)
                }
            }
        }
    }
}
