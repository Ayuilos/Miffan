package me.ayuilos.miffan.data.db.entity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.workspace.relay.RelayEndpoint
import me.rerere.workspace.relay.RelayIds

/**
 * How to reach a computer through a relay (relay/PROTOCOL.md). [hostId] is the computer's
 * connector identity on that relay; [accessToken] is the relay deployer's access code, if any.
 */
@Serializable
data class RemoteHostRelay(
    val url: String,
    val hostId: String,
    val accessToken: String? = null,
    val enabled: Boolean = true,
) {
    val endpoint: RelayEndpoint get() = RelayEndpoint(url, accessToken)
}

/** Value of `last_address` when the last working connection went through the relay. */
const val RELAY_LAST_ADDRESS = "relay"

private val relayStorageJson = Json { ignoreUnknownKeys = true }

internal fun decodeRemoteHostRelay(value: String): RemoteHostRelay? =
    value.takeIf { it.isNotBlank() }
        ?.let { runCatching { relayStorageJson.decodeFromString<RemoteHostRelay>(it) }.getOrNull() }
        ?.takeIf { RelayIds.isHostId(it.hostId) && runCatching { it.endpoint }.isSuccess }

internal fun encodeRemoteHostRelay(relay: RemoteHostRelay?): String =
    relay?.let { relayStorageJson.encodeToString(RemoteHostRelay.serializer(), it) } ?: ""
