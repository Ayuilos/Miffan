package me.ayuilos.miffan.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.InetAddress
import java.security.MessageDigest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object RemoteStreamPermissions {
    val localNetwork: String? get() = if (Build.VERSION.SDK_INT >= 37) Manifest.permission.ACCESS_LOCAL_NETWORK else null
}

/** Numeric canonicalisation never performs DNS for untrusted helper candidates. */
internal fun numericStreamAddress(value: String): String? {
    if (value.contains(':')) {
        if (!value.matches(Regex("[0-9a-fA-F:.]+"))) return null
        return runCatching { InetAddress.getByName(value).hostAddress }.getOrNull()
    }
    val parts = value.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } || it.toIntOrNull() !in 0..255 }) return null
    return parts.joinToString(".") { it.toInt().toString() }
}
internal fun tailscaleAddress(ip: String): Boolean = ip.split('.').let {
    it.size == 4 && it[0] == "100" && it[1].toIntOrNull() in 64..127
}
internal fun privateStreamAddress(ip: String): Boolean = ip.split('.').let {
    it.size == 4 && (it[0] == "10" || it[0] == "192" && it[1] == "168" || it[0] == "172" && it[1].toIntOrNull() in 16..31)
} || ip.lowercase().let { it.startsWith("fc") || it.startsWith("fd") || it.startsWith("fe80:") }
internal data class StreamSubnet(val address: String, val prefix: Int) {
    fun contains(candidate: String): Boolean {
        val local = numericStreamAddress(address) ?: return false
        val remote = numericStreamAddress(candidate) ?: return false
        val a = InetAddress.getByName(local).address
        val b = InetAddress.getByName(remote).address
        if (a.size != b.size || prefix !in 0..a.size * 8) return false
        return a.indices.all { index ->
            val bits = (prefix - index * 8).coerceIn(0, 8)
            val mask = (0xff shl (8 - bits)) and 0xff
            (a[index].toInt() and mask) == (b[index].toInt() and mask)
        }
    }
    fun network(): String {
        val bytes = InetAddress.getByName(address).address
        bytes.indices.forEach { i -> bytes[i] = (bytes[i].toInt() and ((0xff shl (8 - (prefix - i * 8).coerceIn(0, 8))) and 0xff)).toByte() }
        return "${InetAddress.getByAddress(bytes).hostAddress}/$prefix"
    }
}
internal data class StreamNetworkSnapshot(val key: String, val subnets: List<StreamSubnet>, val localAllowed: Boolean)
internal data class StreamCandidates(val addresses: List<String>, val permissionSkipped: Boolean)

internal fun orderStreamCandidates(cached: String?, reported: List<String>, ssh: List<String>, network: StreamNetworkSnapshot): StreamCandidates {
    val reportedIps = reported.mapNotNull(::numericStreamAddress).distinct()
    val sshIps = ssh.mapNotNull(::numericStreamAddress).distinct()
    // A cached LAN address must still be on today's subnet and pass today's permission check.
    val lan = reportedIps.filter { privateStreamAddress(it) && network.subnets.any { subnet -> subnet.contains(it) } }
    val ordered = listOfNotNull(cached?.let(::numericStreamAddress)?.takeIf { (!privateStreamAddress(it) || network.subnets.any { subnet -> subnet.contains(it) }) }) +
        lan + reportedIps.filter(::tailscaleAddress) + sshIps
    fun local(address: String) = privateStreamAddress(address) || network.subnets.any { it.contains(address) }
    val skipped = ordered.any { local(it) && !network.localAllowed }
    return StreamCandidates(ordered.distinct().filter { network.localAllowed || !local(it) }.take(2), skipped)
}
internal fun streamNetworkKey(parts: List<String>): String = parts.sorted().joinToString("|")
internal fun streamRouteCacheKey(hostId: String, revision: String, network: String): String =
    MessageDigest.getInstance("SHA-256").digest(listOf(hostId, revision, network).joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) }

class RemoteStreamRoutes(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    // Addresses are not credentials. Network keys contain no SSID/BSSID and require no location permission.
    private val preferences = context.getSharedPreferences("remote-stream-routes", Context.MODE_PRIVATE)
    internal fun snapshot(): StreamNetworkSnapshot {
        val parts = mutableListOf<String>()
        val subnets = mutableListOf<StreamSubnet>()
        val active = manager.activeNetwork
        manager.allNetworks.forEach { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@forEach
            if (network != active && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@forEach
            val links = manager.getLinkProperties(network)
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            val addresses = links?.linkAddresses.orEmpty().mapNotNull { link ->
                link.address.hostAddress?.substringBefore('%')?.let { StreamSubnet(it, link.prefixLength) }
            }
            parts += "$type:${links?.interfaceName.orEmpty()}:${addresses.map { it.network() }.sorted()}:${links?.routes.orEmpty().mapNotNull { it.gateway?.hostAddress }.sorted()}"
            if (type == "wifi" || type == "ethernet") subnets += addresses
        }
        val permission = RemoteStreamPermissions.localNetwork
        return StreamNetworkSnapshot(streamNetworkKey(parts), subnets,
            permission == null || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
    }
    @Synchronized internal fun cached(hostId: String, revision: String, network: String): String? {
        invalidateRevision(hostId, revision)
        return preferences.getString("$hostId:${streamRouteCacheKey(hostId, revision, network)}", null)
    }
    @Synchronized internal fun success(hostId: String, revision: String, network: String, address: String) {
        invalidateRevision(hostId, revision)
        preferences.edit().putString("$hostId:${streamRouteCacheKey(hostId, revision, network)}", address).apply()
    }
    @Synchronized fun forget(hostId: String) {
        val edit = preferences.edit()
        preferences.all.keys.filter { it.startsWith("$hostId:") }.forEach(edit::remove)
        edit.apply()
    }
    private fun invalidateRevision(hostId: String, revision: String) {
        if (preferences.getString("$hostId:revision", null) != revision) {
            forget(hostId); preferences.edit().putString("$hostId:revision", revision).apply()
        }
    }
}

/** Android's blocking DNS call runs independently so cancellation never holds the open budget. */
internal suspend fun resolveStreamHost(address: String): List<String> = suspendCancellableCoroutine { continuation ->
    Thread({
        val resolved = runCatching { InetAddress.getAllByName(address.removePrefix("[").removeSuffix("]")).mapNotNull { it.hostAddress } }
            .getOrDefault(emptyList())
        if (continuation.isActive) continuation.resume(resolved)
    }, "StreamDns").apply { isDaemon = true; start() }
}
