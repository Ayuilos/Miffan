package me.ayuilos.miffan.data.repository

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RemoteConversationShellsTest {
    private val directory = Files.createTempDirectory("miffan-chat-shell").toFile()
    private val processes = CopyOnWriteArrayList<Process>()
    private val permitted = AtomicBoolean(true)
    private val target = WorkspaceToolTargetSnapshot("assistant", "permission", "workspace", null, "REMOTE",
        remoteHostId = "host", remoteRoot = directory.path, hostConnectionRevision = "host-v1",
        workspaceName = "Remote", conversationId = "chat-a")
    private val pool = RemoteConversationShells(open = {
        val process = ProcessBuilder("/bin/sh").directory(directory).redirectErrorStream(true).start()
        processes.add(process)
        mockk<RemoteTerminalConnection>(relaxed = true).also { connection ->
            every { connection.input } returns process.inputStream
            every { connection.output } returns process.outputStream
            every { connection.isConnected } answers { process.isAlive }
            every { connection.close() } answers { process.destroyForcibly(); Unit }
        }
    }, validate = { check(permitted.get()) { "Target changed" } })

    private suspend fun execute(command: String, target: WorkspaceToolTargetSnapshot = this.target) =
        pool.execute(target, command, null, null, 5_000)

    @After fun cleanup() = runBlocking {
        pool.closeAssistant("assistant")
        processes.forEach { it.destroyForcibly() }
        directory.deleteRecursively()
        Unit
    }

    @Test fun manualInputThenAgentCommandShareShellButAnotherChatDoesNot() = runBlocking {
        val ready = CompletableDeferred<RemoteConversationShellHandle>()
        val manual = async {
            pool.execute(target, "read -r value; export SESSION_VALUE=\"\$value\"; printf '%s' \"\$\$\"",
                null, null, 5_000, onReady = { ready.complete(it) })
        }
        val handle = withTimeout(5_000) { ready.await() }
        assertEquals("read -r value; export SESSION_VALUE=\"\$value\"; printf '%s' \"\$\$\"",
            pool.states.value["chat-a"]?.activeCommand?.command)
        handle.inputWriter().invoke("authorized\n".toByteArray())
        val first = withTimeout(5_000) { manual.await() }
        assertNull(pool.states.value["chat-a"]?.activeCommand)
        val next = execute("printf '%s|%s' \"\$\$\" \"\$SESSION_VALUE\"")
        assertEquals("${first.result.stdout}|authorized", next.result.stdout)
        assertEquals(first.sessionId, next.sessionId)
        assertTrue(next.reused)
        assertTrue(next.sessionOpen)
        val other = execute("printf '%s' \"\${SESSION_VALUE-unset}\"", target.copy(conversationId = "chat-b"))
        assertEquals("unset", other.result.stdout)
        assertNotEquals(first.sessionId, other.sessionId)
        assertEquals(2, processes.size)
    }

    @Test fun closingSessionClearsStateAndStaleCloseCannotEndReplacement() = runBlocking {
        val first = execute("export MARK=old")
        assertEquals(first.sessionId, pool.states.value["chat-a"]?.sessionId)
        pool.close("chat-a", first.sessionId)
        assertTrue(pool.states.value.isEmpty())
        val next = execute("printf '%s' \"\${MARK-unset}\"")
        assertEquals("unset", next.result.stdout)
        assertFalse(next.reused)
        assertNotEquals(first.sessionId, next.sessionId)
        pool.close("chat-a", first.sessionId)
        assertEquals(next.sessionId, execute("true").sessionId)
    }

    @Test fun targetRevisionChangeOpensFreshSession() = runBlocking {
        val first = execute("export MARK=old")
        val next = execute("printf '%s' \"\${MARK-unset}\"", target.copy(hostConnectionRevision = "host-v2"))
        assertEquals("unset", next.result.stdout)
        assertNotEquals(first.sessionId, next.sessionId)
        assertFalse(next.reused)
        assertFalse(processes.first().isAlive)
    }

    @Test fun staleRequestCannotCloseAReplacementForAnotherTarget() = runBlocking {
        execute("true")
        val replacement = execute("true", target.copy(hostConnectionRevision = "host-v2"))
        permitted.set(false)
        assertTrue(runCatching { execute("true") }.isFailure)
        assertEquals(replacement.sessionId, pool.states.value["chat-a"]?.sessionId)
        permitted.set(true)
        assertEquals(replacement.sessionId, execute("true", target.copy(hostConnectionRevision = "host-v2")).sessionId)
    }

    @Test fun cancellationOfQueuedCallDoesNotCloseInteractiveCommand() = runBlocking {
        val ready = CompletableDeferred<RemoteConversationShellHandle>()
        val active = async {
            pool.execute(target, "read -r value; printf '%s' \"\$value\"", null, null, 5_000,
                onReady = { ready.complete(it) })
        }
        val handle = withTimeout(5_000) { ready.await() }
        val queued = async { execute("printf unwanted") }
        queued.cancelAndJoin()
        handle.inputWriter().invoke("finished\n".toByteArray())
        assertEquals("finished", withTimeout(5_000) { active.await() }.result.stdout)
        assertTrue(execute("true").reused)
    }

    @Test fun disabledTargetCannotDispatchQueuedCommand() = runBlocking {
        val ready = CompletableDeferred<RemoteConversationShellHandle>()
        val active = async {
            pool.execute(target, "read -r value", null, null, 5_000, onReady = { ready.complete(it) })
        }
        val handle = withTimeout(5_000) { ready.await() }
        val queued = async { runCatching { execute("touch should-not-exist") } }
        permitted.set(false)
        handle.inputWriter().invoke("done\n".toByteArray())
        withTimeout(5_000) { active.await() }
        assertTrue(withTimeout(5_000) { queued.await() }.isFailure)
        assertFalse(directory.resolve("should-not-exist").exists())
    }
}
