package me.rerere.workspace.relay

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * One relay session as a [Socket], so JSch can run over it (PROTOCOL §5). [open] returns only after
 * the relay sent `ready` and the connector accepted this device (`ok`); from then on the streams
 * carry the SSH bytes.
 *
 * Liveness is the relay's text `ping`/`pong`, never SSH keepalives: the relay answers `ping`
 * without waking its state, while any SSH packet would. A `ping` goes out every [RelayTimings.pingMillis]
 * whatever else is sent. Anything heard from the relay counts as an answer; hearing nothing for
 * [RelayTimings.graceMillis] after a `ping` closes the socket, which JSch sees as end of stream.
 */
class RelaySocket private constructor(private val channel: RelayChannel, private val timings: RelayTimings) : Socket() {
    private val incoming = LinkedBlockingQueue<ByteString>()
    private val opened = CountDownLatch(1)
    private val failure = AtomicReference<IOException?>(null)
    @Volatile private var closed = false
    @Volatile private var soTimeoutMillis = 0
    /** When the relay last sent anything: a `pong`, or any frame of the session. */
    @Volatile private var lastHeardNanos = System.nanoTime()
    @Volatile private var pingSentNanos = 0L
    @Volatile private var bucket: TokenBucket? = null
    /** OkHttp queue depth at which writes wait; see [maxQueuedBytes]. */
    @Volatile private var maxQueued = DEFAULT_MAX_QUEUED
    private var pinger: ScheduledFuture<*>? = null
    @Volatile private var wire: Wire? = null

    private interface Wire {
        fun sendText(text: String): Boolean
        fun sendBinary(bytes: ByteArray, offset: Int, len: Int): Boolean
        fun queueSize(): Long
        fun close(code: Int, reason: String?)
    }

    private class WsWire(private val ws: WebSocket) : Wire {
        override fun sendText(text: String): Boolean = ws.send(text)
        override fun sendBinary(bytes: ByteArray, offset: Int, len: Int): Boolean =
            ws.send(bytes.toByteString(offset, len))
        override fun queueSize(): Long = ws.queueSize()
        override fun close(code: Int, reason: String?) {
            runCatching { ws.close(code, null) }
        }
    }

    private class StreamWire(private val stream: RelayRawStream) : Wire {
        @Volatile private var sentClose = false

        override fun sendText(text: String): Boolean = stream.send(RelayStreamCodec.encodeText(text))
        override fun sendBinary(bytes: ByteArray, offset: Int, len: Int): Boolean =
            stream.send(RelayStreamCodec.encodeBinary(bytes, offset, len))
        override fun queueSize(): Long = stream.queuedBytes()
        override fun close(code: Int, reason: String?) {
            if (!sentClose) {
                sentClose = true
                runCatching { stream.send(RelayStreamCodec.encodeClose(code, reason.orEmpty())) }
            }
            runCatching { stream.close(code, reason.orEmpty()) }
        }
    }

    /** The relay's `ready` message for this session; set once [open] returns. */
    lateinit var ready: RelayReady
        private set

    /** How long [open] took, from the WebSocket request to the connector's `ok`. */
    @Volatile var openMillis: Long = 0
        private set
    @Volatile private var pongWaiter: CountDownLatch? = null

    /**
     * One round trip to the relay itself (text `ping`, answered by the relay without waking its
     * state), in milliseconds, or null when no `pong` came within [timeoutMillis].
     */
    fun ping(timeoutMillis: Long = 5_000): Long? {
        val current = wire ?: return null
        val latch = CountDownLatch(1)
        pongWaiter = latch
        val start = System.nanoTime()
        if (!current.sendText("ping")) return null
        return if (latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) (System.nanoTime() - start) / 1_000_000 else null
    }

    /**
     * Pings now and closes the session (reason [RelayException.UNREACHABLE]) when nothing at all is
     * heard back within [timeoutMillis]; for example right after the phone changed networks, when
     * the old connection is usually dead but would take up to a minute to notice. True if alive.
     */
    fun checkAlive(timeoutMillis: Long = 3_000): Boolean {
        if (closed) return false
        val started = System.nanoTime()
        if (ping(timeoutMillis) != null || lastHeardNanos > started) return true
        if (closed) return false
        finish(IOException("relay did not answer after a network change"), 1000, RelayException.UNREACHABLE)
        return false
    }

    /** Close reason code from the relay or connector, when the session ended with one. */
    @Volatile var closeReason: String? = null
        private set

    /** Why the relay ended this session after it had opened; null while open or after a local close. */
    val closedByRelay: RelaySessionClosedException?
        get() = closeReason?.takeIf { closed && established }?.let { RelaySessionClosedException(it, channel) }

    /** Set once [open] has returned this socket. */
    @Volatile private var established = false

    /** When an SSH byte last crossed the session in either direction (the relay's idle clock). */
    @Volatile private var lastBinaryNanos = System.nanoTime()

    /** Milliseconds since an SSH byte last crossed this session; text pings do not count (PROTOCOL §5.3). */
    fun binaryQuietMillis(): Long = (System.nanoTime() - lastBinaryNanos) / 1_000_000

    private val closeListeners = java.util.concurrent.CopyOnWriteArrayList<(RelaySocket) -> Unit>()

    /** Runs [listener] once the session ends (at once if it already has); [closeReason] tells why. */
    fun addCloseListener(listener: (RelaySocket) -> Unit) {
        closeListeners += listener
        if (closed && closeListeners.remove(listener)) runCatching { listener(this) }
    }

    private val input = object : InputStream() {
        private var current: ByteString? = null
        private var offset = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val deadline = soTimeoutMillis.takeIf { it > 0 }?.let { System.nanoTime() + it * 1_000_000L }
            while (current == null || offset >= current!!.size) {
                val wait = deadline?.let { (it - System.nanoTime()) / 1_000_000L } ?: POLL_MILLIS
                if (deadline != null && wait <= 0) throw SocketTimeoutException("relay read timed out")
                val next = try {
                    incoming.poll(minOf(wait, POLL_MILLIS), TimeUnit.MILLISECONDS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("relay read interrupted")
                }
                if (next != null) {
                    if (next === EOF) {
                        incoming.offer(EOF)
                        return -1
                    }
                    current = next
                    offset = 0
                } else if (closed && incoming.isEmpty()) {
                    return -1
                }
            }
            val c = current!!
            val n = minOf(len, c.size - offset)
            c.copyInto(offset, b, off, n)
            offset += n
            return n
        }

        override fun available(): Int = (current?.size ?: 0) - offset
    }

    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var pos = off
            val end = off + len
            while (pos < end) {
                if (closed) throw failure.get() ?: IOException("relay session closed")
                val n = minOf(MAX_FRAME, end - pos)
                bucket?.take(n)
                // OkHttp buffers up to 16 MiB before failing; wait instead when SSH outruns the link.
                // A `ping` waits behind this queue, so it stays short (see maxQueuedBytes).
                val current = wire ?: throw failure.get() ?: IOException("relay session closed")
                while (current.queueSize() > maxQueued) {
                    if (closed) throw failure.get() ?: IOException("relay session closed")
                    Thread.sleep(5)
                }
                if (!current.sendBinary(b, pos, n)) throw failure.get() ?: IOException("relay send failed")
                lastBinaryNanos = System.nanoTime()
                pos += n
            }
        }
    }

    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun isConnected(): Boolean = !closed
    override fun isClosed(): Boolean = closed
    override fun getInetAddress(): InetAddress? = null
    override fun setTcpNoDelay(on: Boolean) = Unit
    override fun setSoTimeout(timeout: Int) { soTimeoutMillis = timeout }
    override fun getSoTimeout(): Int = soTimeoutMillis
    override fun setKeepAlive(on: Boolean) = Unit

    override fun close() {
        finish(null, 1000, null)
    }

    private fun finish(error: IOException?, code: Int, reason: String?) {
        if (closed) return
        if (error != null) failure.compareAndSet(null, error)
        if (reason != null) closeReason = reason
        closed = true
        pinger?.cancel(false)
        wire?.close(code, reason)
        incoming.offer(EOF)
        opened.countDown()
        closeListeners.toList().forEach { listener -> if (closeListeners.remove(listener)) runCatching { listener(this) } }
    }

    private fun sendText(text: String, via: Wire? = wire) {
        val target = via ?: throw failure.get() ?: IOException("relay send failed")
        if (!target.sendText(text)) throw failure.get() ?: IOException("relay send failed")
    }

    companion object {
        private val EOF = ByteString.EMPTY
        private const val POLL_MILLIS = 250L
        private const val MAX_FRAME = 64 * 1024
        private const val DEFAULT_MAX_QUEUED = 1L shl 20
        private const val MIN_MAX_QUEUED = 64L * 1024

        /**
         * About two seconds of the session's pace, at least 64 KiB. Text `ping`s share OkHttp's
         * queue with the SSH bytes, so a deep queue on a link slower than the pace delays them
         * past the grace period (a 2 MiB queue at 500 kbps is over 30 s), and lets a burst after a
         * network stall overrun the relay's rate supervision.
         */
        internal fun maxQueuedBytes(pace: RelayPace): Long =
            if (pace.kbps <= 0) DEFAULT_MAX_QUEUED else maxOf(MIN_MAX_QUEUED, pace.kbps * 1000L / 8 * 2)

        private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(ThreadFactory {
            Thread(it, "miffan-relay-ping").apply { isDaemon = true }
        })

        /**
         * Opens a session to [hostId] and completes the end-to-end handshake with [signer].
         * Throws [RelayException] with the protocol reason when the relay or connector refuses.
         */
        fun open(
            client: OkHttpClient,
            baseUrl: String,
            accessToken: String?,
            hostId: String,
            channel: RelayChannel,
            signer: RelayDeviceSigner,
            timeoutMillis: Long = 20_000,
            timings: RelayTimings = RelayTimings(),
        ): RelaySocket {
            require(RelayIds.isHostId(hostId)) { "Invalid relay host id" }
            val socket = RelaySocket(channel, timings)
            val started = System.nanoTime()
            val url = baseUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws") +
                "/v1/connect/$hostId?ch=${channel.wire}"
            val request = Request.Builder().url(url).apply {
                if (!accessToken.isNullOrEmpty()) header("Authorization", "Bearer $accessToken")
            }.build()
            val ws = client.newWebSocket(request, socket.WsListener(hostId, signer))
            socket.wire = WsWire(ws)
            return socket.awaitOpened(started, timeoutMillis, streamConnectTimeout = null)
        }

        /**
         * Opens a session over a bidirectional HTTP stream transport (preferring HTTP/3 / QUIC) and
         * completes the end-to-end handshake with [signer].
         */
        fun openStream(
            transport: RelayStreamTransport,
            baseUrl: String,
            accessToken: String?,
            hostId: String,
            channel: RelayChannel,
            signer: RelayDeviceSigner,
            timeoutMillis: Long = 20_000,
            connectTimeoutMillis: Int = 3_000,
            timings: RelayTimings = RelayTimings(),
        ): RelaySocket {
            require(RelayIds.isHostId(hostId)) { "Invalid relay host id" }
            val socket = RelaySocket(channel, timings)
            val started = System.nanoTime()
            val url = baseUrl.trimEnd('/') + "/v1/connect/$hostId?ch=${channel.wire}"
            val headers = if (!accessToken.isNullOrEmpty()) mapOf("Authorization" to "Bearer $accessToken") else emptyMap()
            val boundedConnect = minOf(connectTimeoutMillis.toLong(), timeoutMillis).toInt().coerceAtLeast(1)
            val streamListener = socket.StreamListener(hostId, signer)
            val raw = transport.openStream(url, headers, boundedConnect, streamListener)
            socket.wire = StreamWire(raw)
            return socket.awaitOpened(started, timeoutMillis, streamConnectTimeout = boundedConnect, streamListener = streamListener)
        }
    }

    private fun awaitOpened(
        started: Long,
        timeoutMillis: Long,
        streamConnectTimeout: Int? = null,
        streamListener: StreamListener? = null,
    ): RelaySocket {
        val finished = try {
            opened.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            close()
            Thread.currentThread().interrupt()
            throw InterruptedIOException("relay open interrupted")
        }
        if (!finished) {
            val preOpen = streamConnectTimeout != null && streamListener?.openSeen == false
            val err = if (preOpen) {
                RelayException(RelayException.UNREACHABLE, message = "Relay stream did not connect in time")
            } else {
                RelayException(RelayException.TIMEOUT, message = "Relay session did not open in time")
            }
            finish(err, 1000, null)
            throw err
        }
        failure.get()?.let { throw it }
        if (closed) throw RelayException(closeReason ?: RelayException.UNREACHABLE)
        openMillis = (System.nanoTime() - started) / 1_000_000
        established = true
        pinger = scheduler.scheduleWithFixedDelay(this::pingOrClose, timings.pingMillis, timings.pingMillis,
            TimeUnit.MILLISECONDS)
        return this
    }

    private fun pingOrClose() {
        if (closed) return
        val sent = pingSentNanos
        if (sent != 0L && lastHeardNanos < sent && System.nanoTime() - sent > timings.graceMillis * 1_000_000L) {
            finish(IOException("relay stopped answering"), 1000, RelayException.UNREACHABLE)
            return
        }
        pingSentNanos = System.nanoTime()
        runCatching { sendText("ping") }
    }

    private enum class Stage { READY, CHALLENGE, VERDICT, OPEN }

    private inner class SessionState(private val hostId: String, private val signer: RelayDeviceSigner) {
        @Volatile var stage = Stage.READY
            private set

        fun onText(text: String, via: Wire) {
            lastHeardNanos = System.nanoTime()
            if (text == "pong") {
                pongWaiter?.countDown()
                return
            }
            if (stage == Stage.OPEN) return // v1 carries no text after the handshake
            val message = runCatching { relayJson.decodeFromString<RelayText>(text) }.getOrNull()
            try {
                when {
                    stage == Stage.READY && message?.t == "ready" -> {
                        ready = relayJson.decodeFromString<RelayReady>(text)
                        if (ready.ch != channel.wire) throw RelayException("bad_request", message = "Relay opened another channel")
                        bucket = ready.pace.takeIf { it.kbps > 0 }?.let { TokenBucket(it.kbps, it.burstKb) }
                        maxQueued = maxQueuedBytes(ready.pace)
                        stage = Stage.CHALLENGE
                    }
                    stage == Stage.CHALLENGE && message?.t == "challenge" -> {
                        val nonce = message.nonce ?: throw RelayException("bad_request")
                        val payload = RelayIds.sessionSignaturePayload(hostId, ready.sid, channel, nonce)
                        val auth = RelayAuth(device = RelayIds.b64u(signer.publicKeySpki), sig = RelayIds.b64u(signer.sign(payload)))
                        stage = Stage.VERDICT
                        sendText(relayJson.encodeToString(auth), via)
                    }
                    stage == Stage.VERDICT && message?.t == "ok" -> {
                        stage = Stage.OPEN
                        lastHeardNanos = System.nanoTime()
                        opened.countDown()
                    }
                    stage == Stage.VERDICT && message?.t == "denied" ->
                        throw RelayException("denied", message = "This phone is not allowed on that computer (${message.reason})")
                    else -> Unit // unknown messages are ignored (PROTOCOL §10)
                }
            } catch (error: IOException) {
                finish(error, 1000, (error as? RelayException)?.reason)
            } catch (error: Throwable) {
                finish(IOException("relay handshake failed: $error", error), 1000, null)
            }
        }

        fun onBinary(bytes: ByteString) {
            lastHeardNanos = System.nanoTime()
            if (stage != Stage.OPEN) {
                finish(RelayException("bad_request", message = "Data before the handshake finished"), 4403, null)
                return
            }
            lastBinaryNanos = System.nanoTime()
            if (bytes.size > 0) incoming.offer(bytes)
        }

        fun onClosing(code: Int, reason: String) {
            val error = if (stage != Stage.OPEN) RelayException(reason.ifEmpty { RelayException.UNREACHABLE }) else null
            finish(error, 1000, reason.ifEmpty { null })
        }

        fun onFailure(t: Throwable, response: Response?) {
            finish(toRelayException(t, response), 1000, if (stage == Stage.OPEN) RelayException.UNREACHABLE else null)
        }

        fun onHttpFailure(status: Int, body: String) {
            finish(toRelayException(status, body), 1000, if (stage == Stage.OPEN) RelayException.UNREACHABLE else null)
        }
    }

    private inner class WsListener(hostId: String, signer: RelayDeviceSigner) : WebSocketListener() {
        private val state = SessionState(hostId, signer)

        override fun onMessage(webSocket: WebSocket, text: String) {
            state.onText(text, WsWire(webSocket))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            state.onBinary(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            state.onClosing(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            state.onFailure(t, response)
        }
    }

    private inner class StreamListener(hostId: String, signer: RelayDeviceSigner) : RelayRawStreamListener {
        private val state = SessionState(hostId, signer)
        private val framer = RelayStreamFramer()
        @Volatile var openSeen = false
            private set

        override fun onOpen(stream: RelayRawStream) {
            openSeen = true
            if (wire == null) wire = StreamWire(stream)
            if (!stream.send(RelayStreamCodec.PRELUDE)) {
                finish(RelayException(RelayException.UNREACHABLE, message = "Relay stream closed on open"), 1000, null)
            }
        }

        override fun onBytes(stream: RelayRawStream, data: ByteArray, off: Int, len: Int) {
            if (closed) return
            val sw = StreamWire(stream)
            for (ev in framer.push(data, off, len)) {
                when (ev) {
                    RelayStreamFramer.Event.Prelude -> Unit
                    is RelayStreamFramer.Event.Text -> state.onText(ev.data, sw)
                    is RelayStreamFramer.Event.Binary -> state.onBinary(ev.data)
                    is RelayStreamFramer.Event.Close -> {
                        state.onClosing(ev.code, ev.reason)
                        return
                    }
                    RelayStreamFramer.Event.Oversize -> {
                        finish(RelayException("frame_too_large"), 4413, "frame_too_large")
                        return
                    }
                    is RelayStreamFramer.Event.Invalid -> {
                        finish(RelayException("bad_request", message = ev.reason), 1008, "bad_request")
                        return
                    }
                }
                if (closed) return
            }
        }

        override fun onHttpError(stream: RelayRawStream, status: Int, body: String) {
            state.onHttpFailure(status, body)
        }

        override fun onFailure(stream: RelayRawStream, error: Throwable) {
            val ex = (error as? RelayException) ?: RelayException(
                RelayException.UNREACHABLE,
                message = error.message ?: "Relay unreachable",
                cause = error,
            )
            finish(ex, 1000, if (state.stage == Stage.OPEN) RelayException.UNREACHABLE else null)
        }

        override fun onClosed(stream: RelayRawStream) {
            val error = if (state.stage != Stage.OPEN) RelayException(RelayException.UNREACHABLE) else null
            finish(error, 1000, if (state.stage == Stage.OPEN) RelayException.UNREACHABLE else null)
        }
    }
}

/**
 * How often a session pings the relay, and how long it waits to hear anything back. The relay
 * treats a session that has not pinged for about 75 s as dead, so [pingMillis] stays well below it.
 */
data class RelayTimings(val pingMillis: Long = 30_000, val graceMillis: Long = 20_000) {
    init {
        require(pingMillis > 0 && graceMillis in 1 until pingMillis)
    }
}

internal fun toRelayException(status: Int, body: String, cause: Throwable? = null): RelayException {
    val error = runCatching { relayJson.decodeFromString<RelayError>(body) }.getOrNull()
    return if (error != null) {
        RelayException(error.reason, error.retryAfter, status, "Relay refused: ${error.reason}", cause)
    } else {
        RelayException(RelayException.PLATFORM, null, status, "Relay platform error HTTP $status", cause)
    }
}

/** Maps a failed WebSocket upgrade to a protocol reason; non-relay responses become [RelayException.PLATFORM]. */
internal fun toRelayException(t: Throwable, response: Response?): RelayException {
    if (response == null) {
        return RelayException(RelayException.UNREACHABLE, message = t.message ?: "Relay unreachable", cause = t)
    }
    val body = runCatching { response.peekBody(4096).string() }.getOrNull().orEmpty()
    return toRelayException(response.code, body, t)
}

/** Token bucket for the session's upstream pace. Blocks the writer when empty. */
internal class TokenBucket(kbps: Int, burstKb: Int) {
    private val bytesPerSecond = kbps * 1000.0 / 8
    private val capacity = maxOf(burstKb * 1024.0, 64 * 1024.0)
    private var tokens = capacity
    private var last = System.nanoTime()

    @Synchronized
    fun take(n: Int) {
        while (true) {
            val now = System.nanoTime()
            tokens = minOf(capacity, tokens + (now - last) / 1e9 * bytesPerSecond)
            last = now
            if (tokens >= n) {
                tokens -= n
                return
            }
            val waitMillis = ((n - tokens) / bytesPerSecond * 1000).toLong().coerceIn(1, 1000)
            Thread.sleep(waitMillis)
        }
    }
}
