package me.ayuilos.miffan.data.repository

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceNetworkBrokerTest {
    @Test
    fun `network broker rejects local and special-use addresses`() {
        listOf(
            "0.0.0.0",
            "127.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.168.1.1",
            "192.0.2.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "::",
            "::1",
            "fc00::1",
            "fe80::1",
            "ff02::1",
            "100::1",
            "2001::1",
            "2001:2::1",
            "2001:db8::1",
            "2002:7f00:1::1",
        ).forEach { address ->
            assertFalse(address, with(WorkspaceNetworkBroker) {
                InetAddress.getByName(address).isPublicRoutable()
            })
        }
    }

    @Test
    fun `network broker accepts globally routable addresses`() {
        listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111").forEach { address ->
            assertTrue(address, with(WorkspaceNetworkBroker) {
                InetAddress.getByName(address).isPublicRoutable()
            })
        }
    }
}
