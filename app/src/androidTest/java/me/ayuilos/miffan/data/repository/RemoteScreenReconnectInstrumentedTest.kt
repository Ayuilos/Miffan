package me.ayuilos.miffan.data.repository

import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.workspace.RemoteChannelStream
import me.rerere.workspace.RemoteWorkspaceSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Reuses a pooled SSH session; does not start RDP/VNC or change desktop credentials. */
@RunWith(AndroidJUnit4::class)
class RemoteScreenReconnectInstrumentedTest {
    @Test fun uiCloseThenImmediateOpenReusesHealthySsh() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val workspaces = GlobalContext.get().get<WorkspaceRepository>()
        val keyId = requireNotNull(context.getSharedPreferences("p5b-screen-test", 0).getString("key", null))
        val host = workspaces.createHost("P5b-reconnect-${System.currentTimeMillis()}",
            requireNotNull(args.getString("ssh.host")), args.getString("ssh.port", "22").toInt(),
            requireNotNull(args.getString("ssh.username")), sshKeyId = keyId)
        var workspaceId: String? = null
        var stream: LeasedRemote<RemoteChannelStream>? = null
        var sharedSsh: RemoteWorkspaceSession? = null
        try {
            assertTrue(workspaces.trustHostKey(host.id, requireNotNull(args.getString("ssh.fingerprint"))))
            val workspace = workspaces.createRemoteWorkspace("P5b reconnect SSH", host.id,
                args.getString("ssh.root", "/home/miffanrdp"))
            workspaceId = workspace.id
            repeat(5) {
                val opened = workspaces.openLeasedRemote(workspace.id) { ssh ->
                    if (sharedSsh == null) sharedSsh = ssh else assertSame("SSH must be reused", sharedSsh, ssh)
                    ssh.openProcess("cat")
                }
                stream = opened
                opened.value.output.write('x'.code); opened.value.output.flush()
                assertEquals('x'.code, opened.value.input.read())
                instrumentation.runOnMainSync {
                    val policy = StrictMode.getThreadPolicy()
                    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeathOnNetwork().build())
                    try { opened.close() } finally { StrictMode.setThreadPolicy(policy) }
                }
                assertFalse(opened.value.isConnected)
                // No sleep, retry or replacement: the same verified SSH lease must still work.
                val result = workspaces.executeCommand(workspace.id, "printf alive", timeoutMillis = 5000)
                assertEquals(0, result.exitCode)
                assertEquals("alive", result.stdout)
                assertTrue(requireNotNull(sharedSsh).isConnected)
            }
        } finally {
            stream?.close()
            workspaceId?.let { workspaces.delete(it) }
            workspaces.deleteHost(host.id)
        }
    }
}
