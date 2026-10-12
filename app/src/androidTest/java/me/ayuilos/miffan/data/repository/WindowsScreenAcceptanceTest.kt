package me.ayuilos.miffan.data.repository

import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.ayuilos.miffan.debug.WindowsScreenTestActivity
import me.ayuilos.miffan.data.db.dao.RemoteHostDAO
import me.ayuilos.miffan.data.db.dao.WorkspaceDAO
import me.rerere.stream.StreamSession
import me.rerere.workspace.RemotePlatform
import me.rerere.workspace.screen.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Explicit opt-in; only emulator-5560. Controller must notify Claude before stream/clipboard phases. */
@RunWith(AndroidJUnit4::class)
class WindowsScreenAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val koin = GlobalContext.get()
    private val workspaces = koin.get<WorkspaceRepository>()
    private val hosts = koin.get<RemoteHostDAO>()
    private val preferences = context.getSharedPreferences("p7b2-test", 0)
    private val identityRoot = File(context.noBackupFilesDir, "p7b2-test")
    private fun guard() {
        check(args.getString("p7b2") == "true" && android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Opt-in emulator test only" }
    }
    private fun screens(): RemoteScreenRepository {
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir(): File = identityRoot }
        val repository = RemoteScreenRepository(workspaces, koin.get<WorkspaceDAO>(), hosts,
            koin.get<RemoteScreenCredentialStore>(), context.assets, isolated)
        val engine = RemoteScreenRepository::class.java.getDeclaredField("streams").apply { isAccessible = true }.get(repository) as RemoteStreamEngine
        engine.configureSessionForTest = { session ->
            // Emulator-only decoder exception, held in the debug test instance.
            StreamSession::class.java.getDeclaredField("allowSoftwareDecoder").apply { isAccessible = true }.setBoolean(session, true)
            StreamSession::class.java.getDeclaredField("config").apply { isAccessible = true }.set(session,
                me.rerere.stream.StreamConfig(1280, 720, 30, 4_000, setOf(me.rerere.stream.StreamCodec.H264), 4_000))
        }
        return repository
    }
    private suspend fun host() = requireNotNull(hosts.getById(requireNotNull(preferences.getString("host", null))))
    private fun workspace() = requireNotNull(preferences.getString("workspace", null))

    @Test fun installAndPair() = runBlocking {
        guard()
        check(preferences.getString("host", null) == null) { "Clean previous test configuration first" }
        val keyFile = File(context.cacheDir, "p7b2-test-key")
        val key = try { workspaces.importSshKey("miffan-p7b2-test", keyFile.readText()) }
            finally { check(keyFile.delete() || !keyFile.exists()) }
        preferences.edit().putString("key", key.id).commit()
        val host = workspaces.createHost("miffan-p7b2-test", requireNotNull(args.getString("host")), args.getString("port", "22").toInt(),
            requireNotNull(args.getString("user")), sshKeyId = key.id)
        preferences.edit().putString("host", host.id).commit()
        assertTrue(workspaces.trustHostKey(host.id, requireNotNull(args.getString("sshPin"))))
        assertTrue(workspaces.testHost(host.id))
        val workspace = workspaces.createRemoteWorkspace("miffan-p7b2-test", host.id, workspaces.remoteHome(host.id))
        preferences.edit().putString("workspace", workspace.id).commit()
        val screens = screens()
        screens.updateConfig(host.id, true, me.ayuilos.miffan.data.db.entity.RemoteScreenEndpoint.Helper,
            me.ayuilos.miffan.data.db.entity.RemoteScreenAuth.NONE, "", null)
        val row = host()
        assertEquals("windows", row.platform)
        val probe = screens.probe(workspace.id)
        assertEquals("windows", probe.os); assertEquals(3, probe.helper); assertFalse(probe.cua.ok)
        assertTrue(screens.checkSunshineFirewall(row.id, row.connectionRevision).firewallModelOk)
        workspaces.openLeasedRemote(workspace.id) { ssh ->
            assertEquals(RemotePlatform.WINDOWS, ssh.platform)
            val result = ssh.execute("[Console]::Out.WriteLine([Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path \$env:LOCALAPPDATA 'Miffan\\bin\\miffan.ps1'))))")
            assertEquals(0, result.exitCode)
            assertArrayEquals(context.assets.open("remote/miffan.ps1").use { it.readBytes() }, android.util.Base64.decode(result.stdout.trim(), 0))
            java.io.Closeable {}
        }.close()
        if (args.getString("helperOnly") == "true") {
            File(context.getExternalFilesDir(null), "p7b2-helper.txt").writeText("helper v3 upgraded byte-for-byte with BOM; firewall scope check safe; no pairing/stream/clipboard\n")
            return@runBlocking
        }
        val pinFile = File(context.cacheDir, "p7b2-pin")
        val pairing = screens.startStreamPairing(row.id, row.connectionRevision, "miffan-p7b2-emulator")
        try {
            pinFile.writeText(pairing.pin)
            assertEquals(64, pairing.await().length)
        } finally { pinFile.delete(); pairing.close() }
        assertTrue(screens.streamStatus(row.id, row.connectionRevision).paired)
        File(context.getExternalFilesDir(null), "p7b2-pair.txt").writeText("helper v${probe.helper} installed byte-for-byte with BOM; Windows SFTP platform; firewall check safe; new miffan-p7b2 client paired; temporary SSH key deleted after import\n")
    }

    @Test fun receiveScreen() = runBlocking { guard(); stream(false) }
    @Test fun inputToOwnedNotepad() = runBlocking {
        guard(); check(args.getString("foregroundConfirmed") == "true" && args.getString("clipboardNotified") == "true")
        stream(true)
    }
    private suspend fun stream(input: Boolean) {
        check(args.getString("streamNotified") == "true")
        val screens = screens(); val row = host()
        val activity = instrumentation.startActivitySync(Intent(context, WindowsScreenTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WindowsScreenTestActivity
        assertTrue(activity.ready.await(10, TimeUnit.SECONDS))
        var connection: RemoteScreenConnection? = null
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val sink = object : RemoteScreenFrameSink {
                override fun onSize(width: Int, height: Int, scale: Int) { error("Windows must not use VNC/RDP") }
                override fun onPixels(rect: RfbRect, pixels: IntArray) { error("Windows must use Surface") }
                override fun onFrameComplete() {}
            }
            connection = screens.open(workspace(), sink, null, stream = RemoteStreamRequest(RemoteScreenQuality.SAVER, skip = true))
            assertEquals(RemoteScreenPlatform.WINDOWS, connection.platform)
            assertEquals(RemoteDesktopProtocol.STREAM, connection.session.protocol)
            assertTrue(connection.streamRequested)
            assertEquals("100.64.0.8", connection.streamAddress)
            val session = connection.session as StreamDesktopSession
            session.audio.setEnabled(false)
            session.start(lifetime); session.setSurface(activity.view.holder.surface)
            withTimeout(30_000) { session.streamStats.first { it.renderedFrames > 10 } }
            if (input) {
                // User has activated/maximized only the test-owned document. Click in its text area.
                session.pointer(640, 320, 0); session.pointer(640, 320, 1); session.pointer(640, 320, 0)
                // Clear only this test-owned document, then F5 inserts a timestamp regardless of IME mode.
                session.key(RfbKeys.CONTROL_L, true); session.tapKey('a'.code); session.key(RfbKeys.CONTROL_L, false)
                session.tapKey(RfbKeys.BACKSPACE); session.tapKey(0xffc2) // F5
                session.tapKey(RfbKeys.RETURN); delay(500)
                assertTrue(session.typeText("米饭 Windows 中文粘贴验收")) // exactly one clipboard write
                delay(8_000)
                session.key(RfbKeys.CONTROL_L, true); session.tapKey('s'.code); session.key(RfbKeys.CONTROL_L, false)
                delay(3_000)
                val verified = workspaces.executeCommand(workspace(), "[Console]::Out.WriteLine([IO.File]::ReadAllText((Join-Path \$env:LOCALAPPDATA 'Miffan\\p7b2-tests\\p7b2-notepad.txt')))")
                assertEquals(0, verified.exitCode)
                assertTrue("native F5 timestamp saved in own document", Regex("""\d{1,2}:\d{2}""").containsMatchIn(verified.stdout))
                assertTrue("Chinese clipboard pasted in own document", verified.stdout.contains("米饭 Windows 中文粘贴验收"))
            }
            val bitmap = Bitmap.createBitmap(activity.view.width, activity.view.height, Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1); var status = -1
            PixelCopy.request(activity.view, bitmap, { status = it; copied.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue(copied.await(10, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, status)
            val stem = if (input) "p7b2-input" else "p7b2-screen"
            File(context.getExternalFilesDir(null), "$stem.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(context.getExternalFilesDir(null), "$stem.txt").writeText(session.streamStats.value.toString())
            bitmap.recycle()
            val stats = session.streamStats.value
            assertTrue(stats.videoEncrypted && stats.controlEncrypted && stats.audioEncrypted)
        } finally {
            val delegate = (connection?.session as? StreamDesktopSession)?.delegate
            connection?.close(); lifetime.cancel()
            if (delegate != null) assertTrue(delegate.awaitStopped(10_000))
            instrumentation.runOnMainSync { activity.finish() }
        }
        // Pending UDP sockets must also drain before the test claims it has finished streaming.
        withTimeout(30_000) {
            while (screens.streamStatus(row.id, row.connectionRevision).activeStream) delay(1_000)
        }
    }
    @Test fun cleanup() = runBlocking {
        guard()
        preferences.getString("workspace", null)?.let { workspaces.delete(it) }
        preferences.getString("host", null)?.let { workspaces.deleteHost(it) }
        preferences.getString("key", null)?.let { workspaces.deleteSshKey(it) }
        File(context.cacheDir, "p7b2-test-key").delete(); File(context.cacheDir, "p7b2-pin").delete()
        identityRoot.deleteRecursively(); preferences.edit().clear().commit(); Unit
    }
}
