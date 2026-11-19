package me.rerere.workspace.relay

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import okio.ByteString
import okio.ByteString.Companion.toByteString

const val RELAY_STREAM_CONTENT_TYPE = "application/x-miffan-relay-v1"

data class RelayHttpResponse(
    val status: Int,
    val body: String,
)

interface RelayRawStream {
    /** Queues framed bytes for transmission; returns false if the stream is already closed. */
    fun send(bytes: ByteArray): Boolean

    /** Unflushed outbound bytes currently queued in memory. */
    fun queuedBytes(): Long

    /** Initiates teardown of the underlying HTTP stream. */
    fun close(code: Int = 1000, reason: String = "")
}

interface RelayRawStreamListener {
    fun onOpen(stream: RelayRawStream)
    fun onBytes(stream: RelayRawStream, data: ByteArray, off: Int, len: Int)
    fun onHttpError(stream: RelayRawStream, status: Int, body: String)
    fun onFailure(stream: RelayRawStream, error: Throwable)
    fun onClosed(stream: RelayRawStream)
}

/**
 * Pure-JVM abstraction for HTTP/3 (or HTTP/2) full-duplex framed streams, implemented in `:app`
 * using Cronet while keeping `:workspace` dependency-free on Android native artifacts.
 */
interface RelayStreamTransport {
    /** True when the underlying QUIC/HTTP3 engine can open requests immediately without blocking. */
    fun isAvailable(): Boolean

    /** Non-blocking best-effort warmup (for example downloading or initializing native libraries). */
    fun startWarmup() {}

    /**
     * Blocking/suspending-from-IO-thread initialization when a fallback engine is needed immediately
     * (e.g. TCP is blocked and Cronet needs to download on demand). Returns true when ready.
     */
    fun ensureReady(): Boolean = isAvailable()

    /** Unary HTTP GET (e.g. `/v1/info`) over the stream transport (preferring HTTP/3 / QUIC). */
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMillis: Int = 5_000): RelayHttpResponse

    /** Opens a full-duplex `POST` stream with `Content-Type: application/x-miffan-relay-v1`. */
    fun openStream(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMillis: Int,
        listener: RelayRawStreamListener,
    ): RelayRawStream
}

object RelayStreamCodec {
    const val TYPE_PRELUDE = 0x00
    const val TYPE_TEXT = 0x01
    const val TYPE_BINARY = 0x02
    const val TYPE_CLOSE = 0x08

    const val MAX_TEXT_FRAME = 4_096
    const val MAX_BINARY_FRAME = 65_536

    val PRELUDE = byteArrayOf(TYPE_PRELUDE.toByte(), 0, 0, 0, 0)

    fun encodeText(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        return pack(TYPE_TEXT, body, 0, body.size)
    }

    fun encodeBinary(bytes: ByteArray, offset: Int = 0, len: Int = bytes.size - offset): ByteArray =
        pack(TYPE_BINARY, bytes, offset, len)

    fun encodeClose(code: Int, reason: String): ByteArray {
        val raw = if (reason.isEmpty()) ByteArray(0) else reason.toByteArray(Charsets.UTF_8)
        var end = raw.size
        if (end > 123) {
            end = 123
            while (end > 0 && (raw[end].toInt() and 0xc0) == 0x80) end--
        }
        val body = ByteArray(2 + end)
        body[0] = ((code ushr 8) and 0xff).toByte()
        body[1] = (code and 0xff).toByte()
        if (end > 0) System.arraycopy(raw, 0, body, 2, end)
        return pack(TYPE_CLOSE, body, 0, body.size)
    }

    private fun pack(type: Int, body: ByteArray, offset: Int, len: Int): ByteArray {
        val out = ByteArray(5 + len)
        out[0] = type.toByte()
        out[1] = ((len ushr 24) and 0xff).toByte()
        out[2] = ((len ushr 16) and 0xff).toByte()
        out[3] = ((len ushr 8) and 0xff).toByte()
        out[4] = (len and 0xff).toByte()
        if (len > 0) System.arraycopy(body, offset, out, 5, len)
        return out
    }
}

class RelayStreamFramer {
    sealed interface Event {
        data object Prelude : Event
        data class Text(val data: String) : Event
        data class Binary(val data: ByteString) : Event
        data class Close(val code: Int, val reason: String) : Event
        data object Oversize : Event
        data class Invalid(val reason: String) : Event
    }

    private val hdr = ByteArray(5)
    private var hdrLen = 0
    private var frameType = 0
    private var frameLen = 0
    private var payload = ByteArray(0)
    private var payloadPos = 0
    private var halted = false

    val isHalted: Boolean get() = halted

    fun hasPartial(): Boolean = !halted && hdrLen > 0

    fun push(chunk: ByteArray, off: Int = 0, len: Int = chunk.size - off): List<Event> {
        val out = ArrayList<Event>(2)
        var pos = off
        val end = off + len

        while (pos < end && !halted) {
            if (hdrLen < 5) {
                val take = minOf(5 - hdrLen, end - pos)
                System.arraycopy(chunk, pos, hdr, hdrLen, take)
                hdrLen += take
                pos += take
                if (hdrLen < 5) break

                frameType = hdr[0].toInt() and 0xff
                val rawLen = ((hdr[1].toInt() and 0xff) shl 24) or
                    ((hdr[2].toInt() and 0xff) shl 16) or
                    ((hdr[3].toInt() and 0xff) shl 8) or
                    (hdr[4].toInt() and 0xff)
                // Guard unsigned lengths >= 2 GiB (negative signed Int) as well as > 65536.
                if (rawLen < 0) {
                    halted = true
                    out += Event.Oversize
                    break
                }
                frameLen = rawLen

                when (frameType) {
                    RelayStreamCodec.TYPE_PRELUDE -> {
                        if (frameLen != 0) {
                            halted = true
                            out += Event.Invalid("bad_prelude")
                            break
                        }
                        hdrLen = 0
                        out += Event.Prelude
                        continue
                    }
                    RelayStreamCodec.TYPE_TEXT -> {
                        if (frameLen > RelayStreamCodec.MAX_TEXT_FRAME) {
                            halted = true
                            out += Event.Oversize
                            break
                        }
                        if (frameLen == 0) {
                            hdrLen = 0
                            out += Event.Text("")
                            continue
                        }
                    }
                    RelayStreamCodec.TYPE_BINARY -> {
                        if (frameLen > RelayStreamCodec.MAX_BINARY_FRAME) {
                            halted = true
                            out += Event.Oversize
                            break
                        }
                        if (frameLen == 0) {
                            hdrLen = 0
                            out += Event.Binary(ByteString.EMPTY)
                            continue
                        }
                    }
                    RelayStreamCodec.TYPE_CLOSE -> {
                        if (frameLen > 125 || frameLen == 1) {
                            halted = true
                            out += Event.Invalid("bad_close")
                            break
                        }
                        if (frameLen == 0) {
                            halted = true
                            hdrLen = 0
                            out += Event.Close(1000, "")
                            break
                        }
                    }
                    else -> {
                        halted = true
                        out += Event.Invalid("bad_type")
                        break
                    }
                }

                payload = ByteArray(frameLen)
                payloadPos = 0
            }

            val take = minOf(frameLen - payloadPos, end - pos)
            System.arraycopy(chunk, pos, payload, payloadPos, take)
            payloadPos += take
            pos += take
            if (payloadPos < frameLen) break

            val body = payload
            val type = frameType
            hdrLen = 0
            frameLen = 0
            payload = EMPTY_BYTES
            payloadPos = 0

            when (type) {
                RelayStreamCodec.TYPE_BINARY -> out += Event.Binary(body.toByteString(0, body.size))
                RelayStreamCodec.TYPE_TEXT -> {
                    val decoded = decodeUtf8Strict(body, 0, body.size)
                    if (decoded == null) {
                        halted = true
                        out += Event.Invalid("invalid_utf8")
                        break
                    }
                    out += Event.Text(decoded)
                }
                RelayStreamCodec.TYPE_CLOSE -> {
                    val code = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
                    val reason = decodeUtf8Strict(body, 2, body.size - 2)
                    if (reason == null) {
                        halted = true
                        out += Event.Invalid("invalid_utf8")
                        break
                    }
                    halted = true
                    out += Event.Close(code, reason)
                    break
                }
            }
        }
        return out
    }

    private fun decodeUtf8Strict(bytes: ByteArray, off: Int, len: Int): String? {
        if (len == 0) return ""
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, off, len))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    companion object {
        private val EMPTY_BYTES = ByteArray(0)
    }
}
