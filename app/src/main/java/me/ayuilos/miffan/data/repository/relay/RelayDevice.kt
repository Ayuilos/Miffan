package me.ayuilos.miffan.data.repository.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import me.rerere.workspace.relay.RelayDeviceSigner
import me.rerere.workspace.relay.RelayIds

/**
 * This phone's relay device key (relay/PROTOCOL.md §2): a P-256 key generated inside Android
 * Keystore, never exportable. Connectors allow a phone by the key's [RelayIds.deviceId].
 */
class RelayDeviceKeyStore {
    @Synchronized
    fun signer(): RelayDeviceSigner {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build())
            }.generateKeyPair()
        }
        val publicKey = keyStore.getCertificate(ALIAS).publicKey.encoded
        val privateKey = keyStore.getKey(ALIAS, null) as PrivateKey
        return object : RelayDeviceSigner {
            override val publicKeySpki: ByteArray = publicKey
            override fun sign(data: ByteArray): ByteArray =
                Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey); update(data) }.sign()
        }
    }

    fun deviceId(): String = RelayIds.deviceId(signer().publicKeySpki)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "miffan.relay-device.v1"
    }
}

/** Names the current network, so relay entry choices are measured again after a network change. */
class RelayNetworkKey(private val context: Context) {
    fun current(): String {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val network = manager.activeNetwork ?: return "none"
        val caps = manager.getNetworkCapabilities(network)
        val kind = when {
            caps == null -> "unknown"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            else -> "other"
        }
        return "$kind:${network.networkHandle}"
    }
}

/** What the app needs to reach computers through relays. */
class RelayAccess(
    val client: me.rerere.workspace.relay.RelayClient,
    val deviceKeys: RelayDeviceKeyStore,
    val network: RelayNetworkKey,
) {
    private val _lastReady = kotlinx.coroutines.flow.MutableStateFlow<Map<String, me.rerere.workspace.relay.RelayReady>>(emptyMap())

    /** The relay's last `ready` per computer (by host id): its policy and the computer's usage then. */
    val lastReady: kotlinx.coroutines.flow.StateFlow<Map<String, me.rerere.workspace.relay.RelayReady>> = _lastReady

    fun record(hostId: String, ready: me.rerere.workspace.relay.RelayReady) {
        _lastReady.value = _lastReady.value + (hostId to ready)
    }
}

/** One forced trip through a relay: how long the session took to open and one command took to answer. */
data class RelayProbe(
    val openMillis: Long,
    val commandMillis: Long,
    val policy: me.rerere.workspace.relay.RelayPolicy,
    val usage: me.rerere.workspace.relay.RelayUsage,
)
