package me.rerere.workspace

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.JNI
import java.io.ByteArrayOutputStream
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
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    @Test
    fun pinnedRootfsExecutesInTheApplicationUidWhenProvisioned() {
        val source = RootfsCatalog.forAndroidAbis(Build.SUPPORTED_ABIS.toList())
        val archive = context.cacheDir.resolve("provisioned-rootfs.${source.format.extension}")
        assumeTrue("Pinned Rootfs archive was not provisioned for the device test", archive.isFile)

        val baseDir = context.cacheDir.resolve("in-process-proot-${System.nanoTime()}")
        val manager = WorkspaceManager(
            baseDir = baseDir,
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
                hostPageSizeBytes = AndroidPageSize.currentBytes(),
            ),
        )
        val installer = RootfsInstaller(
            manager = manager,
            hostPageSizeBytes = AndroidPageSize.currentBytes(),
        )
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

    @Test
    fun pinnedRootfsStartsThroughTheInteractivePtyWhenProvisioned() {
        val source = RootfsCatalog.forAndroidAbis(Build.SUPPORTED_ABIS.toList())
        val archive = context.cacheDir.resolve("provisioned-rootfs.${source.format.extension}")
        assumeTrue("Pinned Rootfs archive was not provisioned for the device test", archive.isFile)

        val baseDir = context.cacheDir.resolve("interactive-proot-${System.nanoTime()}")
        val manager = WorkspaceManager(baseDir)
        val installer = RootfsInstaller(
            manager = manager,
            hostPageSizeBytes = AndroidPageSize.currentBytes(),
        )
        val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = nativeLibraryDir.resolve("libproot_exec.so")
        val loader = nativeLibraryDir.resolve("libproot_loader.so")
        val root = "interactive"
        var processId = -1
        var terminalFd: ParcelFileDescriptor? = null
        var reaped = false
        try {
            FileInputStream(archive).use {
                installer.installFromArchive(root, source, it)
            }
            val args = ProotExecutionSpec.interactiveArguments(
                root = root,
                linuxDir = manager.linuxDir(root),
                filesDir = manager.filesDir(root),
            )
            val environment = ProotExecutionSpec.hostEnvironment(loader, manager.tempDir(root))
                .map { (name, value) -> "$name=$value" }
                .toTypedArray()
            val processIdOutput = intArrayOf(-1)
            val masterFd = onMainThread {
                JNI.createSubprocess(
                    proot.absolutePath,
                    manager.filesDir(root).absolutePath,
                    args.toTypedArray(),
                    environment,
                    processIdOutput,
                    24,
                    80,
                )
            }
            processId = processIdOutput[0]
            assertTrue(masterFd >= 0 && processId > 1)

            val descriptor = ParcelFileDescriptor.adoptFd(masterFd)
            terminalFd = descriptor
            val readDescriptor = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
            val writeDescriptor = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
            val output = ByteArrayOutputStream()
            val outputFinished = CountDownLatch(1)
            Thread({
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(readDescriptor).use { input ->
                        val buffer = ByteArray(4 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            synchronized(output) { output.write(buffer, 0, count) }
                        }
                    }
                } catch (_: Throwable) {
                    // A PTY normally reports EIO after its slave closes.
                } finally {
                    outputFinished.countDown()
                }
            }, "WorkspaceInteractivePtyOutput").apply {
                isDaemon = true
                start()
            }

            ParcelFileDescriptor.AutoCloseOutputStream(writeDescriptor).use { outputStream ->
                outputStream.write("printf 'terminal-16k-ok\\n'; exit 0\n".toByteArray())
                outputStream.flush()
            }
            val exitCode = JNI.waitFor(processId)
            reaped = true
            descriptor.close()
            terminalFd = null
            assertTrue(outputFinished.await(5, TimeUnit.SECONDS))
            val terminalOutput = synchronized(output) { output.toString(Charsets.UTF_8.name()) }
            assertEquals("PTY output: $terminalOutput", 0, exitCode)
            assertTrue("PTY output: $terminalOutput", terminalOutput.contains("terminal-16k-ok"))
        } finally {
            terminalFd?.close()
            if (!reaped && processId > 1) {
                runCatching { Os.kill(-processId, OsConstants.SIGKILL) }
                runCatching { JNI.waitFor(processId) }
            }
            manager.deleteWorkspace(root)
            baseDir.deleteRecursivelyNoFollow()
        }
    }

    private fun <T : Any> onMainThread(block: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                result.set(block())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw it }
        return requireNotNull(result.get())
    }
}
