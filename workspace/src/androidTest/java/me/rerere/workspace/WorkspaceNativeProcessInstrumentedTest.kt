package me.rerere.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceNativeProcessInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun launcherPreservesUtf8EnvironmentAndExitStatus() {
        val process = WorkspaceNativeProcess.start(
            command = listOf(
                "/system/bin/sh",
                "-c",
                "printf '%s' \"\$WORKSPACE_NATIVE_TEST\"; exit 7",
            ),
            environment = System.getenv() + ("WORKSPACE_NATIVE_TEST" to "启动成功"),
            workingDirectory = context.cacheDir,
        )
        process.outputStream.close()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }

        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(7, process.exitValue())
        assertEquals("启动成功", stdout)
    }

    @Test
    fun forceTerminationRemovesTheCompleteProcessGroup() {
        val process = WorkspaceNativeProcess.start(
            command = listOf(
                "/system/bin/sh",
                "-c",
                "sleep 60 & echo \$!; wait",
            ),
            environment = System.getenv(),
            workingDirectory = context.cacheDir,
        )
        process.outputStream.close()
        try {
            val childPid = process.inputStream.bufferedReader().readLine().trim().toLong()
            val commandGroupPid = process.nativePid()
            val monitorPid = process.nativeMonitorPid()
            val system = ProcfsWorkspaceProcessSystem()
            val monitor = system.snapshot(monitorPid)
            val command = system.snapshot(commandGroupPid)
            val child = system.snapshot(childPid)

            assertNotNull(monitor)
            assertNotNull(command)
            assertNotNull(child)
            assertEquals(WorkspaceNativeProcess.PROCESS_NAME, monitor?.processName)
            assertEquals(monitorPid, monitor?.processGroupId)
            assertEquals(commandGroupPid, command?.processGroupId)
            assertEquals(commandGroupPid, child?.processGroupId)

            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            eventuallyProcessIsGone(childPid)
            assertFalse(process.isAlive)
        } finally {
            forceStop(process)
        }
    }

    @Test
    fun normalExitPreservesStatusAndReapsBackgroundProcesses() {
        val process = WorkspaceNativeProcess.start(
            command = listOf(
                "/system/bin/sh",
                "-c",
                "sleep 60 & echo \$!; exit 3",
            ),
            environment = System.getenv(),
            workingDirectory = context.cacheDir,
        )
        process.outputStream.close()
        val childPid = process.inputStream.bufferedReader().readLine().trim().toLong()

        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(3, process.exitValue())
        eventuallyProcessIsGone(childPid)
    }

    @Test
    fun callerThreadExitDoesNotTriggerParentDeathCleanup() {
        val future = FutureTask {
            WorkspaceNativeProcess.start(
                command = listOf("/system/bin/sh", "-c", "sleep 60"),
                environment = System.getenv(),
                workingDirectory = context.cacheDir,
            )
        }
        val caller = Thread(future, "ShortLivedWorkspaceCaller")
        caller.start()
        val process = future.get(5, TimeUnit.SECONDS)
        caller.join(5_000)

        try {
            assertFalse(caller.isAlive)
            Thread.sleep(100)
            assertTrue(process.isAlive)
        } finally {
            forceStop(process)
        }
    }

    @Test
    fun concurrentLaunchesUseIndependentCommandGroups() {
        val first = FutureTask {
            WorkspaceNativeProcess.start(
                command = listOf("/system/bin/sh", "-c", "sleep 1; exit 11"),
                environment = System.getenv(),
                workingDirectory = context.cacheDir,
            )
        }
        val second = FutureTask {
            WorkspaceNativeProcess.start(
                command = listOf("/system/bin/sh", "-c", "sleep 1; exit 12"),
                environment = System.getenv(),
                workingDirectory = context.cacheDir,
            )
        }
        val firstCaller = Thread(first, "FirstWorkspaceCaller")
        val secondCaller = Thread(second, "SecondWorkspaceCaller")
        firstCaller.start()
        secondCaller.start()
        val firstProcess = first.get(5, TimeUnit.SECONDS)
        val secondProcess = second.get(5, TimeUnit.SECONDS)
        firstProcess.outputStream.close()
        secondProcess.outputStream.close()

        assertTrue(firstProcess.nativePid() != secondProcess.nativePid())
        assertTrue(firstProcess.waitFor(5, TimeUnit.SECONDS))
        assertTrue(secondProcess.waitFor(5, TimeUnit.SECONDS))
        assertEquals(11, firstProcess.exitValue())
        assertEquals(12, secondProcess.exitValue())
    }

    private fun eventuallyProcessIsGone(pid: Long) {
        val system = ProcfsWorkspaceProcessSystem()
        repeat(100) {
            if (system.snapshot(pid) == null) return
            Thread.sleep(10)
        }
        assertNull(system.snapshot(pid))
    }

    private fun forceStop(process: WorkspaceNativeProcess) {
        if (runCatching { process.isAlive }.getOrDefault(false)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }
}
