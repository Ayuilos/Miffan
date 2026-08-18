package me.rerere.workspace

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceExecutorInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun companionExecutesUnderAStableDifferentUidWithoutInternetPermission() {
        val connection = bindExecutor()
        try {
            val identity = WorkspaceExecutorProtocol.identity(connection.remote)
            assertEquals(WorkspaceExecutorProtocol.VERSION, identity.protocolVersion)
            assertEquals(WorkspaceExecutorProtocol.PACKAGE, identity.packageName)
            assertEquals(
                context.packageManager.getApplicationInfo(WorkspaceExecutorProtocol.PACKAGE, 0).uid,
                identity.processUid,
            )
            assertNotEquals(Process.myUid(), identity.processUid)
            assertTrue(identity.processId > 1)
            assertFalse(identity.hasInternetPermission)

            val result = WorkspaceExecutorProtocol.executeProbe(
                connection.remote,
                "printf 'uid=%s;pid=%s' \"\$(id -u)\" \"\$\$\"; exit 7",
            )
            assertEquals(7, result.exitCode)
            assertEquals("uid=${identity.processUid};pid=", result.stdout.substringBeforeLast('=').plus("="))
            assertTrue(result.stdout.substringAfterLast('=').toLong() > 1)
            assertEquals("", result.stderr)
            assertFalse(result.timedOut)
        } finally {
            context.unbindService(connection)
        }
    }

    private fun bindExecutor(): BoundConnection {
        val remoteRef = AtomicReference<IBinder>()
        val failure = AtomicReference<Throwable>()
        val connected = CountDownLatch(1)
        val connection = object : BoundConnection() {
            override val remote: IBinder
                get() = requireNotNull(remoteRef.get())

            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    failure.set(IllegalStateException("Workspace executor returned no Binder"))
                } else {
                    remoteRef.set(service)
                }
                connected.countDown()
            }

            override fun onNullBinding(name: ComponentName?) {
                failure.set(IllegalStateException("Workspace executor returned a null binding"))
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = context.bindService(
            WorkspaceExecutorProtocol.bindIntent(),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        check(bound) { "Workspace executor companion is not installed" }
        check(connected.await(5, TimeUnit.SECONDS)) { "Workspace executor bind timed out" }
        failure.get()?.let { throw it }
        return connection
    }

    private abstract class BoundConnection : ServiceConnection {
        abstract val remote: IBinder
    }
}
