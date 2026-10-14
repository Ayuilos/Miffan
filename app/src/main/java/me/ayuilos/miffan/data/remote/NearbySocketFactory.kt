package me.ayuilos.miffan.data.remote

import javax.net.SocketFactory

/** Prefer network-bound probes only when binding is allowed (e.g. by the active VPN). */
internal fun chooseNearbySocketFactory(
    preferred: SocketFactory?,
    fallback: SocketFactory = SocketFactory.getDefault(),
): SocketFactory {
    if (preferred == null) return fallback
    return try {
        preferred.createSocket().use { }
        preferred
    } catch (_: Exception) {
        fallback
    }
}
