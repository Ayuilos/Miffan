package me.ayuilos.miffan.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import me.rerere.workspace.RemoteAuthentication
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-encrypted credentials in no-backup storage; no secret is stored in Room. */
class RemoteHostCredentialStore(
    context: Context,
    directoryName: String = "remote-host-credentials",
    private val keyAlias: String = "miffan.remote-host-credentials.v1",
) {
    private val directory = File(context.noBackupFilesDir, directoryName)

    @Synchronized
    fun save(id: String, authentication: RemoteAuthentication) {
        val json = when (authentication) {
            is RemoteAuthentication.Password -> JSONObject()
                .put("type", "PASSWORD")
                .put("value", authentication.value)
            is RemoteAuthentication.PrivateKey -> JSONObject()
                .put("type", "PRIVATE_KEY")
                .put("pem", authentication.pem)
                .put("passphrase", authentication.passphrase)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        directory.mkdirs()
        val destination = fileFor(id)
        val temporary = File(directory, "$id.tmp")
        temporary.outputStream().use { it.write(iv); it.write(ciphertext); it.fdSync() }
        check(temporary.renameTo(destination)) { "Unable to save remote host credentials" }
    }

    @Synchronized
    fun load(id: String): RemoteAuthentication {
        val payload = fileFor(id).readBytes()
        require(payload.size > 12) { "Remote host credential is unavailable" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val json = JSONObject(String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8))
        return when (json.getString("type")) {
            "PASSWORD" -> RemoteAuthentication.Password(json.getString("value"))
            "PRIVATE_KEY" -> RemoteAuthentication.PrivateKey(
                json.getString("pem"),
                if (json.isNull("passphrase")) null else json.getString("passphrase"),
            )
            else -> error("Unsupported remote authentication type")
        }
    }

    @Synchronized
    fun delete(id: String) {
        fileFor(id).delete()
    }

    private fun fileFor(id: String): File {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "Invalid remote host id" }
        return File(directory, "$id.bin")
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }

    private fun java.io.OutputStream.fdSync() {
        (this as? java.io.FileOutputStream)?.fd?.sync()
    }
}
