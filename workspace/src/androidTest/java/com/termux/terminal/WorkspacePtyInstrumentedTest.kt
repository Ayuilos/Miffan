package com.termux.terminal

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import me.rerere.workspace.ProcfsWorkspaceProcessSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspacePtyInstrumentedTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    @Test
    fun launcherRejectsAParentThreadThatMayRetire() {
        val processId = intArrayOf(42)
        val task = FutureTask {
            runCatching {
                JNI.createSubprocess(
                    "/system/bin/sh",
                    context.cacheDir.absolutePath,
                    arrayOf("-c", "exit 0"),
                    hostEnvironment(),
                    processId,
                    24,
                    80,
                )
            }.exceptionOrNull()
        }
        Thread(task, "ShortLivedPtyLauncher").start()

        val error = task.get(5, TimeUnit.SECONDS)
        assertTrue(error is IllegalStateException)
        assertEquals(-1, processId[0])
    }

    @Test
    fun execFailureIsReportedBeforePublishingAPid() {
        val processId = intArrayOf(42)

        val error = assertThrows(IOException::class.java) {
            onMainThread {
                JNI.createSubprocess(
                    "/system/bin/rikkahub-missing-command",
                    context.cacheDir.absolutePath,
                    emptyArray(),
                    hostEnvironment(),
                    processId,
                    24,
                    80,
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("Unable to launch terminal process"))
        assertEquals(-1, processId[0])
    }

    @Test
    fun nulArgumentIsRejectedBeforeFork() {
        val processId = intArrayOf(42)

        assertThrows(IllegalArgumentException::class.java) {
            onMainThread {
                JNI.createSubprocess(
                    "/system/bin/sh",
                    context.cacheDir.absolutePath,
                    arrayOf("bad\u0000argument"),
                    hostEnvironment(),
                    processId,
                    24,
                    80,
                )
            }
        }

        assertEquals(-1, processId[0])
    }

    @Test
    fun normalExitPreservesStatusAndReapsTheCompletePtyGroup() {
        val childPidFile = File(context.cacheDir, "pty-child-pid-${System.nanoTime()}")
        val exitGate = File(context.cacheDir, "pty-exit-gate-${System.nanoTime()}")
        val processId = intArrayOf(-1)
        val environment = hostEnvironment() + arrayOf(
            "PTY_CHILD_PID_FILE=${childPidFile.absolutePath}",
            "PTY_EXIT_GATE=${exitGate.absolutePath}",
        )
        val masterFd = onMainThread {
            JNI.createSubprocess(
                "/system/bin/sh",
                context.cacheDir.absolutePath,
                arrayOf(
                    "-c",
                    "sleep 60 & echo \$! > \"\$PTY_CHILD_PID_FILE\"; " +
                        "while [ ! -e \"\$PTY_EXIT_GATE\" ]; do sleep 1; done; exit 9",
                ),
                environment,
                processId,
                24,
                80,
            )
        }
        val shellPid = processId[0].toLong()
        var reaped = false
        var waiter: FutureTask<Int>? = null
        try {
            assertTrue(masterFd >= 0)
            assertTrue(shellPid > 1)
            eventuallyFileExists(childPidFile)
            val childPid = childPidFile.readText().trim().toLong()
            val processSystem = ProcfsWorkspaceProcessSystem()
            val shell = eventuallySnapshot(processSystem, shellPid)
            val child = eventuallySnapshot(processSystem, childPid)

            assertEquals(shellPid, shell.processGroupId)
            assertEquals(shellPid, child.processGroupId)
            waiter = FutureTask { JNI.waitFor(shellPid.toInt()) }
            Thread(waiter, "WorkspacePtyWaiter").start()
            exitGate.writeText("exit")
            val exitStatus = waiter.get(5, TimeUnit.SECONDS)
            reaped = true
            assertEquals(9, exitStatus)
            eventuallyProcessIsGone(processSystem, childPid)
        } finally {
            exitGate.writeText("exit")
            if (!reaped && shellPid > 1) {
                runCatching { Os.kill(-shellPid.toInt(), OsConstants.SIGKILL) }
                if (waiter == null) {
                    runCatching { JNI.waitFor(shellPid.toInt()) }
                } else {
                    runCatching { waiter.get(5, TimeUnit.SECONDS) }
                }
            }
            JNI.close(masterFd)
            childPidFile.delete()
            exitGate.delete()
        }
    }

    @Test
    fun normalExitReapsAChildThatCreatedANewSession() {
        val toybox = File("/system/bin/toybox")
        val sleep = File("/system/bin/sleep")
        org.junit.Assume.assumeTrue(toybox.canExecute() && sleep.canExecute())
        val childPidFile = File(context.cacheDir, "pty-setsid-child-${System.nanoTime()}")
        val exitGate = File(context.cacheDir, "pty-setsid-gate-${System.nanoTime()}")
        val processId = intArrayOf(-1)
        val environment = hostEnvironment() + arrayOf(
            "PTY_CHILD_PID_FILE=${childPidFile.absolutePath}",
            "PTY_EXIT_GATE=${exitGate.absolutePath}",
        )
        val masterFd = onMainThread {
            JNI.createSubprocess(
                "/system/bin/sh",
                context.cacheDir.absolutePath,
                arrayOf(
                    "-c",
                    "${toybox.absolutePath} setsid ${sleep.absolutePath} 60 " +
                        ">/dev/null 2>&1 & echo \$! > \"\$PTY_CHILD_PID_FILE\"; " +
                        "while [ ! -e \"\$PTY_EXIT_GATE\" ]; do sleep 1; done; exit 9",
                ),
                environment,
                processId,
                24,
                80,
            )
        }
        val shellPid = processId[0].toLong()
        var reaped = false
        var waiter: FutureTask<Int>? = null
        try {
            eventuallyFileExists(childPidFile)
            val childPid = childPidFile.readText().trim().toLong()
            val processSystem = ProcfsWorkspaceProcessSystem()
            val child = eventuallySnapshot(processSystem, childPid)
            assertEquals(childPid, child.processGroupId)
            assertTrue(child.processGroupId != shellPid)

            waiter = FutureTask { JNI.waitFor(shellPid.toInt()) }
            Thread(waiter, "WorkspacePtySetsidWaiter").start()
            exitGate.writeText("exit")
            assertEquals(9, waiter.get(5, TimeUnit.SECONDS))
            reaped = true
            eventuallyProcessIsGone(processSystem, childPid)
        } finally {
            exitGate.writeText("exit")
            if (!reaped && shellPid > 1) {
                runCatching { Os.kill(-shellPid.toInt(), OsConstants.SIGKILL) }
                if (waiter == null) {
                    runCatching { JNI.waitFor(shellPid.toInt()) }
                } else {
                    runCatching { waiter.get(5, TimeUnit.SECONDS) }
                }
            }
            JNI.close(masterFd)
            childPidFile.delete()
            exitGate.delete()
        }
    }

    private fun hostEnvironment(): Array<String> = System.getenv()
        .map { (name, value) -> "$name=$value" }
        .toTypedArray()

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

    private fun eventuallyFileExists(file: File) {
        repeat(100) {
            if (file.isFile && file.length() > 0) return
            Thread.sleep(20)
        }
        assertTrue("PTY child pid was not published", file.isFile && file.length() > 0)
    }

    private fun eventuallySnapshot(
        system: ProcfsWorkspaceProcessSystem,
        pid: Long,
    ) = run {
        repeat(100) {
            system.snapshot(pid)?.let { return@run it }
            Thread.sleep(10)
        }
        requireNotNull(system.snapshot(pid)) { "Process $pid did not appear in procfs" }
    }

    private fun eventuallyProcessIsGone(system: ProcfsWorkspaceProcessSystem, pid: Long) {
        repeat(100) {
            if (system.snapshot(pid) == null) return
            Thread.sleep(10)
        }
        assertNull("Process $pid is still present in procfs", system.snapshot(pid))
    }
}
