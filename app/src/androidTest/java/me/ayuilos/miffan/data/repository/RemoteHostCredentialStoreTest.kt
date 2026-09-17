package me.ayuilos.miffan.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.workspace.RemoteAuthentication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RemoteHostCredentialStoreTest {
    @Test
    fun encryptedCredentialCanBeReplacedAndDeletedWithoutPlaintextOnDisk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RemoteHostCredentialStore(context)
        val id = UUID.randomUUID().toString()
        val password = "distinct-secret-password-$id"
        val file = File(context.noBackupFilesDir, "remote-host-credentials/$id.bin")
        try {
            store.save(id, RemoteAuthentication.Password(password))
            assertEquals(RemoteAuthentication.Password(password), store.load(id))
            assertTrue(file.isFile)
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(password))

            val replacement = RemoteAuthentication.PrivateKey("test-pem-$id", "test-passphrase")
            store.save(id, replacement)
            assertEquals(replacement, store.load(id))
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("test-pem-$id"))
        } finally {
            store.delete(id)
        }
        assertFalse(file.exists())
        assertThrows(java.io.FileNotFoundException::class.java) { store.load(id) }
        try {
            store.save(id, RemoteAuthentication.Password("re-entered-password"))
            assertEquals(RemoteAuthentication.Password("re-entered-password"), store.load(id))
        } finally {
            store.delete(id)
        }
    }
}
