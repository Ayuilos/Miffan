package me.ayuilos.miffan.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.workspace.SshKeyCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SshKeyRepositoryLifecycleTest {
    @Test fun keyCanBeCreatedAndBoundToHostEntirelyOffline() = runBlocking {
        val repository = GlobalContext.get().get<WorkspaceRepository>()
        val suffix = UUID.randomUUID().toString()
        val key = repository.generateSshKey("offline-key-$suffix")
        var hostId: String? = null
        try {
            assertTrue(repository.hasSshKeyMaterial(key.id))
            assertTrue(key.publicKey.startsWith("ssh-ed25519 "))
            val backup = repository.exportSshPrivateKey(key.id, "android-backup-passphrase")
            assertTrue(runCatching { SshKeyCodec.importPrivateKey(backup) }.isFailure)
            assertEquals(key.publicKey, SshKeyCodec.importPrivateKey(backup, "android-backup-passphrase").publicKey)
            assertEquals(key.publicKey, SshKeyCodec.importPrivateKey(repository.exportSshPrivateKey(key.id)).publicKey)
            val host = repository.createHost(
                name = "offline-host-$suffix",
                host = "100.64.0.1",
                port = 22,
                username = "tester",
                sshKeyId = key.id,
            )
            hostId = host.id
            assertTrue(host.sshKeyId == key.id)
            assertTrue(host.connectionRevision != "legacy")
            repository.updateHost(host.id, host.name + "-renamed", host.host, host.port, host.username)
            assertEquals(host.connectionRevision, repository.getHostById(host.id)?.connectionRevision)
            repository.updateHost(host.id, host.name + "-renamed", host.host, host.port, "different-user")
            assertTrue(host.connectionRevision != repository.getHostById(host.id)?.connectionRevision)
            assertTrue(runCatching { repository.deleteSshKey(key.id) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(repository.deleteHost(host.id))
            hostId = null
            assertTrue(repository.deleteSshKey(key.id))
            assertFalse(repository.hasSshKeyMaterial(key.id))
        } finally {
            hostId?.let { runCatching { repository.deleteHost(it) } }
            runCatching { repository.deleteSshKey(key.id) }
        }
    }
}
