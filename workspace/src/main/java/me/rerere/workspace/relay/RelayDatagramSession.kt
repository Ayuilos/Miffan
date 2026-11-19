package me.rerere.workspace.relay

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native unreliable UDP datagram transport for `ch=media-dgram` sessions (PROTOCOL §5.5).
 *
 * The companion WebSocket / HTTP stream [controlSocket] stays open for handshake, ping/pong, and
 * close signals; ending either side tears down the other.
 */
class RelayDatagramSession internal constructor(
    val controlSocket: RelaySocket,
    val udpHost: String,
    val udpPort: Int,
    clientToken: ByteArray,
    private val udpSocket: DatagramSocket,
    private val keepaliveIntervalMillis: Long = KEEPALIVE_INTERVAL_MILLIS,
) : Closeable {
    private val token = clientToken.copyOf()
    private val closed = AtomicBoolean(false)
    private val rxBuf = ByteArray(MAX_PACKET_SIZE)
    private val rxPacket = DatagramPacket(rxBuf, rxBuf.size)
    private val txLocal = ThreadLocal.withInitial { ByteArray(MAX_PACKET_SIZE) }
    private val schedulerLock = Any()
    private var scheduler: ScheduledExecutorService? = null

    val ready: RelayReady get() = controlSocket.ready
    val isClosed: Boolean get() = closed.get()

    init {
        require(token.size == TOKEN_SIZE) { "Client token must be $TOKEN_SIZE bytes" }
        require(udpPort in 1..65535) { "Invalid UDP port $udpPort" }
        try {
            if (!udpSocket.isConnected) {
                udpSocket.connect(InetSocketAddress(InetAddress.getByName(udpHost), udpPort))
            }
            runCatching { udpSocket.receiveBufferSize = SOCKET_BUFFER_BYTES }
            runCatching { udpSocket.sendBufferSize = SOCKET_BUFFER_BYTES }
        } catch (t: Throwable) {
            runCatching { udpSocket.close() }
            runCatching { controlSocket.close() }
            throw t
        }
        controlSocket.addCloseListener { close() }
    }

    /**
     * Verifies reachability to the relay UDP port using the zero-amplification `0xFF`/`0xFE` probe
     * (PROTOCOL §5.5), binding this phone's UDP address on the relay before media starts.
     */
    internal fun probe(timeoutMillis: Long = DEFAULT_PROBE_TIMEOUT_MILLIS): Boolean {
        if (closed.get()) return false
        val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val req = ByteArray(HEADER_SIZE + nonce.size)
        System.arraycopy(token, 0, req, 0, TOKEN_SIZE)
        req[TOKEN_SIZE] = FLOW_PROBE
        System.arraycopy(nonce, 0, req, HEADER_SIZE, nonce.size)

        val ackBuf = ByteArray(MAX_PACKET_SIZE)
        val ackPkt = DatagramPacket(ackBuf, ackBuf.size)
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(1) * 1_000_000L
        try {
            while (!closed.get()) {
                val now = System.nanoTime()
                if (now >= deadline) return false
                try {
                    udpSocket.send(DatagramPacket(req, req.size))
                } catch (_: PortUnreachableException) {
                    // Transient ICMP port unreachable while socket/path settles; retry until deadline.
                } catch (_: IOException) {
                    return false
                }
                val retryEnd = minOf(deadline, System.nanoTime() + PROBE_RETRY_MILLIS * 1_000_000L)
                while (!closed.get()) {
                    val waitMs = ((retryEnd - System.nanoTime()) / 1_000_000L).toInt()
                    if (waitMs <= 0) break
                    udpSocket.soTimeout = waitMs
                    ackPkt.length = ackBuf.size
                    try {
                        udpSocket.receive(ackPkt)
                    } catch (_: SocketTimeoutException) {
                        break
                    } catch (_: PortUnreachableException) {
                        continue
                    } catch (_: IOException) {
                        return false
                    }
                    if (ackPkt.length == req.size &&
                        matchesToken(ackBuf) &&
                        ackBuf[TOKEN_SIZE] == FLOW_ACK &&
                        matchesSlice(ackBuf, HEADER_SIZE, nonce)
                    ) {
                        startKeepalive()
                        return true
                    }
                }
            }
            return false
        } finally {
            runCatching { if (!udpSocket.isClosed) udpSocket.soTimeout = 0 }
        }
    }

    /** Sends one datagram on [flow] (`0x01`/`0x02`/`0x03`). Returns false if closed or oversized. */
    fun send(flow: Byte, payload: ByteArray, offset: Int = 0, length: Int = payload.size): Boolean {
        if (closed.get() || length <= 0 || length > MAX_PAYLOAD_SIZE) return false
        if (offset < 0 || offset + length > payload.size) return false
        val buf = txLocal.get()!!
        System.arraycopy(token, 0, buf, 0, TOKEN_SIZE)
        buf[TOKEN_SIZE] = flow
        System.arraycopy(payload, offset, buf, HEADER_SIZE, length)
        return try {
            udpSocket.send(DatagramPacket(buf, HEADER_SIZE + length))
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Reads the next media/control datagram into [outPayload], writing its flow byte to
     * [outFlow][0] and returning the payload byte length, or `-1` when closed. Single-reader only.
     */
    fun receive(outPayload: ByteArray, outFlow: ByteArray): Int {
        require(outFlow.isNotEmpty()) { "outFlow must have length >= 1" }
        while (!closed.get()) {
            rxPacket.length = rxBuf.size
            try {
                udpSocket.receive(rxPacket)
            } catch (_: PortUnreachableException) {
                continue
            } catch (_: IOException) {
                return -1
            }
            val n = rxPacket.length
            if (n < HEADER_SIZE || !matchesToken(rxBuf)) continue
            val flow = rxBuf[TOKEN_SIZE]
            if (flow != FLOW_VIDEO && flow != FLOW_CONTROL && flow != FLOW_AUDIO) continue
            val payloadLen = (n - HEADER_SIZE).coerceAtMost(outPayload.size)
            System.arraycopy(rxBuf, HEADER_SIZE, outPayload, 0, payloadLen)
            outFlow[0] = flow
            return payloadLen
        }
        return -1
    }

    private fun startKeepalive() {
        val interval = keepaliveIntervalMillis
        if (interval <= 0) return
        synchronized(schedulerLock) {
            if (closed.get() || scheduler != null) return
            val keepalive = ByteArray(HEADER_SIZE).also {
                System.arraycopy(token, 0, it, 0, TOKEN_SIZE)
                it[TOKEN_SIZE] = FLOW_PROBE
            }
            val exec = Executors.newSingleThreadScheduledExecutor(ThreadFactory { runnable ->
                Thread(runnable, "RelayDgramKeepalive").apply { isDaemon = true }
            })
            scheduler = exec
            exec.scheduleAtFixedRate({
                if (!closed.get()) {
                    runCatching { udpSocket.send(DatagramPacket(keepalive, keepalive.size)) }
                }
            }, interval, interval, TimeUnit.MILLISECONDS)
        }
    }

    private fun matchesToken(packet: ByteArray): Boolean {
        for (i in 0 until TOKEN_SIZE) {
            if (packet[i] != token[i]) return false
        }
        return true
    }

    private fun matchesSlice(packet: ByteArray, offset: Int, expected: ByteArray): Boolean {
        for (i in expected.indices) {
            if (packet[offset + i] != expected[i]) return false
        }
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(schedulerLock) {
            scheduler?.shutdownNow()
            scheduler = null
        }
        runCatching { udpSocket.close() }
        runCatching { controlSocket.close() }
    }

    companion object {
        const val TOKEN_SIZE = 16
        const val HEADER_SIZE = 17
        const val MAX_PACKET_SIZE = 2048
        const val MAX_PAYLOAD_SIZE = MAX_PACKET_SIZE - HEADER_SIZE

        const val FLOW_VIDEO: Byte = 0x01
        const val FLOW_CONTROL: Byte = 0x02
        const val FLOW_AUDIO: Byte = 0x03
        const val FLOW_ACK: Byte = 0xFE.toByte()
        const val FLOW_PROBE: Byte = 0xFF.toByte()

        const val KEEPALIVE_INTERVAL_MILLIS = 15_000L
        const val DEFAULT_PROBE_TIMEOUT_MILLIS = 2_500L
        private const val PROBE_RETRY_MILLIS = 250L
        private const val SOCKET_BUFFER_BYTES = 1 shl 20
    }
}
