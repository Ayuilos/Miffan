package me.rerere.stream

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpBridgeTest {
    private class FakePairOps : DatagramPairOps {
        private val nextFd = AtomicInteger(100)
        private val closedFds = ConcurrentHashMap.newKeySet<Int>()
        private val bridgeToNative = ConcurrentHashMap<Int, LinkedBlockingQueue<ByteArray>>()
        private val nativeToBridge = ConcurrentHashMap<Int, LinkedBlockingQueue<ByteArray>>()
        val createdCount = AtomicInteger()
        val lastNonBlockingFlags = CopyOnWriteArrayList<Boolean>()
        val closedOrder = CopyOnWriteArrayList<Int>()

        override fun createPair(): IntArray {
            val nativeFd = nextFd.getAndAdd(2)
            val bridgeFd = nativeFd + 1
            bridgeToNative[bridgeFd] = LinkedBlockingQueue()
            nativeToBridge[bridgeFd] = LinkedBlockingQueue()
            createdCount.incrementAndGet()
            return intArrayOf(nativeFd, bridgeFd)
        }

        override fun send(fd: Int, data: ByteArray, offset: Int, length: Int, nonBlocking: Boolean): Int {
            lastNonBlockingFlags += nonBlocking
            if (closedFds.contains(fd)) return -1
            bridgeToNative[fd]?.offer(data.copyOfRange(offset, offset + length))
            return length
        }

        override fun recv(fd: Int, buffer: ByteArray): Int {
            if (closedFds.contains(fd)) return -1
            val q = nativeToBridge[fd] ?: return -1
            val msg = q.take()
            if (msg.isEmpty() || closedFds.contains(fd)) return -1
            val n = minOf(msg.size, buffer.size)
            System.arraycopy(msg, 0, buffer, 0, n)
            return n
        }

        override fun shutdown(fd: Int) {
            if (closedFds.add(fd)) {
                nativeToBridge[fd]?.offer(ByteArray(0))
            }
        }

        override fun close(fd: Int) {
            closedOrder += fd
            closedFds.add(fd)
            nativeToBridge[fd]?.offer(ByteArray(0))
        }

        fun pushFromNative(bridgeFd: Int, payload: ByteArray) {
            nativeToBridge[bridgeFd]!!.offer(payload)
        }

        fun pollDeliveredToNative(bridgeFd: Int, timeoutMs: Long = 1000): ByteArray? =
            bridgeToNative[bridgeFd]?.poll(timeoutMs, TimeUnit.MILLISECONDS)

        fun isShutdown(fd: Int): Boolean = closedFds.contains(fd)
    }

    private class FakeTransport : StreamUdpTransport {
        val sent = LinkedBlockingQueue<Pair<Byte, ByteArray>>()
        val inbound = LinkedBlockingQueue<Pair<Byte, ByteArray>>()
        val closed = AtomicBoolean(false)

        override fun send(flow: Byte, payload: ByteArray, offset: Int, length: Int): Boolean {
            if (closed.get()) return false
            sent.offer(flow to payload.copyOfRange(offset, offset + length))
            return true
        }

        override fun receive(buffer: ByteArray, flowOut: ByteArray): Int {
            val item = inbound.take()
            if (closed.get() || item.second.isEmpty()) return -1
            flowOut[0] = item.first
            val n = minOf(item.second.size, buffer.size)
            System.arraycopy(item.second, 0, buffer, 0, n)
            return n
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                inbound.offer(0.toByte() to ByteArray(0))
            }
        }
    }

    @Test fun demuxesFlowsNonBlockingForwardsOutboundAndSurvivesReset() {
        val ops = FakePairOps()
        val transport = FakeTransport()
        val bridge = UdpBridge(transport, ops)

        try {
            val vidNative = bridge.open(1)
            val ctrlNative = bridge.open(2)
            val audNative = bridge.open(3)
            val vidBridge = vidNative + 1
            val ctrlBridge = ctrlNative + 1
            val audBridge = audNative + 1

            // Inbound routing by flow uses nonBlocking=true.
            transport.inbound.offer(1.toByte() to "video-1".toByteArray())
            transport.inbound.offer(2.toByte() to "ctrl-1".toByteArray())
            transport.inbound.offer(3.toByte() to "audio-1".toByteArray())

            assertArrayEquals("video-1".toByteArray(), ops.pollDeliveredToNative(vidBridge))
            assertArrayEquals("ctrl-1".toByteArray(), ops.pollDeliveredToNative(ctrlBridge))
            assertArrayEquals("audio-1".toByteArray(), ops.pollDeliveredToNative(audBridge))
            assertTrue(ops.lastNonBlockingFlags.isNotEmpty() && ops.lastNonBlockingFlags.all { it })

            // Outbound native -> transport.
            ops.pushFromNative(ctrlBridge, "enet-syn".toByteArray())
            val out = transport.sent.poll(1, TimeUnit.SECONDS)!!
            assertEquals(2.toByte(), out.first)
            assertArrayEquals("enet-syn".toByteArray(), out.second)

            // resetChannels shuts down active channels while keeping transport alive for retry.
            bridge.resetChannels()
            assertTrue(ops.isShutdown(vidBridge))
            assertTrue(ops.isShutdown(ctrlBridge))
            assertTrue(ops.isShutdown(audBridge))
            assertFalse(transport.closed.get())

            val retryCtrlNative = bridge.open(2)
            val retryCtrlBridge = retryCtrlNative + 1
            transport.inbound.offer(2.toByte() to "retry-ctrl".toByteArray())
            assertArrayEquals("retry-ctrl".toByteArray(), ops.pollDeliveredToNative(retryCtrlBridge))
        } finally {
            bridge.close()
            assertTrue(transport.closed.get())
        }
    }

    @Test fun transportEofResetsChannelsWithoutCallingTransportClose() {
        val ops = FakePairOps()
        val closedFlag = AtomicBoolean(false)
        val eofLatch = CountDownLatch(1)
        val transport = object : StreamUdpTransport {
            override fun send(flow: Byte, payload: ByteArray, offset: Int, length: Int) = false
            override fun receive(buffer: ByteArray, flowOut: ByteArray): Int {
                eofLatch.await(2, TimeUnit.SECONDS)
                return -1
            }
            override fun close() { closedFlag.set(true) }
        }
        val bridge = UdpBridge(transport, ops)
        try {
            val fd = bridge.open(2)
            val bridgeFd = fd + 1
            eofLatch.countDown()
            val deadline = System.currentTimeMillis() + 1000
            while (!ops.isShutdown(bridgeFd) && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(ops.isShutdown(bridgeFd))
            assertFalse(closedFlag.get())
        } finally {
            bridge.close()
        }
    }
}
