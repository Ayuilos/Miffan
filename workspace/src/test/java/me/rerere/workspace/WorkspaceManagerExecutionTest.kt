package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WorkspaceManagerExecutionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `shell receives canonical workspace relative cwd`() {
        var receivedContext: WorkspaceShellContext? = null
        val runner = object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
                receivedContext = context
                return WorkspaceCommandResult(exitCode = 0, stdout = "", stderr = "")
            }
        }
        val manager = WorkspaceManager(tmp.newFolder("workspaces"), shellRunner = runner)
        val root = "root"
        manager.ensureWorkspace(root)
        File(manager.filesDir(root), "b").mkdirs()

        manager.executeCommand(root, command = "pwd", cwd = "a/../b")

        assertEquals("b", receivedContext?.cwd)
        assertEquals(File(manager.filesDir(root), "b").canonicalFile, receivedContext?.workingDir)
    }

    @Test
    fun `commands in one workspace are serialized`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val runner = object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
                calls.incrementAndGet()
                val nowActive = active.incrementAndGet()
                maxActive.updateAndGet { current -> maxOf(current, nowActive) }
                entered.countDown()
                try {
                    check(release.await(2, TimeUnit.SECONDS)) { "Test command was not released" }
                } finally {
                    active.decrementAndGet()
                }
                return WorkspaceCommandResult(exitCode = 0, stdout = "", stderr = "")
            }
        }
        val manager = WorkspaceManager(tmp.newFolder("serialized-workspaces"), shellRunner = runner)
        val root = "root"
        manager.ensureWorkspace(root)

        fun commandThread() = Thread {
            runCatching { manager.executeCommand(root, "true") }
                .exceptionOrNull()
                ?.let(errors::add)
        }
        val first = commandThread().apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val second = commandThread().apply { start() }
        Thread.sleep(100)

        assertEquals(1, calls.get())
        assertTrue(second.isAlive)
        release.countDown()
        first.join(2_000)
        second.join(2_000)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(2, calls.get())
        assertEquals(1, maxActive.get())
    }
}
