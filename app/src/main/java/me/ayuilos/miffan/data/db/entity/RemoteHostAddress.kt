package me.ayuilos.miffan.data.db.entity

import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RemoteHostAddress(val host: String, val port: Int) {
    val storageValue: String get() = "${if (':' in host) "[${host.removeSurrounding("[", "]")}]" else host}:$port"
}

internal fun decodeRemoteHostAddresses(value: String): List<RemoteHostAddress> =
    runCatching { Json.decodeFromString<List<RemoteHostAddress>>(value) }.getOrDefault(emptyList())
        .filter { it.host.isNotBlank() && it.host.none { char -> char.isWhitespace() || char == '\u0000' } && it.port in 1..65535 }

internal fun encodeRemoteHostAddresses(addresses: List<RemoteHostAddress>): String =
    Json.encodeToString(addresses.distinct())

enum class RemoteAddressNetwork { LOCAL, TAILSCALE, OTHER }

/** Classifies literals and local suffixes without resolving names or doing network I/O. */
fun remoteAddressNetwork(host: String): RemoteAddressNetwork {
    val name = host.trim().removeSurrounding("[", "]").lowercase(Locale.ROOT).trimEnd('.')
    if (name.endsWith(".ts.net")) return RemoteAddressNetwork.TAILSCALE
    if (listOf(".local", ".lan", ".home").any(name::endsWith)) return RemoteAddressNetwork.LOCAL
    val octets = name.split('.').mapNotNull { part ->
        part.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toIntOrNull()?.takeIf { it in 0..255 }
    }
    if (name.split('.').size == 4 && octets.size == 4) {
        val (a, b) = octets
        return when {
            a == 100 && b in 64..127 -> RemoteAddressNetwork.TAILSCALE
            a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) -> RemoteAddressNetwork.LOCAL
            else -> RemoteAddressNetwork.OTHER
        }
    }
    if (':' in name) {
        val address = runCatching { InetAddress.getByName(name.substringBefore('%')) }.getOrNull()
        if (address is Inet6Address && (address.isLinkLocalAddress ||
                (address.address[0].toInt() and 0xfe) == 0xfc)) return RemoteAddressNetwork.LOCAL
    }
    return RemoteAddressNetwork.OTHER
}
