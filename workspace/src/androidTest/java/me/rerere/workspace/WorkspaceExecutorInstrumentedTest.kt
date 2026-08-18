package me.rerere.workspace

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Build
import android.os.Process
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.FileInputStream
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

            val manager = WorkspaceManager(context.cacheDir.resolve("snapshot-test-${System.nanoTime()}"))
            val root = "broker"
            manager.ensureWorkspace(root)
            manager.writeText(root, "caller.txt", "from caller")
            val snapshot = context.cacheDir.resolve("broker-${System.nanoTime()}.snapshot")
            try {
                FileOutputStream(snapshot).use { manager.exportFilesSnapshot(root, it) }
                ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_WRITE).use {
                    WorkspaceExecutorProtocol.debugSnapshotRoundTrip(connection.remote, root, it)
                }
                FileInputStream(snapshot).use { manager.replaceFilesSnapshot(root, it) }
                assertEquals("from caller", manager.readText(root, "caller.txt"))
                assertTrue(manager.readText(root, "executor/identity.txt").startsWith("uid=${identity.processUid};pid="))
            } finally {
                snapshot.delete()
                manager.deleteWorkspace(root)
                WorkspaceExecutorProtocol.deleteWorkspace(connection.remote, root)
            }
        } finally {
            context.unbindService(connection)
        }
    }

    @Test
    fun pinnedRootfsExecutesThroughTheSnapshotBrokerWhenProvisioned() {
        val source = RootfsCatalog.forAndroidAbis(Build.SUPPORTED_ABIS.toList())
        val archive = context.cacheDir.resolve("provisioned-rootfs.${source.format.extension}")
        assumeTrue("Pinned Rootfs archive was not provisioned for the device test", archive.isFile)
        val connection = bindExecutor()
        val root = "proot-broker"
        val localManager = WorkspaceManager(context.cacheDir.resolve("proot-caller-${System.nanoTime()}"))
        try {
            ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY).use {
                WorkspaceExecutorProtocol.installRootfs(connection.remote, root, source, it)
            }
            localManager.ensureWorkspace(root)
            localManager.writeText(root, "input.txt", "caller-data")
            val client = WorkspaceExecutorClient(context, localManager)
            val result = client.executeCommand(
                root = root,
                command = """
                    set -eu
                    test "$(cat input.txt)" = caller-data
                    test ! -e /skills
                    test ! -e /upload
                    test ! -e /tool_outputs
                    printf guest-change > output.txt
                    printf 'proot-ok'
                """.trimIndent(),
            )
            assertEquals(0, result.exitCode)
            assertEquals("proot-ok", result.stdout)
            assertEquals("caller-data", localManager.readText(root, "input.txt"))
            assertEquals("guest-change", localManager.readText(root, "output.txt"))
            assertFalse(client.identity().hasInternetPermission)

            val cancellationFailure = AtomicReference<Throwable>()
            val cancellationStarted = CountDownLatch(1)
            val commandThread = Thread {
                try {
                    cancellationStarted.countDown()
                    client.executeCommand(root, "sleep 600")
                } catch (error: Throwable) {
                    cancellationFailure.set(error)
                }
            }
            commandThread.start()
            assertTrue(cancellationStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(500)
            commandThread.interrupt()
            commandThread.join(5_000)
            assertFalse("Cancelled broker command did not stop", commandThread.isAlive)
            assertTrue(cancellationFailure.get() is InterruptedException)
            assertEquals(0, client.executeCommand(root, "true").exitCode)
            client.close()
        } finally {
            localManager.deleteWorkspace(root)
            WorkspaceExecutorProtocol.deleteWorkspace(connection.remote, root)
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
