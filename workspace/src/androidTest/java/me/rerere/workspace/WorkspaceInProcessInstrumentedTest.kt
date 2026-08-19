package me.rerere.workspace

import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceInProcessInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pinnedRootfsExecutesInTheApplicationUidWhenProvisioned() {
        val source = RootfsCatalog.forAndroidAbis(Build.SUPPORTED_ABIS.toList())
        val archive = context.cacheDir.resolve("provisioned-rootfs.${source.format.extension}")
        assumeTrue("Pinned Rootfs archive was not provisioned for the device test", archive.isFile)

        val baseDir = context.cacheDir.resolve("in-process-proot-${System.nanoTime()}")
        val manager = WorkspaceManager(
            baseDir = baseDir,
            shellRunner = ProotShellRunner(File(context.applicationInfo.nativeLibraryDir)),
        )
        val installer = RootfsInstaller(manager)
        val root = "in-process"
        try {
            FileInputStream(archive).use {
                installer.installFromArchive(root, source, it)
            }
            manager.writeText(root, "input.txt", "app-data")

            val result = manager.executeCommand(
                root = root,
                command = """
                    set -eu
                    test "$(cat input.txt)" = app-data
                    test ! -e /skills
                    test ! -e /upload
                    test ! -e /tool_outputs
                    printf guest-change > output.txt
                    printf 'guest_uid=%s kernel_uid=%s' \
                        "$(id -u)" \
                        "$(awk '/^Uid:/{print $2}' /proc/self/status)"
                """.trimIndent(),
            )
            assertEquals(0, result.exitCode)
            assertEquals("guest_uid=0 kernel_uid=${Process.myUid()}", result.stdout)
            assertEquals("app-data", manager.readText(root, "input.txt"))
            assertEquals("guest-change", manager.readText(root, "output.txt"))

            val cancellationFailure = AtomicReference<Throwable>()
            val cancellationStarted = CountDownLatch(1)
            val commandThread = Thread {
                try {
                    cancellationStarted.countDown()
                    manager.executeCommand(root, "sleep 600")
                } catch (error: Throwable) {
                    cancellationFailure.set(error)
                }
            }
            commandThread.start()
            assertTrue(cancellationStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(500)
            commandThread.interrupt()
            commandThread.join(5_000)
            assertFalse("Cancelled in-process command did not stop", commandThread.isAlive)
            assertTrue(cancellationFailure.get() is InterruptedException)
            assertEquals(0, manager.executeCommand(root, "true").exitCode)
        } finally {
            manager.deleteWorkspace(root)
            baseDir.deleteRecursivelyNoFollow()
        }
    }
}
