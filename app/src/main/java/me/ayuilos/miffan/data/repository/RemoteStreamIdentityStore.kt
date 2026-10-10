package me.ayuilos.miffan.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.stream.StreamIdentities
import me.rerere.stream.StreamIdentity

/** One device identity, encrypted with Keystore and excluded from Android backup. */
class RemoteStreamIdentityStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "remote-stream/identity.bin"))
    private val alias = "miffan.remote-stream-identity.v1"
    private var cached: StreamIdentity? = null
    @Serializable private data class Stored(val certificatePem: String, val privateKeyPem: String, val uniqueId: String)

    @Synchronized fun get(): StreamIdentity {
        cached?.let { return it }
        val identity = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val payload = file.openRead().use { it.readBytes() }
            require(payload.size > 28) { "Stream identity unavailable" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            cipher.updateAAD(alias.toByteArray())
            val stored = Json.decodeFromString<Stored>(cipher.doFinal(payload.copyOfRange(12, payload.size)).toString(Charsets.UTF_8))
            StreamIdentity(stored.certificatePem, stored.privateKeyPem, stored.uniqueId)
        } else {
            val generated = StreamIdentities.generate()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(alias.toByteArray())
            val bytes = Json.encodeToString(Stored(generated.certificatePem, generated.privateKeyPem, generated.uniqueId)).toByteArray()
            file.baseFile.parentFile!!.mkdirs()
            val output = file.startWrite()
            try { output.write(cipher.iv); output.write(cipher.doFinal(bytes)); file.finishWrite(output) }
            catch (error: Throwable) { file.failWrite(output); throw error }
            generated
        }
        cached = identity
        return identity
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
