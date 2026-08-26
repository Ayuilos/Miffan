package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files

class WorkspaceManagerExecutionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `shell accepts only canonical cwd within the selected scope`() {
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
        val scope = WorkspaceScope.assistant("assistant-a")
        val files = manager.ensureScope(root, scope).files
        File(files, "b").mkdirs()

        manager.executeCommand(root, command = "pwd", cwd = "b", scope = scope)

        assertEquals("b", receivedContext?.cwd)
        assertEquals(File(files, "b").toPath().toAbsolutePath().normalize(), receivedContext?.workingDir?.toPath())
        listOf("a/../b", "/workspace/b", "a//b", "./b").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                manager.executeCommand(root, command = "pwd", cwd = invalid, scope = scope)
            }
        }
    }

    @Test
    fun `commands from two assistant scopes in one workspace are serialized`() {
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

        fun commandThread(scope: WorkspaceScope) = Thread {
            runCatching { manager.executeCommand(root, "true", scope = scope) }
                .exceptionOrNull()
                ?.let(errors::add)
        }
        val first = commandThread(WorkspaceScope.assistant("assistant-a")).apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val second = commandThread(WorkspaceScope.assistant("assistant-b")).apply { start() }
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

    @Test
    fun `shell cwd refuses a symbolic link out of the assistant scope`() {
        val manager = WorkspaceManager(tmp.newFolder("symlink-workspaces"))
        val scope = WorkspaceScope.assistant("assistant-a")
        val files = manager.ensureScope("root", scope).files
        val outside = tmp.newFolder("outside-scope")
        try {
            Files.createSymbolicLink(File(files, "escape").toPath(), outside.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.executeCommand("root", "pwd", cwd = "escape", scope = scope)
        }

        assertTrue(error.message.orEmpty().contains("symbolic link"))
    }

    @Test
    fun `bind mounts cannot expose workspace storage or its parent`() {
        val parent = tmp.newFolder("bind-boundary")
        val base = File(parent, "workspaces").apply { mkdirs() }
        val scopeParent = File(base, "root/scopes").apply { mkdirs() }

        listOf(parent, base, scopeParent).forEach { source ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                WorkspaceManager(
                    baseDir = base,
                    bindMounts = listOf(WorkspaceBindMount(source, "/leak")),
                )
            }
            assertTrue(error.message.orEmpty().contains("must not overlap Workspace storage"))
        }
    }

    @Test
    fun `postflight marks a fast command that exceeds disk quota`() {
        val runner = object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
                File(context.filesDir, "too-large.bin").writeBytes(ByteArray(5))
                return WorkspaceCommandResult(exitCode = 0, stdout = "done", stderr = "")
            }
        }
        val manager = WorkspaceManager(
            baseDir = tmp.newFolder("postflight-workspaces"),
            config = WorkspaceConfig(
                resourceLimits = WorkspaceResourceLimits(
                    maxFilesBytes = 4,
                    maxRootfsBytes = 100,
                    maxTempBytes = 100,
                    maxWorkspaceBytes = 100,
                    minFreeSpaceBytes = 0,
                    maxToolOutputBytes = 10,
                    maxToolOutputFileBytes = 5,
                    maxShellFileBytes = 10,
                )
            ),
            shellRunner = runner,
        )
        manager.ensureWorkspace("root")

        val result = manager.executeCommand("root", "write quickly")

        assertTrue(result.resourceLimitExceeded)
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("Workspace files exceeds limit"))
    }
}
