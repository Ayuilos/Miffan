package me.rerere.workspace

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

class SshKeyCodecTest {
    @Test fun exportedBackupsRequireTheirPassphraseAndRestoreSameIdentity() {
        val key = SshKeyCodec.generate()
        val encrypted = SshKeyCodec.exportPrivateKey(key.privateKeyPem, "备份 passphrase")
        assertThrows(IllegalArgumentException::class.java) { SshKeyCodec.importPrivateKey(encrypted) }
        assertThrows(IllegalArgumentException::class.java) { SshKeyCodec.importPrivateKey(encrypted, "wrong") }
        assertEquals(key.publicKey, SshKeyCodec.importPrivateKey(encrypted, "备份 passphrase").publicKey)
        val plain = SshKeyCodec.exportPrivateKey(key.privateKeyPem)
        assertEquals(key.publicKey, SshKeyCodec.importPrivateKey(plain).publicKey)
        assertThrows(IllegalArgumentException::class.java) { SshKeyCodec.exportPrivateKey(key.privateKeyPem, "") }
    }

    @Test fun generatedKeyRoundTripsAndExportsMatchingOpenSshPublicKey() {
        val key = SshKeyCodec.generate()
        assertEquals("ssh-ed25519", key.algorithm)
        val parts = key.publicKey.split(' ')
        assertEquals(3, parts.size)
        val blob = Base64.getDecoder().decode(parts[1])
        assertEquals("SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob)), key.fingerprint)
        val imported = SshKeyCodec.importPrivateKey(key.privateKeyPem)
        assertEquals(key.publicKey, imported.publicKey)
        assertEquals(key.fingerprint, imported.fingerprint)
        assertFalse(key.toString().contains("BEGIN OPENSSH"))
        assertNotEquals(key.fingerprint, SshKeyCodec.generate().fingerprint)
    }

    @Test fun encryptedImportRequiresCorrectPassphraseAndPreservesIdentity() {
        val original = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        try {
            val encrypted = ByteArrayOutputStream().also {
                original.writeOpenSSHv1PrivateKey(it, "test-passphrase".toByteArray())
            }.toString(Charsets.UTF_8.name())
            assertThrows(IllegalArgumentException::class.java) { SshKeyCodec.importPrivateKey(encrypted) }
            assertThrows(IllegalArgumentException::class.java) { SshKeyCodec.importPrivateKey(encrypted, "wrong") }
            val imported = SshKeyCodec.importPrivateKey(encrypted, "test-passphrase")
            assertEquals("ssh-rsa", imported.algorithm)
            assertEquals(imported.publicKey, SshKeyCodec.importPrivateKey(imported.privateKeyPem).publicKey)
        } finally {
            original.dispose()
        }
    }

    @Test fun rejectsInvalidInputWithoutReflectingSecrets() {
        val input = "not-a-private-key-secret"
        val failure = assertThrows(IllegalArgumentException::class.java) { SshKeyCodec.importPrivateKey(input) }
        assertFalse(failure.message.orEmpty().contains(input))
        assertNull(failure.cause)
    }
}
