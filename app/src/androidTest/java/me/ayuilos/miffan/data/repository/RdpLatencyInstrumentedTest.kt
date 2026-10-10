package me.ayuilos.miffan.data.repository

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.rdp.*
import me.rerere.workspace.screen.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Fixed test account, app-owned SSH key/password, installed helper. No helper installation. */
@RunWith(AndroidJUnit4::class)
class RdpLatencyInstrumentedTest {
    @Test fun sshLatencyComparison() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val workspaces = GlobalContext.get().get<WorkspaceRepository>()
        val hosts = workspaces.listHostsFlow().first()
        val credentials = RemoteScreenCredentialStore(context)
        val existing = hosts.firstOrNull {
            it.host == "100.64.0.5" && it.port == 22 && it.username == "miffanrdp" &&
                it.trustedHostKeySha256 != null && credentials.loadRdp(it.id) != null
        }
        val keyId = requireNotNull(context.getSharedPreferences("p5b-screen-test", 0).getString("key", null)) {
            "Existing app-generated miffanrdp test key is required"
        }
        val sshPin = args.getString("ssh.fingerprint") ?: hosts.firstOrNull {
            it.host == "100.64.0.5" && it.port == 22 && it.trustedHostKeySha256 != null
        }?.trustedHostKeySha256
        val host = existing ?: workspaces.createHost("RDP latency-${System.currentTimeMillis()}",
            "100.64.0.5", 22, "miffanrdp", sshKeyId = keyId)
        var workspaceId: String? = null
        try {
            if (existing == null) assertTrue(workspaces.trustHostKey(host.id, requireNotNull(sshPin) {
                "Pass the already verified SSH host fingerprint; do not accept an unverified key"
            }))
            val workspace = workspaces.createRemoteWorkspace("RDP latency test", host.id, "/home/miffanrdp")
            workspaceId = workspace.id
            val password = existing?.let { requireNotNull(credentials.loadRdp(it.id)) } ?: credentials.getOrCreateRdp(host.id)
            val out = File(context.filesDir, "rdp-latency").apply { mkdirs() }
            val modes = args.getString("measure.scenarios")?.split(",") ?: listOf("off", "on")
            require(modes.all { it in setOf("off", "on", "baseline", "event", "ack", "bitmap", "network") })
            out.listFiles()?.filter { file -> modes.any { file.name == "gnome-$it.txt" || file.name == "gnome-$it.png" } }?.forEach { it.delete() }
            for (mode in modes) {
                val lowLatency = mode != "off"
                val eventDriven = mode != "baseline"
                val earlyAck = mode !in setOf("baseline", "event")
                val lock = Any()
                val bitmapLock = ReentrantLock()
                var targetBitmap: Bitmap? = null
                var width = 0; var height = 0; var pixels = IntArray(0); var nonempty = false
                val sink = object : RdpBitmapFrameSink {
                    override fun acquireBitmap(width: Int, height: Int): Bitmap? {
                        if (mode !in setOf("bitmap", "network")) return null
                        bitmapLock.lock()
                        return targetBitmap ?: run { bitmapLock.unlock(); null }
                    }
                    override fun releaseBitmap(bitmap: Bitmap) { bitmapLock.unlock() }
                    override fun onSize(w: Int, h: Int, scale: Int) = synchronized(lock) {
                        assertEquals(1, scale); width = w; height = h; pixels = IntArray(w * h)
                        if (mode in setOf("bitmap", "network")) bitmapLock.withLock { targetBitmap?.recycle(); targetBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
                    }
                    override fun onPixels(rect: RfbRect, data: IntArray) = synchronized(lock) {
                        assertFalse("Bitmap scenario fell back to IntArray", mode in setOf("bitmap", "network"))
                        for (row in 0 until rect.height) data.copyInto(pixels, (rect.y + row) * width + rect.x,
                            row * rect.width, (row + 1) * rect.width)
                    }
                    override fun onFrameComplete() = synchronized(lock) {
                        if (!nonempty) nonempty = if (mode in setOf("bitmap", "network")) bitmapLock.withLock { requireNotNull(targetBitmap).getPixel(100, 100) and 0xffffff != 0 } else pixels.any { it and 0xffffff != 0 }
                    }
                }
                suspend fun connectTestSsh(): LeasedRemote<RdpSession> {
                    for (attempt in 0..2) {
                        var enteredRemote = false
                        try {
                            return workspaces.openLeasedRemote(workspace.id) { ssh ->
                                enteredRemote = true
                                // Same stdin-only helper invocation as RemoteScreenRepository. Never install,
                                // replace, delete or edit its existing configuration or credentials directly.
                                val started = ssh.execute("\"\$HOME/.miffan/bin/miffan\" rdp start", timeoutMillis = 30000,
                                    stdin = ByteArrayInputStream((password + "\n").toByteArray(Charsets.UTF_8)))
                                assertEquals("Installed helper failed to start the test service", 0, started.exitCode)
                                assertFalse(started.timedOut)
                                val rdp = parseRdpStart(started.stdout)
                                assertEquals("gnome-remote-desktop", rdp.server)
                                assertEquals("headless", rdp.mode)
                                val pin = requireNotNull(expectedRdpCertificate(host.rdpCertificateSha256, rdp.certificateSha256))
                                val stream = ssh.openLoopbackStream(requireNotNull(rdp.port))
                                try {
                                    RdpSession(stream.input, stream.output, stream, RdpCredentials(requireNotNull(rdp.username), password),
                                        RdpOptions(2560, 1440, pin, RdpSecurity.NLA, lowLatency, eventDriven, earlyAck, mode in setOf("bitmap", "network"), mode == "network"), sink)
                                } catch (error: Throwable) { stream.close(); throw error }
                            }
                        } catch (error: Exception) {
                            val networkFailure = generateSequence<Throwable>(error) { it.cause }.any {
                                (it is SocketTimeoutException || it is ConnectException) && it.stackTrace.any { frame ->
                                    frame.className == "java.net.Socket" && frame.methodName == "connect"
                                }
                            }
                            if (enteredRemote || !networkFailure || attempt == 2) throw error
                            Log.i("RemoteScreenPerf", "RDP benchmark SSH TCP retry ${attempt + 1}/2 before helper")
                            delay(1000L shl attempt)
                        }
                    }
                    error("Unreachable SSH retry state")
                }
                val lease = connectTestSsh()
                val session = lease.value
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    Log.i("RemoteScreenPerf", "RDP benchmark BEGIN scenario=$mode lowLatency=$lowLatency account=miffanrdp requested=2560x1440")
                    session.start(scope)
                    withTimeout(50000) {
                        while (!synchronized(lock) { nonempty }) {
                            assertFalse("Closed before first frame: ${session.state.value}", session.state.value is RemoteScreenState.Closed)
                            delay(50)
                        }
                    }
                    synchronized(lock) { assertEquals(2560, width); assertEquals(1440, height) }
                    delay(if (mode in setOf("off", "on")) 2000 else 5000) // round 2 allows the first window to settle
                    val rows = mutableListOf<String>()
                    val measurementStart = System.nanoTime()
                    val receivedAtStart = session.bytesReceived
                    repeat(args.getString("measure.seconds", "30")!!.toInt().coerceIn(4, 300)) {
                        delay(1000)
                        assertTrue("Closed during measurement: ${session.state.value}", session.state.value is RemoteScreenState.Connected)
                        rows += "second=${it + 1} elapsedMs=${(System.nanoTime() - measurementStart) / 1_000_000.0} ${session.stats.value}"
                    }
                    File(out, "gnome-$mode.txt").writeText(rows.joinToString("\n"))
                    val stats = session.stats.value
                    assertEquals("AVC420", stats.encoding)
                    assertEquals("NLA", stats.security)
                    assertNotNull(stats.decoder)
                    assertTrue("No decoder output sampled", stats.decodedFrames > 0 && stats.decodeMs > 0)
                    assertTrue("YUV timing hook did not run", stats.yuvToRgbMs > 0)
                    assertTrue("Sink timing hook did not run", stats.sinkMs > 0)
                    assertTrue("ACK timing hook did not run", stats.surfaceToAckMs > 0)
                    assertEquals(true, stats.neonYuv)
                    assertTrue("EndFrame timing hook did not run", stats.frameDataMs > 0 && stats.composeMs > 0)
                    assertTrue("Transport bytes did not increase", stats.bytesReceived > receivedAtStart && stats.transportReadBytes > 0)
                    if (!lowLatency) assertEquals("plain", stats.decoderConfiguration)
                    if (earlyAck) assertTrue("ACK was not sent before deferred sink", stats.ackBeforeSinkFrames > 0)
                    else assertEquals(0L, stats.ackBeforeSinkFrames)
                    val bitmap = if (mode in setOf("bitmap", "network")) bitmapLock.withLock {
                        assertTrue("Direct Bitmap path did not run", stats.directBitmapFrames > 0)
                        requireNotNull(targetBitmap).copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        val snapshot = synchronized(lock) { pixels.copyOf() }
                        Bitmap.createBitmap(snapshot, width, height, Bitmap.Config.ARGB_8888)
                    }
                    try { File(out, "gnome-$mode.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    } } finally { bitmap.recycle() }
                    Log.i("RemoteScreenPerf", "RDP benchmark END scenario=$mode stats=$stats")
                } finally {
                    session.close(); lease.close(); scope.cancel()
                    // RdpSession's test-only wait is internal to rdp; give stream/codec teardown time.
                    delay(1000)
                    bitmapLock.withLock { targetBitmap?.recycle(); targetBitmap = null }
                }
            }
        } finally {
            workspaceId?.let { workspaces.delete(it) }
            if (existing == null) {
                GlobalContext.get().get<RemoteScreenRepository>().forgetHost(host.id)
                workspaces.deleteHost(host.id)
            }
        }
    }
}
