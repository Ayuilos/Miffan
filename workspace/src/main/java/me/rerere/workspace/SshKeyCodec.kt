package me.rerere.workspace

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Private material exists only in memory here; callers must encrypt it before persistence. */
data class SshKeyMaterial(
    val privateKeyPem: String,
    val publicKey: String,
    val fingerprint: String,
    val algorithm: String,
) {
    override fun toString(): String = "SshKeyMaterial(algorithm=$algorithm, fingerprint=$fingerprint, privateKey=[redacted])"
}

/** Offline key creation/import. No host connection, local Shell, or PRoot is involved. */
object SshKeyCodec {
    const val MAX_PRIVATE_KEY_CHARS = 1024 * 1024

    /** Exports normalized stored material, optionally protected with an OpenSSH passphrase. */
    fun exportPrivateKey(privateKeyPem: String, passphrase: String? = null): String {
        if (passphrase != null && passphrase.isEmpty()) throw SshKeyException(SshKeyException.Reason.EMPTY_PASSPHRASE)
        val normalized = importPrivateKey(privateKeyPem)
        val bytes = normalized.privateKeyPem.toByteArray(Charsets.UTF_8)
        val password = passphrase?.toByteArray(Charsets.UTF_8)
        try {
            val pair = KeyPair.load(JSch(), bytes, null)
            return try {
                ByteArrayOutputStream().use { output ->
                    pair.writeOpenSSHv1PrivateKey(output, password)
                    output.toString(Charsets.UTF_8.name())
                }
            } finally {
                pair.dispose()
            }
        } catch (_: Exception) {
            throw SshKeyException(SshKeyException.Reason.EXPORT_FAILED)
        } finally {
            bytes.fill(0)
            password?.fill(0)
        }
    }

    fun generate(): SshKeyMaterial {
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        return try {
            material(pair)
        } finally {
            pair.dispose()
        }
    }

    fun importPrivateKey(privateKeyPem: String, passphrase: String? = null): SshKeyMaterial {
        if (privateKeyPem.isBlank() || privateKeyPem.length > MAX_PRIVATE_KEY_CHARS) {
            throw SshKeyException(SshKeyException.Reason.INVALID_SIZE)
        }
        val bytes = privateKeyPem.trim().toByteArray(Charsets.UTF_8)
        val password = passphrase?.toByteArray(Charsets.UTF_8)
        try {
            val pair = KeyPair.load(JSch(), bytes, null)
            return try {
                require(!pair.isEncrypted || (password != null && pair.decrypt(password)))
                material(pair)
            } finally {
                pair.dispose()
            }
        } catch (_: Exception) {
            // Provider parsing exceptions can contain input. Never expose their cause or text.
            throw SshKeyException(SshKeyException.Reason.IMPORT_FAILED)
        } finally {
            bytes.fill(0)
            password?.fill(0)
        }
    }

    private fun material(pair: KeyPair): SshKeyMaterial {
        require(pair.keyType in setOf(KeyPair.ED25519, KeyPair.RSA, KeyPair.ECDSA))
        // Prove that the parsed private material can sign for the exported public key.
        val probe = ByteArray(32).also(SecureRandom()::nextBytes)
        val signatureAlgorithm = if (pair.keyType == KeyPair.RSA) "rsa-sha2-256" else pair.keyTypeString
        val signature = requireNotNull(pair.getSignature(probe, signatureAlgorithm))
        val verifier = requireNotNull(pair.getVerifier(signatureAlgorithm))
        verifier.update(probe)
        require(verifier.verify(signature)) { "Private and public key do not match" }

        val blob = requireNotNull(pair.publicKeyBlob)
        val publicKey = "${pair.keyTypeString} ${Base64.getEncoder().encodeToString(blob)} miffan"
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        val privateKey = ByteArrayOutputStream().use { output ->
            pair.writeOpenSSHv1PrivateKey(output, null)
            output.toString(Charsets.UTF_8.name())
        }
        require(privateKey.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        return SshKeyMaterial(privateKey, publicKey, fingerprint, pair.keyTypeString)
    }
}
