package me.ayuilos.miffan.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import javax.net.SocketFactory
import kotlin.coroutines.resume
import me.rerere.workspace.NativeSshWorkspaceTransport

enum class NearbySystem { MAC, WINDOWS, LINUX, UNKNOWN }
enum class NearbyNetwork { LOCAL, TAILSCALE }
enum class NearbyNameSource { MDNS, LOCAL_DNS, TAILSCALE_DNS }

data class NearbyComputer(
    /** DNS-SD instance name, else reverse DNS; null if only the address is known. */
    val name: String?,
    /** Numeric address to connect to; IPv4 preferred. */
    val host: String,
    val port: Int,
    val system: NearbySystem,
    val detail: String? = null,
    val network: NearbyNetwork = NearbyNetwork.LOCAL,
    val hostKey: String? = null,
    val nameSource: NearbyNameSource? = null,
)

data class NearbyScan(
    val computers: List<NearbyComputer>,
    /** True until all one-off address sweeps finish; mDNS continues afterwards. */
    val searching: Boolean,
    /** A VPN interface with an IPv4 address in the Tailscale/Headscale CGNAT range exists. */
    val tailscaleActive: Boolean = false,
)

internal data class NearbyBanner(val system: NearbySystem, val detail: String? = null)

private val linuxNames = listOf(
    "Ubuntu", "Debian", "Raspbian", "Fedora", "Arch", "CachyOS", "Manjaro", "Alpine",
    "CentOS", "Rocky", "AlmaLinux", "Red Hat", "RHEL", "SUSE", "Gentoo", "NixOS", "Linux",
)

internal fun nearbyBannerSystem(banner: String): NearbyBanner {
    if (banner.contains("OpenSSH_for_Windows", ignoreCase = true)) return NearbyBanner(NearbySystem.WINDOWS)
    // OpenSSH itself is cross-platform. Only its comment is evidence for a Linux distribution.
    val comment = banner.substringAfter(' ', "")
    val detail = linuxNames.firstOrNull {
        Regex("(?i)(?<![a-z])${Regex.escape(it)}(?![a-z])").containsMatchIn(comment)
    }
    return if (detail == null) NearbyBanner(NearbySystem.UNKNOWN) else NearbyBanner(NearbySystem.LINUX, detail)
}

/** Pure description of a LinkProperties IPv4 address and its network's transports. */
internal data class NearbyInterface(
    val address: String,
    val prefix: Int,
    val wifi: Boolean = false,
    val ethernet: Boolean = false,
    val vpn: Boolean = false,
    val domains: List<String> = emptyList(),
)

internal fun chooseNearbyInterface(interfaces: List<NearbyInterface>): NearbyInterface? =
    interfaces.filter { !it.vpn && (it.wifi || it.ethernet) && it.prefix in 0..32 && ipv4Number(it.address) != null }
        .sortedBy { !it.wifi }.firstOrNull()

internal fun chooseNearbyTailscaleInterface(interfaces: List<NearbyInterface>): NearbyInterface? =
    interfaces.firstOrNull {
        val ip = ipv4Number(it.address)
        it.vpn && ip != null && (ip and 0xffc00000L) == 0x64400000L
    }

private fun ipv4Number(address: String): Long? {
    val octets = address.split('.')
    if (octets.size != 4) return null
    var number = 0L
    for (octet in octets) {
        val value = octet.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        number = (number shl 8) or value.toLong()
    }
    return number
}

internal fun nearbySweepAddresses(address: String, prefix: Int): List<String> {
    val own = ipv4Number(address) ?: return emptyList()
    if (prefix !in 0..32) return emptyList()
    val bits = maxOf(24, prefix)
    val mask = (0xffffffffL shl (32 - bits)) and 0xffffffffL
    val network = own and mask
    val broadcast = network or (mask xor 0xffffffffL)
    return (network + 1 until broadcast).filter { it != own }.map { ip ->
        (3 downTo 0).joinToString(".") { ((ip shr (it * 8)) and 255).toString() }
    }
}

internal fun nearbyReverseName(
    ip: String,
    hostname: String,
    routerDomains: List<String> = emptyList(),
    network: NearbyNetwork = NearbyNetwork.LOCAL,
): String? {
    val name = hostname.trim().trimEnd('.')
    if (name.isBlank() || name == ip) return null
    if (network == NearbyNetwork.TAILSCALE) return name.substringBefore('.').ifBlank { null }
    val suffix = (listOf("lan", "local", "home") + routerDomains.map { it.trim('.') })
        .filter { it.isNotBlank() }.sortedByDescending { it.length }
        .firstOrNull { name.endsWith(".$it", ignoreCase = true) }
    return (if (suffix == null) name else name.dropLast(suffix.length + 1)).ifBlank { null }
}

/** Blocking platform DNS cannot always be interrupted; keep it off the coroutine worker pool. */
internal suspend fun lookupNearbyName(
    ip: String,
    domains: List<String>,
    executor: ExecutorService,
    network: NearbyNetwork = NearbyNetwork.LOCAL,
): String? =
    withTimeoutOrNull(1_500L) {
        suspendCancellableCoroutine { continuation ->
            val task = executor.submit {
                val name = runCatching { nearbyReverseName(ip, InetAddress.getByName(ip).canonicalHostName, domains, network) }.getOrNull()
                continuation.resume(name)
            }
            continuation.invokeOnCancellation { task.cancel(true) }
        }
    }

/** Connect and read only: never writes a client identification or attempts authentication. */
internal suspend fun probeNearbySsh(
    host: String,
    port: Int = 22,
    socketFactory: SocketFactory = SocketFactory.getDefault(),
    connectTimeoutMillis: Int = 400,
    bannerTimeoutMillis: Int = 800,
): String? = coroutineScope {
    suspendCancellableCoroutine { continuation ->
        val socket = runCatching { socketFactory.createSocket() }.getOrNull()
        if (socket == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { runCatching { socket.close() } }
        launch(Dispatchers.IO) {
            val banner = runCatching {
                socket.use {
                    it.connect(InetSocketAddress(host, port), connectTimeoutMillis)
                    val deadline = System.nanoTime() + bannerTimeoutMillis * 1_000_000L
                    val input = it.getInputStream()
                    val line = StringBuilder()
                    // The deadline bounds even a peer that trickles one byte at a time.
                    while (line.length < 255) {
                        val remaining = (deadline - System.nanoTime()) / 1_000_000L
                        if (remaining <= 0) return@use null
                        it.soTimeout = remaining.coerceAtLeast(1).toInt()
                        val byte = input.read()
                        if (byte < 0) return@use null
                        if (byte == 10) return@use line.toString().trimEnd('\r').takeIf { text -> text.startsWith("SSH-") }
                        line.append(byte.toChar())
                    }
                    null
                }
            }.getOrNull()
            continuation.resume(banner)
        }
    }
}

internal data class NearbyAdvertisement(
    val name: String,
    val host: String,
    val sshPort: Int?,
    val apple: Boolean = false,
)

/** Exchanges the SSH host key, then rejects it before any authentication takes place. */
internal suspend fun probeNearbyHostKey(host: String, port: Int, socketFactory: SocketFactory, timeoutMillis: Int): String? =
    try {
        runInterruptible(Dispatchers.IO) {
            NativeSshWorkspaceTransport.discoverHostKey(host, port, timeoutMillis, socketFactory).sha256Fingerprint
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

internal fun nearbyAppleEvidence(type: String, model: String?): Boolean =
    type == "_sftp-ssh._tcp" || type == "_companion-link._tcp" ||
        (type == "_device-info._tcp" && model?.startsWith("Mac") == true)

/** Service aliases remain in source state, so losing one can reveal another on the same host. */
internal fun mergeNearbyComputers(
    advertisements: Collection<NearbyAdvertisement>,
    probes: Collection<NearbyComputer>,
): List<NearbyComputer> {
    val ads = advertisements.groupBy { NearbyNetwork.LOCAL to it.host }
    val checked = probes.groupBy { it.network to it.host }
    val computers = (ads.keys + checked.keys).mapNotNull { key ->
        val (network, host) = key
        val hints = ads[key].orEmpty().sortedWith(compareBy<NearbyAdvertisement> { it.sshPort == null }.thenBy { it.name })
        val advertisedPort = hints.firstOrNull { it.sshPort != null }?.sshPort
        val evidence = checked[key].orEmpty()
        if (advertisedPort == null && evidence.isEmpty()) return@mapNotNull null
        val port = advertisedPort ?: evidence.first().port
        val banner = evidence.sortedWith(compareBy<NearbyComputer> { it.system == NearbySystem.UNKNOWN }
            .thenBy { it.detail.isNullOrBlank() }.thenBy { it.port != port }).firstOrNull()
        val apple = hints.any { it.apple }
        val named = evidence.filter { !it.name.isNullOrBlank() }.minByOrNull { nameRank(it.nameSource) }
        NearbyComputer(
            hints.firstOrNull()?.name ?: named?.name, host, port,
            if (apple) NearbySystem.MAC else banner?.system ?: NearbySystem.UNKNOWN,
            if (apple) null else banner?.detail, network,
            evidence.firstOrNull { it.port == port }?.hostKey,
            if (hints.isNotEmpty()) NearbyNameSource.MDNS else named?.nameSource,
        )
    }
    // Source state stays untouched: a lost advertisement must be able to reveal the DNS name.
    val identified = computers.filter { !it.hostKey.isNullOrBlank() }.groupBy { it.hostKey }
    val merged = computers.filter { it.hostKey.isNullOrBlank() } + identified.values.flatMap { machine ->
        val named = machine.filter { !it.name.isNullOrBlank() }.minByOrNull { nameRank(it.nameSource) }
        val apple = machine.any { it.network == NearbyNetwork.LOCAL && ads[NearbyNetwork.LOCAL to it.host].orEmpty().any { ad -> ad.apple } }
        val evidence = machine.sortedWith(compareBy<NearbyComputer> { it.system == NearbySystem.UNKNOWN }
            .thenBy { it.detail.isNullOrBlank() }).first()
        machine.groupBy { it.network }.values.map { entries ->
            entries.minWith(compareBy<NearbyComputer> { it.host }.thenBy { it.port }).copy(
                name = named?.name, nameSource = named?.nameSource,
                system = if (apple) NearbySystem.MAC else evidence.system,
                detail = if (apple) null else evidence.detail,
            )
        }
    }
    return merged.sortedWith(compareBy<NearbyComputer, String>(String.CASE_INSENSITIVE_ORDER) { it.name ?: it.host }.thenBy { it.host })
}

private fun nameRank(source: NearbyNameSource?): Int = when (source) {
    NearbyNameSource.MDNS -> 0
    NearbyNameSource.LOCAL_DNS -> 1
    NearbyNameSource.TAILSCALE_DNS -> 2
    null -> 3
}

internal fun chooseNearbyComputerAddress(addresses: List<InetAddress>): String? {
    val usable = addresses.filterNot { it.isLoopbackAddress || it.isAnyLocalAddress || it.isMulticastAddress }
    val address = usable.firstOrNull { it is Inet4Address }
        ?: usable.firstOrNull {
            it is Inet6Address && !it.isLinkLocalAddress && !it.isSiteLocalAddress &&
                (it.address[0].toInt() and 0xfe) != 0xfc
        }
    return address?.hostAddress
}
