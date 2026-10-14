package me.ayuilos.miffan.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.net.SocketFactory

class NearbyComputerDiscovery internal constructor(
    private val context: Context,
    private val probe: suspend (String, Int, SocketFactory, Int) -> String?,
) {
    constructor(context: Context) : this(context, { host, port, factory, timeout -> probeNearbySsh(host, port, factory, timeout) })
    /** Cold; LAN and Tailscale sweeps run once while mDNS continues until collection is cancelled. */
    fun observe(): Flow<NearbyScan> = callbackFlow {
        val manager = runCatching { context.getSystemService(NsdManager::class.java) }.getOrNull()
        val networks = findNearbyNetworks(context)
        val local = networks.firstOrNull { it.kind == NearbyNetwork.LOCAL }
        val tailscaleActive = networks.any { it.kind == NearbyNetwork.TAILSCALE }
        // Bind probes to the physical network, rather than letting the default VPN route them.
        val socketFactory = local?.network?.socketFactory ?: SocketFactory.getDefault()
        val probeSlots = Semaphore(48)
        val dnsExecutor = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "nearby-reverse-dns").apply { isDaemon = true }
        }
        val events = Channel<DiscoveryEvent>(Channel.UNLIMITED)
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()
        val worker = launch {
            // Only this worker mutates discovery state; Binder and IO callbacks post events.
            val services = mutableMapOf<ServiceKey, TrackedService>()
            val advertisements = mutableMapOf<ServiceKey, NearbyAdvertisement>()
            val banners = mutableMapOf<ProbeTarget, NearbyComputer>()
            val sweepHosts = mutableSetOf<Pair<NearbyNetwork, String>>()
            val hintProbes = mutableSetOf<ProbeTarget>()
            val namesRequested = mutableSetOf<Pair<NearbyNetwork, String>>()
            val queue = ArrayDeque<TrackedService>()
            var searching = true
            var resolving: TrackedService? = null
            var timeout: Job? = null

            fun publish() {
                // A successful hint probe is evidence only while its advertisement still exists.
                val visibleProbes = banners.values.filter { computer ->
                    (computer.network to computer.host) in sweepHosts ||
                        (computer.network == NearbyNetwork.LOCAL && advertisements.values.any {
                            it.host == computer.host && (it.sshPort ?: 22) == computer.port
                        })
                }
                trySend(NearbyScan(mergeNearbyComputers(advertisements.values, visibleProbes), searching, tailscaleActive))
            }

            fun update(service: TrackedService, info: NsdServiceInfo?) {
                if (services[service.key] !== service) return
                val addresses = if (info == null) emptyList() else if (Build.VERSION.SDK_INT >= 34) {
                    info.hostAddresses
                } else {
                    @Suppress("DEPRECATION")
                    listOfNotNull(info.host)
                }
                val host = chooseNearbyComputerAddress(addresses)
                val ssh = service.key.type in SSH_TYPES
                if (host != null && info != null && (!ssh || info.port in 1..65535)) {
                    val apple = nearbyAppleEvidence(service.key.type, info.attributes["model"]?.toString(Charsets.UTF_8))
                    val ad = NearbyAdvertisement(service.key.name, host, if (ssh) info.port else null, apple)
                    advertisements[service.key] = ad
                    val target = ProbeTarget(host, ad.sshPort ?: 22, NearbyNetwork.LOCAL)
                    if (target !in banners && hintProbes.add(target)) launch {
                        val banner = probeSlots.withPermit { probe(target.host, target.port, socketFactory, 400) }
                        events.trySend(DiscoveryEvent.Checked(target, banner, sweep = false))
                    }
                } else {
                    advertisements.remove(service.key)
                }
                publish()
            }

            @Suppress("DEPRECATION")
            fun resolveNext() {
                while (resolving == null && queue.isNotEmpty()) {
                    val service = queue.removeFirst()
                    if (services[service.key] !== service) continue
                    resolving = service
                    timeout = launch {
                        delay(RESOLVE_TIMEOUT_MILLIS)
                        events.trySend(DiscoveryEvent.Resolved(service, null))
                    }
                    val listener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            events.trySend(DiscoveryEvent.Resolved(service, null))
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            events.trySend(DiscoveryEvent.Resolved(service, serviceInfo))
                        }
                    }
                    // Legacy resolution has no cancellation API. Late callbacks are ignored.
                    runCatching { manager?.resolveService(service.info, listener) }
                        .onFailure { events.trySend(DiscoveryEvent.Resolved(service, null)) }
                }
            }

            fun remove(service: TrackedService) {
                if (services[service.key] !== service) return
                services.remove(service.key)
                queue.remove(service)
                service.stopWatching?.invoke()
                advertisements.remove(service.key)
                publish()
            }

            try {
                publish()
                for (event in events) {
                    when (event) {
                        is DiscoveryEvent.Found -> {
                            val name = event.info.serviceName
                            if (name.isNullOrBlank()) continue
                            val key = ServiceKey(event.type, name)
                            if (key in services || manager == null) continue
                            val service = TrackedService(key, event.info)
                            services[key] = service
                            if (Build.VERSION.SDK_INT >= 34) {
                                runCatching {
                                    service.stopWatching = watchService(
                                        manager, event.info,
                                        onUpdated = { events.trySend(DiscoveryEvent.Updated(service, it)) },
                                        onLost = { events.trySend(DiscoveryEvent.Unavailable(service)) },
                                    )
                                }.onFailure { remove(service) }
                            } else {
                                queue.addLast(service)
                                resolveNext()
                            }
                        }
                        is DiscoveryEvent.Lost -> services[event.key]?.let(::remove)
                        is DiscoveryEvent.Stopped -> services.values.filter { it.key.type == event.type }.forEach(::remove)
                        is DiscoveryEvent.Updated -> update(event.service, event.info)
                        is DiscoveryEvent.Unavailable -> remove(event.service)
                        is DiscoveryEvent.Resolved -> {
                            if (resolving !== event.service) continue
                            timeout?.cancel()
                            timeout = null
                            resolving = null
                            update(event.service, event.info)
                            resolveNext()
                        }
                        is DiscoveryEvent.Checked -> {
                            if (!event.sweep) hintProbes.remove(event.target)
                            if (event.banner == null) continue
                            val evidence = nearbyBannerSystem(event.banner)
                            val target = event.target
                            val machine = target.network to target.host
                            banners[target] = NearbyComputer(banners[target]?.name, target.host, target.port,
                                evidence.system, evidence.detail, target.network)
                            if (event.sweep) sweepHosts.add(machine)
                            publish()
                            val hasMdnsName = target.network == NearbyNetwork.LOCAL && advertisements.values.any { it.host == target.host }
                            if (event.sweep && !hasMdnsName && namesRequested.add(machine)) launch {
                                val domains = networks.firstOrNull { it.kind == target.network }?.info?.domains.orEmpty()
                                val name = lookupNearbyName(target.host, domains, dnsExecutor, target.network)
                                events.trySend(DiscoveryEvent.Named(machine, name))
                            }
                        }
                        is DiscoveryEvent.Named -> {
                            banners.keys.filter { (it.network to it.host) == event.machine }.forEach { target ->
                                banners[target] = banners.getValue(target).copy(name = event.name)
                            }
                            publish()
                        }
                        DiscoveryEvent.SweepDone -> {
                            searching = false
                            publish()
                        }
                    }
                }
            } finally {
                timeout?.cancel()
                services.values.forEach { it.stopWatching?.invoke() }
            }
        }
        val sweep = launch {
            coroutineScope {
                networks.forEach { selected ->
                    val info = selected.info
                    val prefix = if (selected.kind == NearbyNetwork.TAILSCALE) 24 else info.prefix
                    val factory = selected.network.socketFactory
                    val connectTimeout = if (selected.kind == NearbyNetwork.TAILSCALE) 700 else 400
                    nearbySweepAddresses(info.address, prefix).forEach { host ->
                        launch {
                            val banner = probeSlots.withPermit { probe(host, 22, factory, connectTimeout) }
                            events.trySend(DiscoveryEvent.Checked(ProbeTarget(host, 22, selected.kind), banner, sweep = true))
                        }
                    }
                }
            }
            // Both physical-network and VPN probes have completed; mDNS continues independently.
            events.trySend(DiscoveryEvent.SweepDone)
        }

        var multicastLock: WifiManager.MulticastLock? = null
        try {
            if (manager != null) {
                runCatching {
                    context.applicationContext.getSystemService(WifiManager::class.java)
                        ?.createMulticastLock("nearby-computers")?.also {
                            multicastLock = it
                            it.setReferenceCounted(false)
                            it.acquire()
                        }
                }
                SERVICE_TYPES.forEach { type ->
                    val listener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) = Unit
                        override fun onDiscoveryStopped(serviceType: String) { events.trySend(DiscoveryEvent.Stopped(type)) }
                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { events.trySend(DiscoveryEvent.Stopped(type)) }
                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                        override fun onServiceFound(serviceInfo: NsdServiceInfo) { events.trySend(DiscoveryEvent.Found(type, serviceInfo)) }
                        override fun onServiceLost(serviceInfo: NsdServiceInfo) { events.trySend(DiscoveryEvent.Lost(ServiceKey(type, serviceInfo.serviceName))) }
                    }
                    listeners += listener
                    runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                        .onFailure { events.trySend(DiscoveryEvent.Stopped(type)) }
                }
            }
            awaitClose()
        } finally {
            events.close()
            withContext(NonCancellable) {
                sweep.cancelAndJoin()
                worker.cancelAndJoin()
            }
            listeners.forEach { runCatching { manager?.stopServiceDiscovery(it) } }
            runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
            dnsExecutor.shutdownNow()
        }
    }.conflate().onStart { emit(NearbyScan(emptyList(), searching = true)) }.distinctUntilChanged()
}

private data class SelectedNetwork(val network: Network, val info: NearbyInterface, val kind: NearbyNetwork)

private fun findNearbyNetworks(context: Context): List<SelectedNetwork> = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching emptyList()
    val candidates = manager.allNetworks.flatMap { network ->
        val capabilities = manager.getNetworkCapabilities(network) ?: return@flatMap emptyList()
        val properties = manager.getLinkProperties(network) ?: return@flatMap emptyList()
        properties.linkAddresses.mapNotNull { address ->
            val ip = address.address as? Inet4Address ?: return@mapNotNull null
            if (ip.isLoopbackAddress || ip.isAnyLocalAddress || ip.isMulticastAddress) return@mapNotNull null
            network to NearbyInterface(
                address = ip.hostAddress ?: return@mapNotNull null,
                prefix = address.prefixLength,
                wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                domains = properties.domains?.split(Regex("\\s+")).orEmpty(),
            )
        }
    }
    val interfaces = candidates.map { it.second }
    listOfNotNull(
        chooseNearbyInterface(interfaces)?.let { info ->
            SelectedNetwork(candidates.first { it.second == info }.first, info, NearbyNetwork.LOCAL)
        },
        chooseNearbyTailscaleInterface(interfaces)?.let { info ->
            SelectedNetwork(candidates.first { it.second == info }.first, info, NearbyNetwork.TAILSCALE)
        },
    )
}.getOrDefault(emptyList())

private const val RESOLVE_TIMEOUT_MILLIS = 5_000L
private val SSH_TYPES = setOf("_ssh._tcp", "_sftp-ssh._tcp")
private val SERVICE_TYPES = SSH_TYPES + setOf(
    "_nvstream._tcp", "_rfb._tcp", "_smb._tcp", "_workstation._tcp", "_device-info._tcp", "_companion-link._tcp",
)
private data class ServiceKey(val type: String, val name: String)
private data class ProbeTarget(val host: String, val port: Int, val network: NearbyNetwork)
private class TrackedService(val key: ServiceKey, val info: NsdServiceInfo) {
    var stopWatching: (() -> Unit)? = null
}
private sealed interface DiscoveryEvent {
    data class Found(val type: String, val info: NsdServiceInfo) : DiscoveryEvent
    data class Lost(val key: ServiceKey) : DiscoveryEvent
    data class Stopped(val type: String) : DiscoveryEvent
    data class Updated(val service: TrackedService, val info: NsdServiceInfo) : DiscoveryEvent
    data class Unavailable(val service: TrackedService) : DiscoveryEvent
    data class Resolved(val service: TrackedService, val info: NsdServiceInfo?) : DiscoveryEvent
    data class Checked(val target: ProbeTarget, val banner: String?, val sweep: Boolean) : DiscoveryEvent
    data class Named(val machine: Pair<NearbyNetwork, String>, val name: String?) : DiscoveryEvent
    data object SweepDone : DiscoveryEvent
}

@RequiresApi(34)
private fun watchService(
    manager: NsdManager,
    info: NsdServiceInfo,
    onUpdated: (NsdServiceInfo) -> Unit,
    onLost: () -> Unit,
): () -> Unit {
    val callback = object : NsdManager.ServiceInfoCallback {
        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = onLost()
        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = onUpdated(serviceInfo)
        override fun onServiceLost() = onLost()
        override fun onServiceInfoCallbackUnregistered() = Unit
    }
    manager.registerServiceInfoCallback(info, Executor { it.run() }, callback)
    return { runCatching { manager.unregisterServiceInfoCallback(callback) } }
}
