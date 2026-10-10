package me.rerere.workspace.screen

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RemoteScreenPerfTest {
    private data class Request(val incremental: Boolean, val width: Int, val height: Int)

    /** Duplex fake server: may withhold the body independently of the message type byte. */
    private class Server(depth: Int = 2, jpeg: RfbJpegDecoder? = null) : Closeable {
        val clock = AtomicLong()
        val requests = LinkedBlockingQueue<Request>()
        val frames = LinkedBlockingQueue<Unit>()
        private val pipe = PipedInputStream(65536)
        private val server = DataOutputStream(PipedOutputStream(pipe))
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val sent = object : OutputStream() {
            override fun write(b: Int) = error("Expected buffered writes")
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len == 10 && b[off].toInt() == 3) {
                    val input = DataInputStream(ByteArrayInputStream(b, off + 1, len - 1))
                    val incremental = input.readUnsignedByte() == 1
                    assertEquals(0, input.readUnsignedShort())
                    assertEquals(0, input.readUnsignedShort())
                    requests.add(Request(incremental, input.readUnsignedShort(), input.readUnsignedShort()))
                }
            }
        }
        val session = RemoteScreenSession(pipe, sent, Closeable { server.close(); pipe.close() }, null, jpeg,
            RemoteScreenOptions(maxFps = 10, pipelineDepth = depth), object : RemoteScreenFrameSink {
                override fun onSize(width: Int, height: Int, scale: Int) {}
                override fun onPixels(rect: RfbRect, pixels: IntArray) {}
                override fun onFrameComplete() { frames.add(Unit) }
            }, clock::get)

        init {
            server.write("RFB 003.008\n".toByteArray())
            server.writeByte(1); server.writeByte(1); server.writeInt(0)
            server.writeShort(4); server.writeShort(2); server.write(ByteArray(16))
            server.writeInt(4); server.writeBytes("test"); server.flush()
            session.statsLogger = {}
            session.start(scope)
        }

        fun request(): Request = requests.poll(3, TimeUnit.SECONDS) ?: error("No request; state=${session.state.value}")
        fun frame() { assertNotNull("No frame; state=${session.state.value}", frames.poll(3, TimeUnit.SECONDS)) }
        fun header() { server.writeByte(0); server.flush() }
        fun body(encoding: Int = RfbClient.ENCODING_RAW, width: Int = 1, height: Int = 1) {
            server.writeByte(0); server.writeShort(1)
            server.writeShort(0); server.writeShort(0); server.writeShort(width); server.writeShort(height)
            server.writeInt(encoding)
            if (encoding == RfbClient.ENCODING_RAW) repeat(width * height) { server.writeInt(0) }
            if (encoding == RfbClient.ENCODING_TIGHT) { server.writeByte(0x90); server.writeByte(1); server.writeByte(42) }
            server.flush()
        }
        fun empty() { header(); server.writeByte(0); server.writeShort(0); server.flush() }
        fun bellAndClipboard() {
            server.writeByte(2); server.writeByte(3); server.write(ByteArray(3)); server.writeInt(1); server.writeByte(65)
            server.flush()
        }
        fun disconnect() { server.close() }
        override fun close() { session.close(); scope.cancel() }
    }

    @Test fun headerRequestsNextFrameBeforeBodyOrJpegDecode() {
        var decoded = false
        Server(jpeg = RfbJpegDecoder { _, _, _, _, _, _, _ -> decoded = true }).use { server ->
            assertEquals(Request(false, 4, 2), server.request())
            server.clock.set(100_000_000)
            server.header()
            assertEquals(Request(true, 4, 2), server.request())
            assertFalse(decoded)
            server.body(RfbClient.ENCODING_TIGHT)
            server.frame()
            assertTrue(decoded)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test fun pauseDrainsRepliesAndResumeRequestsWhileHeaderReadIsBlocked() {
        Server().use { server ->
            server.request()
            server.session.setPaused(true)
            server.clock.set(100_000_000)
            server.header(); server.body(); server.frame()
            assertTrue(server.requests.isEmpty())
            server.session.setPaused(false)
            assertEquals(Request(true, 4, 2), server.request())
        }
    }

    @Test fun serialDepthDoesNotRequestBeforeDecodeAndUpload() {
        Server(depth = 1).use { server ->
            server.request()
            server.clock.set(100_000_000)
            server.header()
            // Body is withheld: a serial session cannot send another request.
            assertNull(server.requests.poll(100, TimeUnit.MILLISECONDS))
            server.body(); server.frame()
            assertEquals(Request(true, 4, 2), server.request())
        }
    }

    @Test fun resizeDrainsOldReplyThenRequestsFullNewSize() {
        Server().use { server ->
            server.request()
            server.clock.set(100_000_000)
            server.header(); server.request()
            server.body(RfbClient.ENCODING_DESKTOP_SIZE, 8, 3); server.frame()
            assertTrue(server.requests.isEmpty())
            server.clock.set(200_000_000)
            server.empty(); server.frame()
            assertEquals(Request(false, 8, 3), server.request())
        }
    }

    @Test fun mergedResizeReplyUsesFreeSlotForFullRefreshAfterBoundedDrain() {
        Server().use { server ->
            server.request()
            server.clock.set(100_000_000)
            server.header(); server.request()
            server.body(RfbClient.ENCODING_DESKTOP_SIZE, 8, 3); server.frame()
            // The old incremental request was coalesced with the resize reply.
            server.clock.set(2_200_000_000)
            server.session.setMaxFps(10) // wake the reader without fabricating a reply
            assertEquals(Request(false, 8, 3), server.request())
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test fun pacerCapsSilentServerAndPairsMergedCursorOrEmptyRepliesByFifo() {
        val pacer = ScreenRequestPacer(2)
        assertEquals(false, pacer.request(0, 10, false))
        assertEquals(true, pacer.request(10_000_000_000, 10, false)) // fill only the second slot
        repeat(50) { assertNull(pacer.request(20_000_000_000, 10, false, resume = true)) }
        assertEquals(2, pacer.outstanding)
        assertEquals(21_000_000_000, pacer.latency(21_000_000_000))
        pacer.complete() // server merged two requests into one reply
        assertEquals(1, pacer.outstanding)
        assertEquals(true, pacer.request(21_000_000_000, 10, false, atHeader = true))
        assertEquals(2, pacer.outstanding)
        repeat(50) { i ->
            pacer.complete() // one reply may represent multiple requests
            assertEquals(true, pacer.request(22_000_000_000 + i * 1_000_000_000L, 10, false))
            assertEquals(2, pacer.outstanding)
        }
        pacer.complete() // empty or cursor-only reply
        assertEquals(1, pacer.outstanding)
        assertNull(pacer.request(71_000_000_001, 10, false, atHeader = true)) // FPS cap
        pacer.complete(); pacer.complete()
        assertEquals(0, pacer.outstanding)
        assertNull(pacer.request(72_000_000_000, 10, true))
    }

    @Test fun fixedWindowReportsFrameRateBytesEncodingsAndExpiresIdleSamples() {
        val window = ScreenStatsWindow(0, 0)
        fun add(at: Long, bytes: Long, displayed: Boolean, pixels: Long, vararg encodings: Int) {
            window.add(ScreenStatsWindow.Update(at, bytes, 10_000_000, 20_000_000, 30_000_000,
                4_000_000, 5_000_000, pixels, displayed, encodings.toSet()))
        }
        add(100_000_000, 20, true, 1, RfbClient.ENCODING_RAW)
        add(200_000_000, 18, true, 2, RfbClient.ENCODING_TIGHT, RfbClient.ENCODING_TIGHT_JPEG)
        add(300_000_000, 4, false, 0)
        add(400_000_000, 16, false, 0, RfbClient.ENCODING_CURSOR)
        val snapshot = window.snapshot(2_000_000_000, 58, 2, RfbPixelFormat.RGB565, 1)
        assertEquals(1.0, snapshot.fps, 0.0)
        assertEquals(29.0, snapshot.bytesPerSecond, 0.0)
        assertEquals(14.5, snapshot.avgUpdateBytes, 0.0)
        assertEquals(20, snapshot.maxUpdateBytes)
        assertEquals(2, snapshot.emptyUpdates)
        assertEquals(setOf("Raw", "Tight", "TightJPEG", "Cursor"), snapshot.encodings)
        assertEquals(10.0, snapshot.avgLatencyMillis, 0.0)
        assertEquals(20.0, snapshot.avgReadMillis, 0.0)
        assertEquals(30.0, snapshot.avgDecodeMillis, 0.0)
        assertEquals(4.0, snapshot.avgScaleMillis, 0.0)
        assertEquals(5.0, snapshot.avgSinkMillis, 0.0)
        assertEquals(0.75, snapshot.avgChangedPixels, 0.0)
        assertEquals(2, snapshot.scale)
        assertEquals(RfbPixelFormat.RGB565, snapshot.pixelFormat)
        assertEquals(1, snapshot.outstandingRequests)
        val idle = window.snapshot(4_000_000_000, 58, 2, RfbPixelFormat.RGB565, 1)
        assertEquals(0.0, idle.fps, 0.0)
        assertEquals(0.0, idle.bytesPerSecond, 0.0)
        assertTrue(idle.encodings.isEmpty())
    }

    @Test fun sessionStatsCountOnlyPixelUpdatesAndExcludeOtherMessagesFromUpdateBytes() = runBlocking {
        Server().use { server ->
            server.request()
            server.clock.set(100_000_000)
            server.header(); server.request(); server.body(); server.frame() // 20 bytes
            server.clock.set(200_000_000)
            server.bellAndClipboard() // 10 bytes, no acknowledgement
            server.header(); server.request()
            // empty reply also advances FIFO and refills the pipeline
            server.body(RfbClient.ENCODING_CURSOR, 0, 0); server.frame() // 16 bytes
            server.clock.set(300_000_000)
            server.empty(); server.frame(); server.request() // 4 bytes
            server.clock.set(2_000_000_000)
            server.session.setMaxFps(10)
            val stats = withTimeout(3_000) { server.session.stats.first { it.encodings.isNotEmpty() } }
            assertEquals(0.5, stats.fps, 0.0)
            assertEquals(25.0, stats.bytesPerSecond, 0.0)
            assertEquals(40.0 / 3, stats.avgUpdateBytes, 0.0)
            assertEquals(20, stats.maxUpdateBytes)
            assertEquals(2, stats.emptyUpdates)
            assertEquals(setOf("Raw", "Cursor"), stats.encodings)
            assertEquals(100.0, stats.avgLatencyMillis, 0.0)
            assertTrue(stats.outstandingRequests in 1..2)
        }
    }

    @Test fun headerReadFailureIsReportedAndSessionCloses() = runBlocking {
        Server().use { server ->
            server.request()
            server.disconnect()
            val state = withTimeout(3_000) { server.session.state.first { it is RemoteScreenState.Closed } }
            assertNotNull((state as RemoteScreenState.Closed).error)
        }
    }

    @Test fun jpegTimingExcludesInterleavedTransportReads() {
        var time = 0L
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write("RFB 003.008\n".toByteArray()); writeByte(1); writeByte(1); writeInt(0)
            writeShort(1); writeShort(1); write(ByteArray(16)); writeInt(0)
            writeByte(0); writeByte(0); writeShort(1)
            writeShort(0); writeShort(0); writeShort(1); writeShort(1); writeInt(RfbClient.ENCODING_TIGHT)
            writeByte(0x90); writeByte(1); writeByte(42)
        }
        val stream = object : ByteArrayInputStream(bytes.toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                time += 7
                return super.read(b, off, minOf(len, 1))
            }
        }
        val client = RfbClient(stream, ByteArrayOutputStream(), null,
            RfbJpegDecoder { _, _, _, _, _, _, _ -> time += 50 }, nanoTime = { time })
        client.handshake()
        val readBefore = client.readNanos
        val event = client.readMessage() as RfbEvent.FramebufferUpdated
        assertTrue(client.readNanos > readBefore)
        assertEquals(50, client.decodeNanos)
        assertEquals(setOf(RfbClient.ENCODING_TIGHT), event.encodings)
        assertTrue(event.hasTightJpeg)
    }

    @Test fun bufferedHeaderUsesArrivalTimeRatherThanDelayedConsumptionTime() {
        var time = 0L
        val stream = object : ByteArrayInputStream(byteArrayOf(2, 2)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { time += 7; return super.read(b, off, len) }
        }
        val client = RfbClient(stream, ByteArrayOutputStream(), null, nanoTime = { time })
        assertEquals(7, client.readMessageHeader().receivedNanos)
        time += 100 // previous frame's decode/upload
        assertEquals(7, client.readMessageHeader().receivedNanos)
        assertEquals(2, client.bytesReceived)
        assertEquals(2, client.bytesRead)
    }

    @Test fun transportTimingCountsUnderlyingReadsOnce() {
        var time = 0L
        val stream = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { time += 7; return super.read(b, off, len) }
        }
        val timed = TimedInputStream(stream) { time }
        val input = DataInputStream(java.io.BufferedInputStream(timed))
        assertEquals(1, input.readUnsignedByte())
        assertEquals(2, input.readUnsignedByte())
        assertEquals(3, input.readUnsignedByte())
        assertEquals(7, timed.readNanos)
    }
}
