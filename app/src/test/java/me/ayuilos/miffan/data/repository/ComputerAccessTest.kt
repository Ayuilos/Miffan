package me.ayuilos.miffan.data.repository

import com.jcraft.jsch.JSchException
import io.mockk.*
import kotlinx.coroutines.test.runTest
import me.ayuilos.miffan.data.db.dao.RemoteHostDAO
import me.ayuilos.miffan.data.db.dao.SshKeyDAO
import me.ayuilos.miffan.data.db.entity.RemoteHostAddress
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.SshKeyEntity
import me.ayuilos.miffan.data.db.entity.encodeRemoteHostAddresses
import me.rerere.workspace.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException

class ComputerAccessTest {
    private class Fixture {
        val material = SshKeyCodec.generate()
        val authentication = RemoteAuthentication.PrivateKey(material.privateKeyPem)
        val host = RemoteHostEntity("host", "Computer", "main", 22, "user", "MANAGED_KEY",
            sshKeyId = "key", trustedHostKeySha256 = "SHA256:" + "A".repeat(43), createdAt = 1, updatedAt = 1,
            alternateAddresses = encodeRemoteHostAddresses(listOf(RemoteHostAddress("alternate", 2222))))
        val hostDao = mockk<RemoteHostDAO>()
        val keyDao = mockk<SshKeyDAO>()
        val keyStore = mockk<SshKeyCredentialStore>()
        val transport = mockk<NativeSshWorkspaceTransport>()
        val session = mockk<RemoteWorkspaceSession>(relaxed = true)
        val repository = WorkspaceRepository(mockk(), mockk(), mockk(), mockk(), mockk(), hostDao,
            mockk(), transport, keyDao, keyStore, mockk(relaxed = true))

        init {
            coEvery { hostDao.getById("host") } returns host
            coEvery { hostDao.updateLastAddress(any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { keyDao.getById("key") } returns SshKeyEntity("key", "Miffan", material.algorithm,
                material.publicKey, material.fingerprint, 1, 1)
            every { keyStore.load("key") } returns authentication
            every { session.withOperation(any<RemoteWorkspaceSession.OperationHandle>(), any<() -> WorkspaceCommandResult>()) } answers {
                secondArg<() -> WorkspaceCommandResult>().invoke()
            }
        }
    }

    @Test fun `revocation tries saved addresses with the pin and dispatches once`() = runTest {
        val f = Fixture()
        every { f.transport.open(any(), any(), any()) } answers {
            val config = firstArg<RemoteHostConfig>()
            assertEquals(f.host.trustedHostKeySha256, config.trustedHostKeySha256)
            assertEquals(f.authentication, config.authentication)
            if (config.host == "main") throw ConnectException("Connection refused")
            assertEquals(2222, config.port)
            f.session
        }
        every { f.session.execute("revoke", any(), any(), any(), any()) } returns WorkspaceCommandResult(0, "", "")
        assertTrue(f.repository.revokeComputerAccess("host", "revoke"))
        verify(exactly = 2) { f.transport.open(any(), any(), any()) }
        verify(exactly = 1) { f.session.execute("revoke", any(), any(), any(), any()) }
        verify(exactly = 1) { f.session.close() }
    }

    @Test fun `only authentication failure opening the session is already gone`() = runTest {
        val f = Fixture()
        every { f.transport.open(any(), any(), any()) } throws RemoteConnectException("main", JSchException("Auth fail"))
        assertFalse(f.repository.revokeComputerAccess("host", "revoke"))
        verify(exactly = 1) { f.transport.open(any(), any(), any()) }
        verify(exactly = 0) { f.session.execute(any(), any(), any(), any(), any()) }
    }

    @Test fun `command failure is never mapped to authentication refusal or replayed`() = runTest {
        val f = Fixture()
        every { f.transport.open(any(), any(), any()) } returns f.session
        val failure = JSchException("Auth fail")
        every { f.session.execute(any(), any(), any(), any(), any()) } throws failure
        val error = runCatching { f.repository.revokeComputerAccess("host", "revoke") }.exceptionOrNull()
        assertTrue(error is JSchException)
        assertEquals(failure.message, error?.message)
        verify(exactly = 1) { f.transport.open(any(), any(), any()) }
        verify(exactly = 1) { f.session.close() }
        every { f.session.execute(any(), any(), any(), any(), any()) } returns WorkspaceCommandResult(1, "", "Cannot edit admin keys")
        assertTrue(runCatching { f.repository.revokeComputerAccess("host", "revoke") }.exceptionOrNull()?.message.orEmpty().contains("Cannot edit admin keys"))
    }

    @Test fun `repository probe passes only the verified key and performs no trust writes`() = runTest {
        for (outcome in KeySignInCheck.entries) {
            val f = Fixture()
            every { f.transport.checkKeySignIn("probe", 2222, "user", f.authentication, 6_000) } returns outcome
            assertEquals(outcome, f.repository.checkKeySignIn("probe", 2222, "user", "key"))
            coVerify(exactly = 0) { f.hostDao.getById(any()) }
            coVerify(exactly = 0) { f.hostDao.update(any()) }
            verify(exactly = 0) { f.transport.open(any(), any(), any()) }
        }
    }
    @Test fun `edited host cannot receive a command built for the previous identity`() = runTest {
        val f = Fixture()
        assertTrue(runCatching {
            f.repository.revokeComputerAccess("host", "revoke", expectedConnectionRevision = "previous")
        }.exceptionOrNull() is WorkspaceToolTargetChangedException)
        verify(exactly = 0) { f.transport.open(any(), any(), any()) }
    }

}
