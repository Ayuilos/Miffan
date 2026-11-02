package me.ayuilos.miffan.data.repository.relay

import me.ayuilos.miffan.data.db.entity.RELAY_LAST_ADDRESS
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.RemoteHostRelay
import me.ayuilos.miffan.data.db.entity.encodeRemoteHostRelay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreconnectTest {
    private fun host(lastAddress: String?, relay: RemoteHostRelay?) = RemoteHostEntity(
        id = "h", name = "Mac", host = "192.168.1.5", port = 22, username = "me", authType = "key",
        trustedHostKeySha256 = null, createdAt = 0, updatedAt = 0,
        lastAddressValue = lastAddress, relayValue = encodeRemoteHostRelay(relay),
    )

    private val relay = RemoteHostRelay("https://relay.example", "abcdefghijklmnopqrstuvwxyz")

    @Test fun chatSkipsAComputerLastReachedThroughItsRelay() {
        assertFalse(shouldPreconnect(host(RELAY_LAST_ADDRESS, relay), throughRelay = false))
    }

    @Test fun chatStillPreconnectsAtHome() {
        assertTrue(shouldPreconnect(host("192.168.1.5:22", relay), throughRelay = false))
        assertTrue(shouldPreconnect(host(null, null), throughRelay = false))
        // A relay turned off on this phone will not be raced, so the connection is direct only.
        assertTrue(shouldPreconnect(host(RELAY_LAST_ADDRESS, relay.copy(enabled = false)), throughRelay = false))
    }

    @Test fun computerPageAlwaysPreconnects() {
        assertTrue(shouldPreconnect(host(RELAY_LAST_ADDRESS, relay), throughRelay = true))
    }
}
