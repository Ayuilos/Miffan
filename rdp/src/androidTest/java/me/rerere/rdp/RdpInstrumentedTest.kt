package me.rerere.rdp

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.rerere.workspace.screen.RemoteScreenFrameSink
import me.rerere.workspace.screen.RemoteScreenState
import me.rerere.workspace.screen.RfbRect
import me.rerere.workspace.screen.RfbKeys
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RdpInstrumentedTest {
    private val noOpSink = object : RemoteScreenFrameSink {
        override fun onSize(width: Int, height: Int, scale: Int) {}
        override fun onPixels(rect: RfbRect, pixels: IntArray) {}
        override fun onFrameComplete() {}
    }

    @Test fun closeBeforeStartIsIdempotent() {
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = RdpSession(
            java.io.ByteArrayInputStream(byteArrayOf()), java.io.ByteArrayOutputStream(),
            java.io.Closeable { closes.incrementAndGet() }, RdpCredentials("unused", "unused"),
            RdpOptions(), noOpSink,
        )
        session.close(); session.close()
        assertEquals(1, closes.get())
        assertTrue(session.state.value is RemoteScreenState.Closed)
        assertThrows(IllegalStateException::class.java) { session.start(scope) }
        scope.cancel()
    }

    @Test fun cancellingDuringHandshakeStopsThreads() {
        val released = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val input = object : java.io.InputStream() {
            override fun read(): Int { released.await(); return -1 }
        }
        val session = RdpSession(
            input, java.io.ByteArrayOutputStream(), java.io.Closeable { released.countDown() },
            RdpCredentials("unused", "unused"), RdpOptions(), noOpSink,
        )
        session.start(scope)
        Thread.sleep(150)
        scope.cancel()
        assertTrue("Handshake cancellation leaked a thread", session.awaitStopped(10000))
        assertTrue(session.state.value is RemoteScreenState.Closed)
    }

    @Test fun streamHandshakeFrameAndInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val desktop = requireNotNull(args.getString("active")) { "Pass .deps/rdp-test.properties through instrumentation arguments" }
        val socket = Socket().apply { connect(InetSocketAddress(args.getString("host", "10.0.2.2"), requireNotNull(args.getString("$desktop.port")).toInt()), 10000) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val frame = CountDownLatch(1)
        val lock = Any()
        var width = 0
        var height = 0
        var pixels = IntArray(0)
        var frames = 0
        val sink = object : RemoteScreenFrameSink {
            override fun onSize(w: Int, h: Int, scale: Int) { synchronized(lock) {
                assertEquals(1, scale); width = w; height = h; pixels = IntArray(w*h)
            } }
            override fun onPixels(rect: RfbRect, data: IntArray) { synchronized(lock) {
                for (row in 0 until rect.height) data.copyInto(pixels, (rect.y+row)*width+rect.x, row*rect.width, (row+1)*rect.width)
            } }
            override fun onFrameComplete() { synchronized(lock) {
                frames++
                if (frame.count > 0 && pixels.any { (it and 0xffffff) != 0 }) frame.countDown()
            } }
        }
        val credentials = RdpCredentials(requireNotNull(args.getString("$desktop.username")), org.json.JSONObject(File(instrumentation.targetContext.cacheDir, "rdp-test-credentials.json").readText()).getString("$desktop.password"))
        val security = when (args.getString("$desktop.security")) { "tls" -> RdpSecurity.TLS; "nla" -> RdpSecurity.NLA; else -> RdpSecurity.AUTO }
        val session = RdpSession(socket.getInputStream(), socket.getOutputStream(), socket, credentials, RdpOptions(security = security), sink)
        val out = File(instrumentation.targetContext.getExternalFilesDir(null), "rdp-test").apply { mkdirs() }
        fun saveFrame(stage: String) {
            // Copy under the sink lock, then compress off it. PNG IO must never hold up
            // the native event loop, especially when the emulator or host is busy.
            val snapshot = synchronized(lock) { Triple(pixels.copyOf(), width, height) }
            val bitmap = Bitmap.createBitmap(snapshot.first, snapshot.second, snapshot.third, Bitmap.Config.ARGB_8888)
            try {
                File(out, "$desktop-$stage.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
        try {
            session.start(scope)
            val firstDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (frame.count > 0 && session.state.value !is RemoteScreenState.Closed && System.nanoTime() < firstDeadline) frame.await(100, TimeUnit.MILLISECONDS)
            val gotFrame = frame.count == 0L
            assertTrue("No nonempty frame: ${session.state.value}", gotFrame)
            assertTrue(session.state.value is RemoteScreenState.Connected)
            assertNotNull(session.certificateSha256.value)
            saveFrame("before")
            // Coordinates are adjustable after inspecting the first saved frame. Default is the
            // centered zenity entry. The second invocation can use input.x/input.y arguments.
            val x = args.getString("input.x")?.toInt() ?: width/2
            val y = args.getString("input.y")?.toInt() ?: height/2
            session.pointer(x,y,1); session.pointer(x,y,0)
            session.key(RfbKeys.CONTROL_L,true); session.key('a'.code,true)
            session.key('a'.code,false); session.key(RfbKeys.CONTROL_L,false)
            val text = requireNotNull(args.getString("input.text", "Miffan P5A 中文输入"))
            if (!session.typeText(text)) {
                session.sendClipboard(text)
                session.key(RfbKeys.CONTROL_L, true); session.key('v'.code, true)
                session.key('v'.code, false); session.key(RfbKeys.CONTROL_L, false)
            }
            Thread.sleep(3000)
            saveFrame("input")
            Log.i("MiffanRdpTest", "$desktop sending Return after Unicode text")
            session.key(RfbKeys.RETURN, true); session.key(RfbKeys.RETURN, false)
            Thread.sleep(4000)
            val stats = session.stats.value
            assertTrue("No observed encoding: $stats", stats.encoding in setOf("AVC444","AVC420","RFX","Planar","Raw"))
            if (stats.encoding.startsWith("AVC")) assertNotNull("Missing actual MediaCodec decoder: $stats", stats.decoder)
            assertEquals(if (desktop == "kde") "TLS" else "NLA", stats.security)
            saveFrame("after")
            val report = "desktop=$desktop size=${width}x$height frames=$frames certificate=${session.certificateSha256.value} stats=$stats"
            File(out,"$desktop-stats.txt").writeText(report)
            Log.i("MiffanRdpTest",report)
            session.setPaused(true)
            Thread.sleep(400)
            val pausedFrames = synchronized(lock) { frames }
            Thread.sleep(400)
            assertEquals(pausedFrames, synchronized(lock) { frames })
            session.setPaused(false)
        } finally {
            session.close(); session.close(); scope.cancel()
            assertTrue("RDP threads did not stop", session.awaitStopped(10000))
        }
        // Reconnect with the observed pin, then deliberately reject another fingerprint.
        for (pin in listOf(requireNotNull(session.certificateSha256.value), "00".repeat(32))) {
            val another = Socket().apply { connect(InetSocketAddress(args.getString("host","10.0.2.2"), requireNotNull(args.getString("$desktop.port")).toInt()),10000) }
            val anotherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val retry = RdpSession(another.getInputStream(),another.getOutputStream(),another,credentials,RdpOptions(certificateSha256=pin,security=security),sink)
            try {
                retry.start(anotherScope)
                val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
                while (retry.state.value is RemoteScreenState.Connecting && System.nanoTime()<deadline) Thread.sleep(50)
                if (pin == session.certificateSha256.value) assertTrue("Pinned reconnect: ${retry.state.value}", retry.state.value is RemoteScreenState.Connected)
                else assertTrue("Wrong pin accepted: ${retry.state.value}", (retry.state.value as? RemoteScreenState.Closed)?.error is SecurityException)
            } finally {
                anotherScope.cancel(); retry.close()
                assertTrue(retry.awaitStopped(10000))
            }
        }
    }
}
