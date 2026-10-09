package me.ayuilos.miffan.data.repository

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import me.ayuilos.miffan.data.db.entity.RemoteScreenAuth
import me.ayuilos.miffan.data.db.entity.RemoteScreenEndpoint
import me.ayuilos.miffan.data.db.entity.RemoteScreenProtocol
import me.rerere.workspace.screen.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Real app repositories, generated app key and SSH direct-tcpip; no host-side private key. */
@RunWith(AndroidJUnit4::class)
class RdpRepositoryInstrumentedTest {
    @Test fun sshScreen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val workspaces = GlobalContext.get().get<WorkspaceRepository>()
        val screens = GlobalContext.get().get<RemoteScreenRepository>()
        val preferences = context.getSharedPreferences("p5b-screen-test", 0)
        val key = preferences.getString("key", null)?.let { workspaces.getSshKeyById(it) }
            ?: workspaces.generateSshKey("P5b emulator test key").also { preferences.edit().putString("key", it.id).commit() }
        if (args.getString("phase") == "key") {
            File(context.cacheDir, "p5b-public-key.txt").writeText(key.publicKey + "\n")
            Log.i("MiffanP5bTest", "Public key generated inside app; id=${key.id}")
            return@runBlocking
        }
        val host = workspaces.createHost("P5b-${System.currentTimeMillis()}",
            requireNotNull(args.getString("ssh.host")), args.getString("ssh.port", "22").toInt(),
            requireNotNull(args.getString("ssh.username")), sshKeyId = key.id)
        var workspaceId: String? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var connection: RemoteScreenConnection? = null
        try {
            assertTrue(workspaces.trustHostKey(host.id, requireNotNull(args.getString("ssh.fingerprint"))))
            val workspace = workspaces.createRemoteWorkspace("P5b SSH screen", host.id,
                args.getString("ssh.root", "/home/miffanrdp"))
            workspaceId = workspace.id
            screens.updateConfig(host.id, true, RemoteScreenEndpoint.Helper, RemoteScreenAuth.NONE, "", null,
                protocol = RemoteScreenProtocol.RDP)
            if (args.getString("refreshHelper") == "true") {
                // The development helper version stays v7 between edits; force repository
                // installation of this APK's current asset, leaving services/config intact.
                assertEquals(0, workspaces.executeCommand(workspace.id,
                    "rm -f ~/.miffan/bin/miffan", timeoutMillis = 10000).exitCode)
            }
            val first = CountDownLatch(1)
            val lock = Any()
            var width = 0; var height = 0; var pixels = IntArray(0)
            val sink = object : RemoteScreenFrameSink {
                override fun onSize(w: Int, h: Int, scale: Int) { synchronized(lock) {
                    assertEquals(1, scale); width = w; height = h; pixels = IntArray(w * h)
                } }
                override fun onPixels(rect: RfbRect, data: IntArray) { synchronized(lock) {
                    for (row in 0 until rect.height) data.copyInto(pixels, (rect.y + row) * width + rect.x,
                        row * rect.width, (row + 1) * rect.width)
                } }
                override fun onFrameComplete() { synchronized(lock) {
                    if (first.count > 0 && pixels.any { it and 0xffffff != 0 }) first.countDown()
                } }
            }
            if (args.getString("phase") == "existing") {
                val error = runCatching { screens.open(workspace.id, sink, null) }.exceptionOrNull()
                assertEquals(RemoteScreenProblem.RDP_ALREADY_CONFIGURED, (error as? RemoteScreenUnavailableException)?.problem)
                return@runBlocking
            }
            connection = screens.open(workspace.id, sink, null)
            val session = connection.session
            assertEquals(RemoteDesktopProtocol.RDP, session.protocol)
            val copied = CompletableDeferred<String>()
            scope.launch { session.clipboard.collect { if (it == "Miffan P5B 中文输入") copied.complete(it) } }
            session.start(scope)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(50)
            while (first.count > 0 && session.state.value !is RemoteScreenState.Closed && System.nanoTime() < deadline)
                first.await(100, TimeUnit.MILLISECONDS)
            assertEquals("No frame: ${session.state.value}", 0L, first.count)
            assertTrue(session.state.value is RemoteScreenState.Connected)
            assertEquals(if (args.getString("active") == "kde") "TLS" else "NLA", session.rdpStats?.value?.security)
            val pin = requireNotNull(session.certificateSha256.value)
            val password = requireNotNull(RemoteScreenCredentialStore(context).loadRdp(host.id))
            val processAudit = workspaces.executeCommand(workspace.id, "ps -eo args", timeoutMillis = 10000)
            assertEquals(0, processAudit.exitCode)
            assertFalse("RDP password appears in a process argv", processAudit.stdout.contains(password))
            val permissions = workspaces.executeCommand(workspace.id,
                "stat -c '%a' ~/.miffan/rdp ~/.miffan/rdp/cert.pem ~/.miffan/rdp/key.pem", timeoutMillis = 10000)
            assertEquals(0, permissions.exitCode)
            assertEquals(listOf("700", "600", "600"), permissions.stdout.trim().lines())
            Log.i("MiffanP5bTest", "Remote process argv contains no generated RDP password; certificate permissions 700/600/600")
            withTimeout(5000) { while (screens.getConfig(host.id)?.rdpCertificateSha256 == null) delay(50) }
            assertEquals(pin, screens.getConfig(host.id)?.rdpCertificateSha256)
            session.pointer(width / 2, height / 2, 1); session.pointer(width / 2, height / 2, 0)
            session.key(RfbKeys.CONTROL_L, true); session.tapKey('a'.code); session.key(RfbKeys.CONTROL_L, false)
            assertFalse(session.typeText("中文输入"))
            assertFalse(session.typeText("a".repeat(4097)))
            assertTrue(session.typeText("Miffan P5B "))
            session.sendClipboard("中文输入")
            session.key(RfbKeys.CONTROL_L, true); session.tapKey('v'.code); session.key(RfbKeys.CONTROL_L, false)
            delay(4000)
            session.key(RfbKeys.CONTROL_L, true); session.tapKey('a'.code); session.tapKey('c'.code); session.key(RfbKeys.CONTROL_L, false)
            assertEquals("Miffan P5B 中文输入", withTimeout(15000) { copied.await() })
            session.tapKey(RfbKeys.RETURN)
            Log.i("MiffanP5bTest", "${args.getString("active")} Return sent; SSH repository, Unicode clipboard roundtrip passed")
            delay(4000)
            val snapshot = synchronized(lock) { Triple(pixels.copyOf(), width, height) }
            val bitmap = Bitmap.createBitmap(snapshot.first, snapshot.second, snapshot.third, Bitmap.Config.ARGB_8888)
            try { File(context.getExternalFilesDir(null), "p5b-${args.getString("active")}.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
            Log.i("MiffanP5bTest", "stats=${session.rdpStats?.value}; pin=$pin; bytes=${session.bytesReceived}")
            connection.close()
            assertEquals(pin, screens.getConfig(host.id)?.rdpCertificateSha256)
            val pinned = screens.open(workspace.id, sink, null)
            try {
                pinned.session.start(scope)
                withTimeout(15000) { while (pinned.session.state.value is RemoteScreenState.Connecting) delay(50) }
                assertTrue("Pinned reconnect: ${pinned.session.state.value}", pinned.session.state.value is RemoteScreenState.Connected)
                assertEquals(pin, pinned.session.certificateSha256.value)
            } finally { pinned.close() }
            assertTrue(screens.pinRdpCertificate(host.id, "00".repeat(32)))
            val wrong = screens.open(workspace.id, sink, null)
            try {
                wrong.session.start(scope)
                withTimeout(15000) { while (wrong.session.state.value !is RemoteScreenState.Closed) delay(50) }
                assertTrue((wrong.session.state.value as RemoteScreenState.Closed).error is RemoteRdpCertificateChangedException)
                assertEquals("00".repeat(32), screens.getConfig(host.id)?.rdpCertificateSha256)
            } finally { wrong.close() }
        } finally {
            connection?.close(); scope.cancel()
            workspaceId?.let { workspaces.delete(it) }
            screens.forgetHost(host.id); workspaces.deleteHost(host.id)
        }
    }
}
