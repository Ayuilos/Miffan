package me.rerere.stream

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val prefs = context.getSharedPreferences("p6b-generated-identity", 0)
    private val args = InstrumentationRegistry.getArguments()
    private fun connector() = StreamTcpConnector { port ->
        val socket = Socket().apply { connect(InetSocketAddress("10.0.2.2", if (port == 47984) args.getString("pinServerPort")?.toInt() ?: port else port), 2000) }
        val output = object : java.io.FilterOutputStream(socket.getOutputStream()) {
            var sent = false
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                out.write(bytes, offset, length)
                if (!sent && port == 47989) { sent = true; Log.i("StreamAcceptance", "PAIR_REQUEST_SENT") }
            }
        }
        StreamTcpChannel(socket.getInputStream(), output, socket)
    }
    private fun identity(): StreamIdentity = if (prefs.contains("cert")) StreamIdentity(prefs.getString("cert", "")!!,
        prefs.getString("key", "")!!, prefs.getString("id", "")!!) else StreamIdentities.generate().also {
        prefs.edit().putString("cert", it.certificatePem).putString("key", it.privateKeyPem).putString("id", it.uniqueId).commit()
    }
    @Test fun pairNewIdentity(): Unit = runBlocking {
        assertFalse("Use this test only with a new test identity", prefs.contains("pin"))
        val pinFile = File(context.cacheDir, "stream-pin")
        val pin = pinFile.readText().trim(); pinFile.delete()
        val host = StreamHost(connector(), identity(), null)
        val actual = host.pair(pin, "miffan-p6b-emulator")
        assertEquals("b1afd9ddaa2c2f90559b3cf77ef9882688aba8130d40453cf246e22e905a1d17", actual)
        prefs.edit().putString("pin", actual).commit()
        assertTrue(host.serverInfo().paired)
        Log.i("StreamAcceptance", "PAIR_OK DER_SHA256=$actual")
    }
    @Test fun certificatePinRejects(): Unit = runBlocking {
        val host = StreamHost(connector(), identity(), "00".repeat(32))
        try { host.serverInfo(); fail("Wrong pin accepted") } catch (e: StreamException) {
            assertEquals(StreamFailureReason.CERTIFICATE_MISMATCH, e.reason)
        }
        Log.i("StreamAcceptance", "PIN_REJECTED_BEFORE_HTTP")
    }
    @Test fun strictEncryptionRejectsPlainRtsp() {
        val activity = instrumentation.startActivitySync(Intent(context, StreamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StreamTestActivity
        assertTrue(activity.ready.await(5, TimeUnit.SECONDS))
        var opens = 0
        val connector = StreamTcpConnector { opens++; error("Plain RTSP must fail before opening TCP") }
        val session = StreamSession(StreamHost(connector, identity(), "00".repeat(32)), StreamApp(1, "unused"),
            "192.0.2.1", StreamConfig(1920, 1080, 60, 15000), activity.view.holder.surface)
        try {
            val key = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
            assertEquals(-110, StreamNative.run(session, "192.0.2.1", "7.1.431.-1", "3.23.0.74", 257,
                "rtsp://192.0.2.1:48010", 1920, 1080, 60, 15000, 257, key, ByteArray(16)))
            assertEquals(0, opens)
        } finally { session.close(); instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun cancellationReleasesProcessSlot() {
        val activity = instrumentation.startActivitySync(Intent(context, StreamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StreamTestActivity
        assertTrue(activity.ready.await(5, TimeUnit.SECONDS))
        val release = java.util.concurrent.CountDownLatch(1)
        val opened = java.util.concurrent.CountDownLatch(1)
        val flushed = java.util.concurrent.CountDownLatch(1)
        val connector = StreamTcpConnector {
            opened.countDown()
            val input = object : java.io.InputStream() {
                override fun read(): Int { release.await(); return -1 }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int { release.await(); return -1 }
            }
            val output = object : java.io.ByteArrayOutputStream() { override fun flush() { flushed.countDown() } }
            StreamTcpChannel(input, output, java.io.Closeable { release.countDown() })
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = StreamHost(connector, identity(), "00".repeat(32))
        fun session() = StreamSession(host, StreamApp(1, "unused"), "192.0.2.1",
            StreamConfig(1920, 1080, 60, 15000), activity.view.holder.surface)
        try {
            val closed = session(); closed.close(); closed.close()
            assertTrue(closed.awaitStopped(100))
            assertThrows(IllegalStateException::class.java) { closed.start(scope) }
            val pending = session(); pending.start(scope)
            assertTrue(opened.await(3, TimeUnit.SECONDS))
            assertTrue("Caller output was not flushed", flushed.await(3, TimeUnit.SECONDS)); scope.cancel()
            assertTrue("Cancelled handshake leaked process slot", pending.awaitStopped(3000))
            assertEquals(StreamState.Closed, pending.state.value)
            val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val next = session(); next.start(nextScope); next.close()
            assertTrue(next.awaitStopped(3000))
            assertFalse(next.state.value is StreamState.Failed && (next.state.value as StreamState.Failed).reason == StreamFailureReason.BUSY)
            nextScope.cancel()
        } finally { scope.cancel(); instrumentation.runOnMainSync { activity.finish() } }
    }
    /** P6e receive-only acceptance: no keys, clipboard, pointer or host configuration changes. */
    @Test fun audioAndTrafficAcceptance(): Unit = runBlocking {
        val pin = requireNotNull(prefs.getString("pin", null)) { "Test identity needs pairing" }
        val host = StreamHost(connector(), identity(), pin)
        val app = host.apps().first { it.name == "Desktop" }
        val activity = instrumentation.startActivitySync(Intent(context, StreamTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StreamTestActivity
        assertTrue(activity.ready.await(5, TimeUnit.SECONDS))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s = StreamSession(host, app, "100.64.0.5", StreamConfig(
            args.getString("width", "1280")!!.toInt(), args.getString("height", "720")!!.toInt(),
            args.getString("fps", "30")!!.toInt(), args.getString("bitrate", "4000")!!.toInt(),
            setOf(StreamCodec.H264), args.getString("timeout", "10000")!!.toLong()),
            activity.view.holder.surface, activity).apply { allowSoftwareDecoder = args.getString("software", "false") == "true" }
        val audio = requireNotNull(s.audio)
        fun waitUntil(label: String, timeout: Long = 10000, predicate: () -> Boolean) {
            val until = System.nanoTime() + timeout * 1_000_000
            while (!predicate() && System.nanoTime() < until) Thread.sleep(20)
            assertTrue("$label: ${s.state.value} ${s.stats.value}", predicate())
        }
        try {
            assertFalse(audio.enabled.value); s.start(scope)
            waitUntil("stream startup", 30000) { s.state.value != StreamState.Connecting }
            assertEquals(StreamState.Streaming, s.state.value)
            val mutedBytes = s.bytesReceived
            Thread.sleep(1000)
            assertTrue(s.bytesReceived > mutedBytes)
            assertTrue(s.stats.value.audioPackets > 0)
            assertFalse(s.stats.value.audioTrackActive); assertEquals(0L, s.stats.value.audioWrittenFrames)
            audio.setEnabled(true)
            assertTrue("Focus denied: ${s.stats.value}", audio.enabled.value)
            waitUntil("AudioTrack playback head advances") { s.stats.value.audioPlayedFrames > 0 }
            Thread.sleep(5000)
            assertTrue(s.stats.value.audioTrackActive)
            assertTrue(s.stats.value.audioWrittenFrames > 0)
            assertNotNull(s.stats.value.audioDecoderName)
            Log.i("StreamAcceptance", "P6E_AUDIO_ON bytes=${s.bytesReceived} ${s.stats.value}")
            audio.setEnabled(false)
            assertFalse(audio.enabled.value); assertFalse(s.stats.value.audioTrackActive)
            val written = s.stats.value.audioWrittenFrames
            val played = s.stats.value.audioPlayedFrames
            val bytes = s.bytesReceived; val packets = s.stats.value.audioPackets
            Thread.sleep(1500)
            assertEquals(written, s.stats.value.audioWrittenFrames)
            assertEquals(played, s.stats.value.audioPlayedFrames)
            assertTrue(s.bytesReceived > bytes); assertTrue(s.stats.value.audioPackets > packets)
            Log.i("StreamAcceptance", "P6E_AUDIO_OFF bytes=${s.bytesReceived} ${s.stats.value}")
            // Exercise re-enable and focus loss, without sending anything to the host.
            audio.setEnabled(true)
            waitUntil("playback resumes") { s.stats.value.audioPlayedFrames > played }
            val manager = activity.getSystemService(android.media.AudioManager::class.java)
            val competing = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                .build()
            try {
                assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(competing))
                waitUntil("focus loss releases playback") { !audio.enabled.value && !s.stats.value.audioTrackActive }
                assertFalse(s.stats.value.audioTrackActive)
                val stoppedWrites = s.stats.value.audioWrittenFrames
                Thread.sleep(200)
                assertEquals(stoppedWrites, s.stats.value.audioWrittenFrames)
                Log.i("StreamAcceptance", "P6E_FOCUS_LOST ${s.stats.value}")
            } finally { manager.abandonAudioFocusRequest(competing) }
            audio.setEnabled(true)
            waitUntil("track recreated after focus loss") { s.stats.value.audioTrackActive }
        } finally {
            s.close(); assertTrue(s.awaitStopped()); scope.cancel()
            assertFalse(audio.enabled.value); assertFalse(s.stats.value.audioTrackActive)
            Log.i("StreamAcceptance", "P6E_AUDIO_CLOSED bytes=${s.bytesReceived} ${s.stats.value}")
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun streamAcceptance(): Unit = runBlocking {
        val retryArmed = java.util.concurrent.atomic.AtomicBoolean()
        val retryTcpAt = java.util.concurrent.atomic.AtomicLong()
        val underlying = connector()
        val host = StreamHost(StreamTcpConnector { port ->
            if (retryArmed.get()) retryTcpAt.compareAndSet(0, System.nanoTime())
            underlying.open(port)
        }, identity(), requireNotNull(prefs.getString("pin", null)))
        val apps = host.apps(); val app = apps.firstOrNull { it.name == "Desktop" } ?: apps.first()
        val activity = instrumentation.startActivitySync(Intent(context, StreamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StreamTestActivity
        assertTrue(activity.ready.await(5, TimeUnit.SECONDS))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val codec = if (args.getString("codec", "hevc") == "hevc") StreamCodec.HEVC else StreamCodec.H264
        fun session(address: String) = StreamSession(host, app, address, StreamConfig(args.getString("width", "1920")!!.toInt(),
            args.getString("height", "1080")!!.toInt(), args.getString("fps", "60")!!.toInt(), args.getString("bitrate", "15000")!!.toInt(),
            if (args.getString("fallback", "false") == "true") setOf(StreamCodec.HEVC, StreamCodec.H264) else setOf(codec),
            if (address == "192.0.2.1") 4000 else args.getString("timeout", "4000")!!.toLong()), activity.view.holder.surface).apply {
            allowSoftwareDecoder = args.getString("software", "false") == "true"
        }
        fun waitState(s: StreamSession, max: Long = 10000) {
            val until = System.nanoTime() + (max + host.retryAfterMillis + 15000) * 1_000_000
            while (s.state.value == StreamState.Connecting && System.nanoTime() < until) Thread.sleep(20)
        }
        var running: StreamSession? = null
        try {
            if (args.getString("candidate", "false") == "true") {
                val bad = session("192.0.2.1"); running = bad
                val begin = System.nanoTime(); bad.start(scope); waitState(bad)
                assertTrue(bad.awaitStopped())
                val elapsed = (System.nanoTime() - begin) / 1e6
                assertEquals(StreamFailureReason.UDP_UNREACHABLE, (bad.state.value as StreamState.Failed).reason)
                assertTrue("Deadline plus cleanup exceeded 6s: $elapsed", elapsed < 6000)
                Log.i("StreamAcceptance", "UDP_FAILURE elapsedMs=$elapsed state=${bad.state.value}")
            }
            val s = session("100.64.0.5"); running = s
            val retryWait = host.retryAfterMillis
            Log.i("StreamAcceptance", "START codec=$codec retryWaitMs=$retryWait software=${s.allowSoftwareDecoder}")
            val retryStart = System.nanoTime(); retryArmed.set(retryWait > 0)
            s.start(scope); waitState(s)
            assertEquals("Startup failed: ${s.state.value}; ${s.stats.value}", StreamState.Streaming, s.state.value)
            if (retryWait > 0) {
                val actualWait = (retryTcpAt.get() - retryStart) / 1_000_000.0
                assertTrue("New TCP opened before pending expiry: $actualWait < $retryWait", actualWait >= retryWait - 50)
                Log.i("StreamAcceptance", "RETRY_WAIT expectedMs=$retryWait actualBeforeTcpMs=$actualWait")
            }
            val busy = session("100.64.0.5"); busy.start(scope)
            assertEquals(StreamFailureReason.BUSY, (busy.state.value as StreamState.Failed).reason); busy.close()
            s.mouseMove(5, 0); Thread.sleep(100); s.mouseMove(-5, 0)
            s.key(0x10, true, 1); Thread.sleep(100); s.key(0x10, false, 0)
            val start = System.nanoTime()
            var swapped = false
            while ((System.nanoTime() - start) < 30_000_000_000L) {
                Thread.sleep(1000)
                assertEquals(StreamState.Streaming, s.state.value)
                if (!swapped && System.nanoTime() - start > 10_000_000_000L) {
                    s.setSurface(null); Thread.sleep(250)
                    val before = s.stats.value.renderedFrames
                    lateinit var newReady: java.util.concurrent.CountDownLatch
                    instrumentation.runOnMainSync { newReady = activity.replaceSurface() }
                    assertTrue(newReady.await(5, TimeUnit.SECONDS))
                    s.setSurface(activity.view.holder.surface)
                    val resumeDeadline = System.nanoTime() + 10_000_000_000L
                    while (s.stats.value.renderedFrames <= before && s.state.value == StreamState.Streaming && System.nanoTime() < resumeDeadline) Thread.sleep(50)
                    assertEquals("Surface replacement failed: ${s.stats.value}", StreamState.Streaming, s.state.value)
                    assertTrue("Surface did not resume rendering: ${s.stats.value}", s.stats.value.renderedFrames > before)
                    Log.i("StreamAcceptance", "SURFACE_RESUMED codec=$codec")
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(context.getExternalFilesDir(null), "p6b-$codec.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    swapped = true
                }
                Log.i("StreamAcceptance", "SAMPLE ${s.stats.value}")
            }
            val stats = s.stats.value
            assertEquals(codec, stats.codec)
            assertTrue(stats.videoEncrypted && stats.audioEncrypted && stats.controlEncrypted)
            assertTrue("No sustained output: $stats", stats.renderedFrames > 10 && stats.audioPackets > 0)
            Log.i("StreamAcceptance", "FINAL $stats")
            File(context.getExternalFilesDir(null), "p6b-$codec.txt").writeText(stats.toString())
        } finally {
            running?.close(); assertTrue(running?.awaitStopped() != false); scope.cancel()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
