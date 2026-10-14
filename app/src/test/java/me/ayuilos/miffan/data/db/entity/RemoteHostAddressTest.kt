package me.ayuilos.miffan.data.db.entity

import org.junit.Assert.*
import org.junit.Test

class RemoteHostAddressTest {
    @Test fun classifiesNetworkBoundariesAndNamesWithoutDns() {
        listOf("100.64.0.0", "100.127.255.255", "computer.example.ts.net", "Computer.TS.NET.").forEach {
            assertEquals(it, RemoteAddressNetwork.TAILSCALE, remoteAddressNetwork(it))
        }
        listOf("10.0.0.0", "172.16.0.0", "172.31.255.255", "192.168.255.255", "169.254.1.2",
            "computer.local", "computer.LAN", "computer.home", "fe80::1", "[fe80::2]", "fd00::1").forEach {
            assertEquals(it, RemoteAddressNetwork.LOCAL, remoteAddressNetwork(it))
        }
        listOf("100.63.255.255", "100.128.0.0", "172.15.255.255", "172.32.0.0", "192.169.1.1", "8.8.8.8",
            "example.com", "local", "evil.local.example", "100.64.0.999", "100.64.0.1.2", "2001:db8::1").forEach {
            assertEquals(it, RemoteAddressNetwork.OTHER, remoteAddressNetwork(it))
        }
    }

    @Test fun addressesAreMainFirstAndLastIsOnlyUsedWhenStillSaved() {
        val main = RemoteHostAddress("host", 22)
        val alternate = RemoteHostAddress("fe80::1", 2222)
        val row = RemoteHostEntity("id", "name", main.host, main.port, "user", "PASSWORD",
            trustedHostKeySha256 = null, createdAt = 1, updatedAt = 1,
            alternateAddresses = encodeRemoteHostAddresses(listOf(main, alternate, alternate)), lastAddressValue = alternate.storageValue)
        assertEquals("[fe80::1]:2222", alternate.storageValue)
        assertEquals(listOf(main, alternate), row.addresses)
        assertEquals(listOf(alternate, main), row.connectionAddresses)
        assertNull(row.copy(lastAddressValue = "missing:22").lastAddress)
        assertEquals(listOf(main, alternate), row.copy(lastAddressValue = "missing:22").connectionAddresses)
        assertEquals(listOf(main), row.copy(alternateAddresses = "invalid").addresses)
    }
}
