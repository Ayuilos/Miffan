package me.rerere.stream

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock

internal interface DatagramPairOps {
    fun createPair(): IntArray?
    fun send(fd: Int, data: ByteArray, offset: Int, length: Int, nonBlocking: Boolean): Int
    fun recv(fd: Int, buffer: ByteArray): Int
    fun shutdown(fd: Int)
    fun close(fd: Int)
}

internal object NativeDatagramPairOps : DatagramPairOps {
    override fun createPair(): IntArray? = StreamNative.createDatagramPair()
    override fun send(fd: Int, data: ByteArray, offset: Int, length: Int, nonBlocking: Boolean): Int =
        StreamNative.sendDatagram(fd, data, offset, length, nonBlocking)
    override fun recv(fd: Int, buffer: ByteArray): Int = StreamNative.recvDatagram(fd, buffer)
    override fun shutdown(fd: Int) = StreamNative.shutdownFd(fd)
    override fun close(fd: Int) = StreamNative.closeFd(fd)
}

/**
 * Bridges native moonlight-common-c / ENet UDP fds (backed by AF_UNIX SOCK_SEQPACKET socketpairs)
 * with a multiplexed [StreamUdpTransport] keyed by flow byte (`0x01` video, `0x02` control, `0x03` audio).
 */
internal class UdpBridge(
    private val transport: StreamUdpTransport,
    private val ops: DatagramPairOps = NativeDatagramPairOps,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val channels = ConcurrentHashMap<Byte, Channel>()
    private val inboundThread: Thread

    private class Channel(
        val flow: Byte,
        val bridgeFd: Int,
        private val ops: DatagramPairOps,
    ) {
        val lock = ReentrantReadWriteLock()
        val closed = AtomicBoolean(false)
        private val fdClosed = AtomicBoolean(false)
        @Volatile var worker: Thread? = null

        fun deliverNonBlocking(buf: ByteArray, length: Int) {
            val read = lock.readLock()
            read.lock()
            try {
                if (!closed.get()) {
                    ops.send(bridgeFd, buf, 0, length, nonBlocking = true)
                }
            } finally {
                read.unlock()
            }
        }

        fun shutdownOnly() {
            if (closed.compareAndSet(false, true)) {
                ops.shutdown(bridgeFd)
            }
        }

        fun closeFdOnce() {
            shutdownOnly()
            val write = lock.writeLock()
            write.lock()
            try {
                if (fdClosed.compareAndSet(false, true)) {
                    ops.close(bridgeFd)
                }
            } finally {
                write.unlock()
            }
        }

        fun closeAndJoin(joinTimeoutMillis: Long) {
            shutdownOnly()
            val t = worker
            if (t != null && t !== Thread.currentThread()) {
                runCatching { t.join(joinTimeoutMillis) }
            }
            closeFdOnce()
        }
    }

    init {
        inboundThread = Thread({
            val buf = ByteArray(MAX_DATAGRAM_BYTES)
            val flowOut = ByteArray(1)
            try {
                while (!closed.get()) {
                    val n = try {
                        transport.receive(buf, flowOut)
                    } catch (_: Throwable) {
                        -1
                    }
                    if (n < 0) {
                        resetChannels()
                        return@Thread
                    }
                    if (n == 0) continue
                    channels[flowOut[0]]?.deliverNonBlocking(buf, n)
                }
            } finally {
                if (closed.get()) resetChannels()
            }
        }, "StreamUdpIn").apply {
            isDaemon = true
            start()
        }
    }

    fun open(flow: Byte): Int {
        if (closed.get()) return -1
        channels.remove(flow)?.closeAndJoin(JOIN_TIMEOUT_MILLIS)
        val pair = ops.createPair() ?: return -1
        if (pair.size != 2 || pair[0] < 0 || pair[1] < 0) {
            if (pair.size >= 1 && pair[0] >= 0) ops.close(pair[0])
            if (pair.size >= 2 && pair[1] >= 0) ops.close(pair[1])
            return -1
        }
        val nativeFd = pair[0]
        val bridgeFd = pair[1]
        val ch = Channel(flow, bridgeFd, ops)
        channels.put(flow, ch)?.closeAndJoin(JOIN_TIMEOUT_MILLIS)

        val worker = Thread({
            val buf = ByteArray(MAX_DATAGRAM_BYTES)
            try {
                while (!closed.get() && !ch.closed.get()) {
                    val n = ops.recv(bridgeFd, buf)
                    if (n <= 0) break
                    if (!transport.send(flow, buf, 0, n)) break
                }
            } catch (_: Throwable) {
            } finally {
                channels.remove(flow, ch)
                ch.closeFdOnce()
            }
        }, "StreamUdpOut-${flow.toInt() and 0xff}")
        ch.worker = worker
        worker.isDaemon = true
        worker.start()

        if (closed.get()) {
            channels.remove(flow, ch)
            ch.closeAndJoin(JOIN_TIMEOUT_MILLIS)
            ops.close(nativeFd)
            return -1
        }
        return nativeFd
    }

    /** Tears down active native flow channels while keeping [transport] alive for codec retry. */
    fun resetChannels() {
        val snapshot = channels.values.toList()
        snapshot.forEach { ch ->
            channels.remove(ch.flow, ch)
            ch.shutdownOnly()
        }
        snapshot.forEach { it.closeAndJoin(JOIN_TIMEOUT_MILLIS) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { transport.close() }
        resetChannels()
        if (Thread.currentThread() !== inboundThread) {
            runCatching { inboundThread.join(JOIN_TIMEOUT_MILLIS) }
        }
    }

    private companion object {
        const val MAX_DATAGRAM_BYTES = 2048
        const val JOIN_TIMEOUT_MILLIS = 2_000L
    }
}
