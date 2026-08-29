package me.ayuilos.miffan.data.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAICodexCredentials
import me.rerere.ai.provider.ProviderSetting
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "ProviderSecretCipher"
private const val KEY_ALIAS = "miffan_provider_secrets_v1"
private const val VALUE_PREFIX = "miffan-secret:v1:"
private const val GCM_TAG_LENGTH_BITS = 128

/** Encrypts provider credentials with an app-private key held by Android Keystore. */
internal class ProviderSecretCipher {
    private val keyLock = Any()

    fun encrypt(value: String): String {
        if (value.isBlank() || value.startsWith(VALUE_PREFIX)) return value
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
        return VALUE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    fun decrypt(value: String): String {
        if (value.isBlank() || !value.startsWith(VALUE_PREFIX)) return value
        return runCatching {
            val payload = Base64.getUrlDecoder().decode(value.removePrefix(VALUE_PREFIX))
            require(payload.isNotEmpty()) { "Encrypted provider secret is empty" }
            val ivLength = payload[0].toInt() and 0xff
            require(ivLength in 12..16 && payload.size > ivLength + 1) {
                "Encrypted provider secret has an invalid IV"
            }
            val iv = payload.copyOfRange(1, ivLength + 1)
            val encrypted = payload.copyOfRange(ivLength + 1, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrElse { error ->
            Log.e(TAG, "Unable to decrypt a provider credential; the credential must be reconnected", error)
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey = synchronized(keyLock) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
            }
            .generateKey()
    }
}

internal fun List<ProviderSetting>.transformProviderSecrets(
    transform: (String) -> String,
): List<ProviderSetting> = map { provider -> provider.transformProviderSecrets(transform) }

private fun ProviderSetting.transformProviderSecrets(
    transform: (String) -> String,
): ProviderSetting {
    val transformedModels = models.map { model ->
        model.copy(
            providerOverwrite = model.providerOverwrite?.transformProviderSecrets(transform),
        )
    }
    return when (this) {
        is ProviderSetting.OpenAI -> copy(
            models = transformedModels,
            apiKey = transform(apiKey),
            codexCredentials = codexCredentials?.transformSecrets(transform),
        )

        is ProviderSetting.Google -> copy(
            models = transformedModels,
            apiKey = transform(apiKey),
            privateKey = transform(privateKey),
        )

        is ProviderSetting.Claude -> copy(
            models = transformedModels,
            apiKey = transform(apiKey),
        )
    }
}

private fun OpenAICodexCredentials.transformSecrets(
    transform: (String) -> String,
): OpenAICodexCredentials = copy(
    accessToken = transform(accessToken),
    refreshToken = transform(refreshToken),
)

/** Removes provider credentials from portable backups by default. */
internal fun Settings.withoutProviderSecrets(): Settings = copy(
    providers = providers.map(ProviderSetting::withoutSecrets),
)

private fun ProviderSetting.withoutSecrets(): ProviderSetting {
    val safeModels: List<Model> = models.map { model ->
        model.copy(providerOverwrite = model.providerOverwrite?.withoutSecrets())
    }
    return when (this) {
        is ProviderSetting.OpenAI -> copy(
            models = safeModels,
            apiKey = "",
            codexCredentials = null,
        )

        is ProviderSetting.Google -> copy(
            models = safeModels,
            apiKey = "",
            privateKey = "",
        )
        is ProviderSetting.Claude -> copy(models = safeModels, apiKey = "")
    }
}
