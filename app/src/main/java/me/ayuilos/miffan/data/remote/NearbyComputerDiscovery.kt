package me.ayuilos.miffan.data.remote

import android.content.Context
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.Executor

data class NearbyComputer(
    /** DNS-SD instance name, e.g. "Ekk's MacBook Pro"; what the user recognises. */
    val name: String,
    /** Numeric address to connect to; IPv4 preferred, never link-local IPv6. */
    val host: String,
    val port: Int,
)

class NearbyComputerDiscovery(private val context: Context) {
    /**
     * Computers on the local network that advertise `_ssh._tcp`, sorted by name, updated as they
     * appear and disappear. Cold: discovery runs only while collected and stops on cancellation.
     * Emits an empty list first. Unavailable networking and discovery/resolve failures are ignored.
     */
    fun observe(): Flow<List<NearbyComputer>> = callbackFlow {
        val manager = runCatching { context.getSystemService(NsdManager::class.java) }.getOrNull()
        if (manager == null) {
            close()
            return@callbackFlow
        }

        // Binder callbacks only write to this thread-safe channel. The worker exclusively owns
        // the maps and resolve queue, including registration and cleanup of service callbacks.
        val events = Channel<DiscoveryEvent>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) { close() }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                events.trySend(DiscoveryEvent.Found(serviceInfo))
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                events.trySend(DiscoveryEvent.Lost(serviceInfo.serviceName))
            }
        }
        val worker = launch {
            val services = mutableMapOf<String, TrackedService>()
            var computers = emptyMap<String, NearbyComputer>()
            val queue = ArrayDeque<TrackedService>()
            var resolving: TrackedService? = null
            var timeout: Job? = null

            fun publish(service: TrackedService, info: NsdServiceInfo?) {
                val name = service.info.serviceName
                if (services[name] !== service) return // Lost or replaced while resolving.
                val addresses = if (info == null) emptyList() else if (Build.VERSION.SDK_INT >= 34) {
                    info.hostAddresses
                } else {
                    @Suppress("DEPRECATION")
                    listOfNotNull(info.host)
                }
                val host = chooseNearbyComputerAddress(addresses)
                computers = if (host != null && info != null && info.port in 1..65535) {
                    mergeNearbyComputer(computers, NearbyComputer(name, host, info.port))
                } else {
                    removeNearbyComputer(computers, name)
                }
                trySend(sortNearbyComputers(computers))
            }

            @Suppress("DEPRECATION")
            fun resolveNext() {
                while (resolving == null && queue.isNotEmpty()) {
                    val service = queue.removeFirst()
                    if (services[service.info.serviceName] !== service) continue
                    resolving = service
                    timeout = launch {
                        delay(RESOLVE_TIMEOUT_MILLIS)
                        events.trySend(DiscoveryEvent.Resolved(service, null))
                    }
                    val resolveListener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            events.trySend(DiscoveryEvent.Resolved(service, null))
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            events.trySend(DiscoveryEvent.Resolved(service, serviceInfo))
                        }
                    }
                    // Before API 34 NSD has no resolution cancellation API. A timeout advances
                    // our queue; a late result is ignored instead of resurrecting an old entry.
                    runCatching { manager.resolveService(service.info, resolveListener) }
                        .onFailure { events.trySend(DiscoveryEvent.Resolved(service, null)) }
                }
            }

            fun remove(service: TrackedService) {
                val name = service.info.serviceName
                if (services[name] !== service) return
                services.remove(name)
                queue.remove(service)
                service.stopWatching?.invoke()
                computers = removeNearbyComputer(computers, name)
                trySend(sortNearbyComputers(computers))
            }

            try {
                for (event in events) {
                    when (event) {
                        is DiscoveryEvent.Found -> {
                            val name = event.info.serviceName
                            if (name.isNullOrBlank() || name in services) continue
                            val service = TrackedService(event.info)
                            services[name] = service
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
                        is DiscoveryEvent.Lost -> services[event.name]?.let(::remove)
                        is DiscoveryEvent.Updated -> publish(event.service, event.info)
                        is DiscoveryEvent.Unavailable -> remove(event.service)
                        is DiscoveryEvent.Resolved -> {
                            if (resolving !== event.service) continue
                            timeout?.cancel()
                            timeout = null
                            resolving = null
                            publish(event.service, event.info)
                            resolveNext()
                        }
                    }
                }
            } finally {
                timeout?.cancel()
                services.values.forEach { it.stopWatching?.invoke() }
            }
        }

        var multicastLock: WifiManager.MulticastLock? = null
        try {
            runCatching {
                context.applicationContext.getSystemService(WifiManager::class.java)
                    ?.createMulticastLock("nearby-computers")?.also {
                        multicastLock = it
                        it.setReferenceCounted(false)
                        it.acquire()
                    }
            }
            runCatching { manager.discoverServices("_ssh._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { close() }
            awaitClose()
        } finally {
            events.close()
            withContext(NonCancellable) { worker.cancelAndJoin() }
            // A failed start or a concurrently stopped discovery can invalidate this listener.
            runCatching { manager.stopServiceDiscovery(listener) }
            runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        }
    }.conflate().onStart { emit(emptyList()) }.distinctUntilChanged()
}

private const val RESOLVE_TIMEOUT_MILLIS = 5_000L

private class TrackedService(val info: NsdServiceInfo) {
    var stopWatching: (() -> Unit)? = null
}

private sealed interface DiscoveryEvent {
    data class Found(val info: NsdServiceInfo) : DiscoveryEvent
    data class Lost(val name: String) : DiscoveryEvent
    data class Updated(val service: TrackedService, val info: NsdServiceInfo) : DiscoveryEvent
    data class Unavailable(val service: TrackedService) : DiscoveryEvent
    data class Resolved(val service: TrackedService, val info: NsdServiceInfo?) : DiscoveryEvent
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

internal fun chooseNearbyComputerAddress(addresses: List<InetAddress>): String? {
    val usable = addresses.filterNot { it.isLoopbackAddress || it.isAnyLocalAddress || it.isMulticastAddress }
    val address = usable.firstOrNull { it is Inet4Address }
        ?: usable.firstOrNull {
            it is Inet6Address && !it.isLinkLocalAddress && !it.isSiteLocalAddress &&
                // Unique-local IPv6 (fc00::/7) is not globally routable either.
                (it.address[0].toInt() and 0xfe) != 0xfc
        }
    return address?.hostAddress
}

internal fun mergeNearbyComputer(
    computers: Map<String, NearbyComputer>,
    computer: NearbyComputer,
): Map<String, NearbyComputer> = computers + (computer.name to computer)

internal fun removeNearbyComputer(
    computers: Map<String, NearbyComputer>,
    name: String,
): Map<String, NearbyComputer> = computers - name

internal fun sortNearbyComputers(computers: Map<String, NearbyComputer>): List<NearbyComputer> =
    computers.values
        .sortedWith(compareBy<NearbyComputer, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name })
        // Keep aliases in state so losing the displayed name can reveal another advertisement.
        .distinctBy { it.host to it.port }
