package me.ayuilos.miffan.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.db.dao.RemoteHostDAO
import me.ayuilos.miffan.data.db.entity.RemoteHostAddress
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.rerere.workspace.NativeSshWorkspaceTransport
import me.rerere.workspace.RemoteAuthentication
import me.rerere.workspace.RemoteConnectException
import me.rerere.workspace.RemoteHostKey
import me.rerere.workspace.RemotePasswordSignInFailure
import me.rerere.workspace.RemoteWorkspaceSession
import com.jcraft.jsch.JSchException
import java.net.ConnectException
import org.junit.Assert.*
import org.junit.Test

class HostAddressesTest {
    private val pin = "SHA256:" + "A".repeat(43)
    private var host: RemoteHostEntity? = RemoteHostEntity("id", "Computer", "192.168.1.5", 22,
        "user", "PASSWORD", "revision", trustedHostKeySha256 = pin, createdAt = 1, updatedAt = 2)
    private val hosts = mockk<RemoteHostDAO>()
    private val transport = mockk<NativeSshWorkspaceTransport>()
    private val credentials = mockk<RemoteHostCredentialStore>()
    private val repo: WorkspaceRepository
    init {
        coEvery { hosts.getById("id") } answers { host }
        coEvery { hosts.compareAndSetAddresses(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val row = host
            if (row == null || row.host != arg<String>(5) || row.port != arg<Int>(6) ||
                row.alternateAddresses != arg<String>(7) || row.lastAddressValue != arg<String?>(8) ||
                row.connectionRevision != arg<String>(9) || row.trustedHostKeySha256 != arg<String?>(10)) 0
            else {
                host = row.copy(host = arg(1), port = arg(2), alternateAddresses = arg(3), lastAddressValue = arg(4))
                1
            }
        }
        coEvery { hosts.updateLastAddress(any(), any(), any(), any(), any(), any()) } answers {
            host = host?.copy(lastAddressValue = arg(1)); 1
        }
        coEvery { hosts.updateDetectedPlatform(any(), any(), any()) } returns 1
        every { credentials.load("id") } returns RemoteAuthentication.Password("password")
        repo = WorkspaceRepository(mockk(), mockk(), mockk(), mockk(), mockk(), hosts, credentials,
            transport, mockk(), mockk(), mockk(relaxed = true))
    }

    @Test fun addDeduplicatesAndRemovePromotesWithoutChangingIdentity() = runBlocking {
        assertTrue(repo.addHostAddress("id", " 100.64.0.5 ", 2222))
        assertTrue(repo.addHostAddress("id", "100.64.0.5", 2222))
        assertTrue(repo.addHostAddress("id", "192.168.1.5", 22))
        assertEquals(2, host!!.addresses.size)
        host = host!!.copy(lastAddressValue = "192.168.1.5:22")
        assertTrue(repo.removeHostAddress("id", "192.168.1.5", 22))
        assertEquals("100.64.0.5", host!!.host)
        assertEquals(2222, host!!.port)
        assertEquals("[]", host!!.alternateAddresses)
        assertNull(host!!.lastAddress)
        assertFalse(repo.removeHostAddress("id", "100.64.0.5", 2222))
        assertEquals("revision", host!!.connectionRevision)
        assertEquals(pin, host!!.trustedHostKeySha256)
        assertEquals(2L, host!!.updatedAt)
        coVerify(exactly = 0) { hosts.updateConnection(any()) }
    }

    @Test fun removeAlternateClearsLastAndKeepsOtherAddresses() = runBlocking {
        repo.addHostAddress("id", "100.64.0.5", 22)
        repo.addHostAddress("id", "fe80::1", 2222)
        host = host!!.copy(lastAddressValue = "[fe80::1]:2222")
        assertEquals(RemoteHostAddress("fe80::1", 2222), host!!.lastAddress)
        assertTrue(repo.removeHostAddress("id", "fe80::1", 2222))
        assertNull(host!!.lastAddress)
        assertEquals(listOf(RemoteHostAddress("192.168.1.5", 22), RemoteHostAddress("100.64.0.5", 22)), host!!.addresses)
    }

    @Test fun learningRequiresExistingMatchingPin() = runBlocking {
        assertFalse(repo.learnHostAddress("id", "100.64.0.5", 22, "other"))
        assertEquals(1, host!!.addresses.size)
        assertTrue(repo.learnHostAddress("id", "100.64.0.5", 22, pin))
        assertEquals("revision", host!!.connectionRevision)
        assertEquals(pin, host!!.trustedHostKeySha256)
        host = host!!.copy(trustedHostKeySha256 = null)
        assertFalse(repo.learnHostAddress("id", "100.64.0.6", 22, pin))
        assertNull(host!!.trustedHostKeySha256)
    }

    @Test fun missingHostsReturnFalseAndInvalidAddressesAreRejected() = runBlocking {
        for ((name, port) in listOf("" to 22, "bad host" to 22, "host" to 0, "host" to 65536, "bad\u0000host" to 22)) {
            assertTrue(runCatching { repo.addHostAddress("id", name, port) }.exceptionOrNull() is IllegalArgumentException)
        }
        host = null
        assertFalse(repo.addHostAddress("id", "host", 22))
        assertFalse(repo.removeHostAddress("id", "host", 22))
        assertFalse(repo.learnHostAddress("id", "host", 22, pin))
    }

    @Test fun connectionFallsBackAndRecordsSuccessThenUsesLastAddress() = runBlocking {
        repo.addHostAddress("id", "100.64.0.5", 22)
        val session = mockk<RemoteWorkspaceSession>(relaxed = true)
        every { session.platform } returns me.rerere.workspace.RemotePlatform.WINDOWS
        every { session.homeDirectory } returns "/C:/Users/user"
        val attempted = mutableListOf<String>()
        every { transport.open(any(), any(), any()) } answers {
            val config = firstArg<me.rerere.workspace.RemoteHostConfig>()
            attempted += config.host
            assertEquals(pin, config.trustedHostKeySha256)
            if (config.host == "192.168.1.5") throw ConnectException("Connection refused")
            session
        }
        assertEquals("/C:/Users/user", repo.remoteHome("id"))
        assertEquals(listOf("192.168.1.5", "100.64.0.5"), attempted)
        assertEquals(RemoteHostAddress("100.64.0.5", 22), host!!.lastAddress)
        attempted.clear()
        repo.remoteHome("id")
        assertEquals(listOf("100.64.0.5"), attempted)
        assertEquals("revision", host!!.connectionRevision)
    }

    @Test fun concurrentAddressEditsDoNotResurrectRemovedLastAddress() = runBlocking {
        repo.addHostAddress("id", "100.64.0.5", 22)
        val session = mockk<RemoteWorkspaceSession>(relaxed = true)
        every { session.platform } returns me.rerere.workspace.RemotePlatform.WINDOWS
        every { session.homeDirectory } returns "/C:/Users/user"
        every { transport.open(any(), any(), any()) } answers {
            host = host!!.copy(host = "100.64.0.5", alternateAddresses = "[]")
            session
        }
        repo.remoteHome("id")
        assertNull(host!!.lastAddressValue)
        coVerify(exactly = 0) { hosts.updateLastAddress(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun learningRechecksTrustAfterConcurrentIdentityEdit() = runBlocking {
        coEvery { hosts.compareAndSetAddresses(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            host = host!!.copy(connectionRevision = "new", trustedHostKeySha256 = "new-pin")
            0
        }
        assertFalse(repo.learnHostAddress("id", "100.64.0.5", 22, pin))
        assertEquals(1, host!!.addresses.size)
        assertEquals("new-pin", host!!.trustedHostKeySha256)
    }

    @Test fun discoveryUsesLastAddressAndNetworkFallbackWithoutRecordingSshSuccess() = runBlocking {
        repo.addHostAddress("id", "100.64.0.5", 22)
        host = host!!.copy(lastAddressValue = "100.64.0.5:22")
        val attempted = mutableListOf<String>()
        val key = RemoteHostKey("ssh-ed25519", pin)
        every { transport.discoverHostKey(any(), any(), any(), any()) } answers {
            attempted += firstArg<String>()
            if (firstArg<String>() == "100.64.0.5") throw ConnectException("Connection refused")
            key
        }
        assertEquals(key, repo.discoverHostKey("id"))
        assertEquals(listOf("100.64.0.5", "192.168.1.5"), attempted)
        coVerify(exactly = 0) { hosts.updateLastAddress(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun passwordFailureIsReturnedIntactWithoutTryingAlternate() = runBlocking {
        repo.addHostAddress("id", "100.64.0.5", 22)
        val failure = RemoteConnectException("192.168.1.5", JSchException("Auth fail"), RemotePasswordSignInFailure.REJECTED, "user")
        every { transport.open(any(), any(), any()) } throws failure
        assertSame(failure, runCatching { repo.remoteHome("id") }.exceptionOrNull())
        verify(exactly = 1) { transport.open(any(), any(), any()) }
    }
}
