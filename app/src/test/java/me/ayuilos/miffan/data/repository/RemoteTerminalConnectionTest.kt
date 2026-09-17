package me.ayuilos.miffan.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.db.dao.RemoteHostDAO
import me.ayuilos.miffan.data.db.dao.WorkspaceDAO
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.rerere.workspace.NativeSshWorkspaceTransport
import me.rerere.workspace.RemoteAuthentication
import me.rerere.workspace.RemoteTerminalSession
import me.rerere.workspace.RemoteWorkspaceSession
import me.rerere.workspace.RemoteFileTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTerminalConnectionTest {
    private val workspace = WorkspaceEntity(
        id = "ws", name = "Remote", root = "remote:ws", createdAt = 1, updatedAt = 1,
        kind = WorkspaceEntity.KIND_REMOTE, remoteHostId = "host", remotePath = "/srv/project",
    )
    private var host = RemoteHostEntity(
        id = "host", name = "Server", host = "server.internal", port = 22,
        username = "user", authType = "PASSWORD", connectionRevision = "v1",
        trustedHostKeySha256 = "SHA256:" + "A".repeat(43), createdAt = 1, updatedAt = 1,
    )
    private val dao = mockk<WorkspaceDAO>()
    private val hostDao = mockk<RemoteHostDAO>()
    private val credentials = mockk<RemoteHostCredentialStore>()
    private val transport = mockk<NativeSshWorkspaceTransport>()
    private val session = mockk<RemoteWorkspaceSession>(relaxed = true)
    private val terminal = mockk<RemoteTerminalSession>(relaxed = true)

    private fun repository(): WorkspaceRepository {
        coEvery { dao.getById("ws") } returns workspace
        coEvery { hostDao.getById("host") } answers { host }
        every { credentials.load("host") } returns RemoteAuthentication.Password("test")
        every { transport.open(any(), any(), any()) } returns session
        every { session.resolvedRoot } returns "/srv/project"
        every { session.openTerminal(any(), any()) } returns terminal
        return WorkspaceRepository(dao, mockk(), mockk(), mockk(), mockk(), hostDao,
            credentials, transport, mockk(), mockk())
    }

    @Test fun manualTerminalStaysActiveUntilClosedAndClosesOnlyOnce() = runBlocking {
        val repo = repository()
        val connection = repo.openRemoteTerminal("ws", expectedHostRevision = "v1", expectedRemoteRoot = "/srv/project")
        assertEquals(RemoteConnectionActivity.OPERATING, repo.remoteHostStates.value.getValue("host").activity)
        connection.resize(100, 30)
        verify(exactly = 1) { terminal.resize(100, 30) }
        connection.close()
        connection.close()
        verify(exactly = 1) { terminal.close() }
        assertEquals(RemoteConnectionActivity.IDLE, repo.remoteHostStates.value.getValue("host").activity)
    }

    @Test fun timedOutWriteReportsUnknownOutcomeAndClosesConnection() = runBlocking {
        val repo = repository()
        every { session.writeText(any(), any(), any()) } throws RemoteFileTimeoutException()
        val failure = runCatching { repo.writeText("ws", "file.txt", "data", true) }.exceptionOrNull()
        assertTrue(failure is RemoteFileTimeoutException)
        assertEquals(false, repo.remoteHostStates.value.getValue("host").lastConnection?.success)
        assertEquals(RemoteOperationOutcome.OUTCOME_UNKNOWN,
            repo.remoteWorkspaceStates.value.getValue("ws").lastOperation?.outcome)
        verify(exactly = 1) { session.close() }
        verify(exactly = 1) { session.writeText(any(), any(), any()) }
    }

    @Test fun reconnectCannotUseAnotherHostWithMatchingRevision() = runBlocking {
        val repo = repository()
        val failure = runCatching {
            repo.openRemoteTerminal("ws", expectedHostId = "previous-host", expectedHostRevision = "v1")
        }.exceptionOrNull()
        assertTrue(failure is WorkspaceToolTargetChangedException)
        verify(exactly = 0) { transport.open(any(), any(), any()) }
    }

    @Test fun reconnectCannotSilentlyUseEditedHostIdentity() = runBlocking {
        val repo = repository()
        host = host.copy(connectionRevision = "v2", username = "other")
        val failure = runCatching { repo.openRemoteTerminal("ws", expectedHostRevision = "v1") }.exceptionOrNull()
        assertTrue(failure is WorkspaceToolTargetChangedException)
        verify(exactly = 0) { transport.open(any(), any(), any()) }
    }

    @Test fun identityChangeDuringPtyHandshakeClosesBothResources() = runBlocking {
        val repo = repository()
        every { session.openTerminal(any(), any()) } answers {
            host = host.copy(connectionRevision = "v2")
            terminal
        }
        val failure = runCatching { repo.openRemoteTerminal("ws", expectedHostRevision = "v1") }.exceptionOrNull()
        assertTrue(failure is WorkspaceToolTargetChangedException)
        verify(exactly = 1) { terminal.close() }
        verify(exactly = 1) { session.close() }
        assertEquals(RemoteConnectionActivity.IDLE, repo.remoteHostStates.value.getValue("host").activity)
    }

    @Test fun untrustedHostCannotOpenManualTerminal() = runBlocking {
        val repo = repository()
        host = host.copy(trustedHostKeySha256 = null)
        assertTrue(runCatching { repo.openRemoteTerminal("ws") }.isFailure)
        verify(exactly = 0) { transport.open(any(), any(), any()) }
        assertEquals(RemoteConfigurationState.HOST_KEY_UNTRUSTED, repo.remoteHostStates.value.getValue("host").configuration)
    }
}
