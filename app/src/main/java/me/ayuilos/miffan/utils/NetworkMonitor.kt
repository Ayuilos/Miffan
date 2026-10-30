package me.ayuilos.miffan.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * Whether the phone has a default network that offers internet. Not whether Android validated it: validation
 * fails on networks that still work (its check servers are unreachable in some places), and "no internet"
 * must never show while a reply can still arrive.
 */
class NetworkMonitor(context: Context, scope: CoroutineScope) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private fun NetworkCapabilities?.reachesInternet() =
        this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    private fun current(): Boolean = manager?.let { it.getNetworkCapabilities(it.activeNetwork).reachesInternet() } ?: true

    // Only watched while a screen shows a pending reply. Without a ConnectivityManager nothing can be said, so assume online.
    val online: StateFlow<Boolean> = callbackFlow {
        val connectivity = manager
        if (connectivity == null) {
            trySend(true)
            awaitClose()
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(connectivity.getNetworkCapabilities(network).reachesInternet())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.reachesInternet())
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        trySend(current())
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5_000), current())
}
