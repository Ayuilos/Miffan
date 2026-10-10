package me.ayuilos.miffan.data.repository

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
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
            out.listFiles()?.filter { it.name in setOf("gnome-off.txt", "gnome-on.txt", "gnome-off.png", "gnome-on.png") }?.forEach { it.delete() }
            for (lowLatency in listOf(false, true)) {
                val lock = Any()
                var width = 0; var height = 0; var pixels = IntArray(0); var nonempty = false
                val sink = object : RemoteScreenFrameSink {
                    override fun onSize(w: Int, h: Int, scale: Int) = synchronized(lock) {
                        assertEquals(1, scale); width = w; height = h; pixels = IntArray(w * h)
                    }
                    override fun onPixels(rect: RfbRect, data: IntArray) = synchronized(lock) {
                        for (row in 0 until rect.height) data.copyInto(pixels, (rect.y + row) * width + rect.x,
                            row * rect.width, (row + 1) * rect.width)
                    }
                    override fun onFrameComplete() = synchronized(lock) {
                        if (!nonempty) nonempty = pixels.any { it and 0xffffff != 0 }
                    }
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val lease = workspaces.openLeasedRemote(workspace.id) { ssh ->
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
                            RdpOptions(2560, 1440, pin, RdpSecurity.NLA, lowLatency), sink)
                    } catch (error: Throwable) { stream.close(); throw error }
                }
                val session = lease.value
                val mode = if (lowLatency) "on" else "off"
                try {
                    Log.i("RemoteScreenPerf", "RDP benchmark BEGIN lowLatency=$mode account=miffanrdp requested=2560x1440")
                    session.start(scope)
                    withTimeout(50000) {
                        while (!synchronized(lock) { nonempty }) {
                            assertFalse("Closed before first frame: ${session.state.value}", session.state.value is RemoteScreenState.Closed)
                            delay(50)
                        }
                    }
                    synchronized(lock) { assertEquals(2560, width); assertEquals(1440, height) }
                    delay(2000) // first window/decoder setup excluded from measurements
                    val rows = mutableListOf<String>()
                    val measurementStart = System.nanoTime()
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
                    if (!lowLatency) assertEquals("plain", stats.decoderConfiguration)
                    val snapshot = synchronized(lock) { pixels.copyOf() }
                    val bitmap = Bitmap.createBitmap(snapshot, width, height, Bitmap.Config.ARGB_8888)
                    try { File(out, "gnome-$mode.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    } } finally { bitmap.recycle() }
                    Log.i("RemoteScreenPerf", "RDP benchmark END lowLatency=$mode stats=$stats")
                } finally {
                    session.close(); lease.close(); scope.cancel()
                    // RdpSession's test-only wait is internal to rdp; give stream/codec teardown time.
                    delay(1000)
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
