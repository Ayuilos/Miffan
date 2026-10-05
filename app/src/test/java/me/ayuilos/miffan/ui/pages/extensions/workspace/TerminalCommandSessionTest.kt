package me.ayuilos.miffan.ui.pages.extensions.workspace

import android.content.Context
import android.content.SharedPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.ayuilos.miffan.data.repository.RemoteConversationShellHandle
import me.ayuilos.miffan.data.repository.RemoteConversationCommandResult
import me.rerere.workspace.WorkspaceCommandResult
import io.mockk.verify
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import me.rerere.workspace.RemoteTerminalCommandSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TerminalCommandSessionTest {
    private val receipts = mutableMapOf<String, String>()
    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val repository = mockk<WorkspaceRepository>()
    private val screen = mockk<RemoteTerminalScreen>(relaxed = true)
    private val target = WorkspaceToolTargetSnapshot("assistant", "revision", "workspace", null,
        "REMOTE", remoteHostId = "host", remoteRoot = "/srv/project", hostConnectionRevision = "v1", workspaceName = "Project", conversationId = "chat")
    private val spec = RemoteTerminalCommandSpec("sudo example", "")

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { context.getString(any()) } returns "Press Enter to run."
        every { preferences.getString(any(), any()) } answers { receipts[firstArg()] }
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            receipts[firstArg()] = secondArg()
            editor
        }
        every { editor.commit() } returns true
        every { editor.apply() } returns Unit
    }

    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun openingAndTypingCannotExecuteAndCancelReportsOnce() = runBlocking {
        val results = mutableListOf<String>()
        val session = TerminalCommandSession("id", context, this, repository, target, spec, results::add, screen)
        session.write("password or pasted\ncommand".toByteArray())
        assertEquals(TerminalCommandState.READY, session.state.value)
        coVerify(exactly = 0) { repository.executeInConversationTerminal(any(), any(), any(), any(), any(), any(), any()) }
        session.cancel()
        session.cancel()
        assertEquals(1, results.size)
        assertTrue(results.single().contains("cancelled"))
    }

    @Test fun enterExecutesOnceAndReturnsFailureExitStatusWithoutStdin() = runBlocking {
        val handle = mockk<RemoteConversationShellHandle>(relaxed = true)
        coEvery { repository.executeInConversationTerminal(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            assertEquals("started", receipts["id"])
            arg<suspend (RemoteConversationShellHandle) -> Unit>(6).invoke(handle)
            arg<suspend (ByteArray) -> Unit>(5).invoke("command failed\n".toByteArray())
            RemoteConversationCommandResult(WorkspaceCommandResult(7, "command failed\n", ""), "shell", false, true)
        }
        val results = mutableListOf<String>()
        val session = TerminalCommandSession("id", context, this, repository, target, spec, results::add, screen)
        session.write(byteArrayOf(13))
        session.write(byteArrayOf(13))
        withTimeout(5_000) { session.state.first { it == TerminalCommandState.FINISHED } }
        coVerify(exactly = 1) { repository.executeInConversationTerminal(target, spec.command, null, null, null, any(), any()) }
        assertEquals(1, results.size)
        assertTrue(results.single().contains("\"exitCode\":7"))
        assertTrue(results.single().contains("command failed"))
        assertTrue(results.single().contains("\"sessionOpen\":true"))
        verify(exactly = 0) { handle.close() }
        session.write(byteArrayOf(13))
        assertEquals(1, results.size)
    }

    @Test fun processRestartNeverRerunsStartedCommandAndDeliversUnknownOutcome() = runBlocking {
        receipts["id"] = "started"
        val results = mutableListOf<String>()
        val session = TerminalCommandSession("id", context, this, repository, target, spec, results::add, screen)
        session.write(byteArrayOf(13))
        session.reportRecoveredResult()
        session.reportRecoveredResult()
        assertEquals(1, results.size)
        assertTrue(results.single().contains("interrupted"))
        coVerify(exactly = 0) { repository.executeInConversationTerminal(any(), any(), any(), any(), any(), any(), any()) }
        // A result ignored while another branch was selected can be delivered when revisited.
        session.attachResultHandler(results::add)
        session.reportRecoveredResult()
        assertEquals(2, results.size)
    }
}
