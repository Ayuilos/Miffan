package me.ayuilos.miffan.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.workspace.SshKeyCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SshKeyCredentialStoreTest {
    @Test fun generatedKeySurvivesEncryptedStoreAndOfflineImport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SshKeyCredentialStore(context)
        val id = UUID.randomUUID().toString()
        val file = File(context.noBackupFilesDir, "managed-ssh-keys/$id.bin")
        val generated = SshKeyCodec.generate()
        try {
            store.save(id, generated.privateKeyPem)
            assertTrue(store.hasUsableMaterial(id))
            assertTrue(file.isFile)
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("OPENSSH PRIVATE KEY"))
            val restored = SshKeyCodec.importPrivateKey(store.load(id).pem)
            assertEquals(generated.publicKey, restored.publicKey)
            assertEquals(generated.fingerprint, restored.fingerprint)
        } finally {
            store.delete(id)
        }
        assertFalse(store.hasUsableMaterial(id))
    }
}
