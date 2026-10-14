package me.ayuilos.miffan.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class NearbyComputerDiscoveryTest {
    @Test
    fun ipv4IsPreferredEvenWhenIpv6ComesFirst() {
        assertEquals("192.168.1.10", chooseNearbyComputerAddress(addresses("2001:db8::1", "192.168.1.10")))
    }

    @Test
    fun firstIpv4IsChosen() {
        assertEquals("192.168.1.10", chooseNearbyComputerAddress(addresses("192.168.1.10", "192.168.1.11")))
    }

    @Test
    fun globalIpv6IsUsedWhenThereIsNoIpv4() {
        val global = InetAddress.getByName("2001:db8::1")
        assertEquals(global.hostAddress, chooseNearbyComputerAddress(addresses("fe80::1", "::1") + global))
    }

    @Test
    fun linkLocalIpv6IsRejected() {
        assertNull(chooseNearbyComputerAddress(addresses("fe80::1", "fe80::1234%1")))
    }

    @Test
    fun nonGlobalIpv6AndUnusableAddressesAreRejected() {
        assertNull(chooseNearbyComputerAddress(addresses("::1", "::", "ff02::1", "fec0::1", "fd00::1", "fc00::1")))
        assertNull(chooseNearbyComputerAddress(addresses("127.0.0.1", "0.0.0.0", "224.0.0.1")))
        assertNull(chooseNearbyComputerAddress(emptyList()))
    }

    @Test
    fun lostServiceIsRemovedByInstanceName() {
        val first = NearbyComputer("Mac", "192.168.1.10", 22)
        val second = NearbyComputer("Linux", "192.168.1.11", 22)
        val computers = mergeNearbyComputer(mergeNearbyComputer(emptyMap(), first), second)
        assertEquals(listOf(second), sortNearbyComputers(removeNearbyComputer(computers, first.name)))
        assertEquals(emptyList<NearbyComputer>(), sortNearbyComputers(removeNearbyComputer(mapOf(first.name to first), first.name)))
    }

    @Test
    fun sameInstanceNameReplacesItsPreviousAddress() {
        val original = NearbyComputer("Mac", "192.168.1.10", 22)
        val updated = original.copy(host = "192.168.1.11")
        assertEquals(listOf(updated), sortNearbyComputers(mergeNearbyComputer(mapOf(original.name to original), updated)))
    }

    @Test
    fun duplicateHostAndPortCollapsesButDifferentPortsRemain() {
        val first = NearbyComputer("Alpha", "192.168.1.10", 22)
        val alias = first.copy(name = "Beta")
        val otherPort = first.copy(name = "Gamma", port = 2222)
        val computers = listOf(alias, otherPort, first).associateBy { it.name }
        assertEquals(listOf(first, otherPort), sortNearbyComputers(computers))
        assertEquals(listOf(alias, otherPort), sortNearbyComputers(removeNearbyComputer(computers, first.name)))
    }

    @Test
    fun namesAreSortedCaseInsensitively() {
        val computers = listOf(
            NearbyComputer("zebra", "192.168.1.10", 22),
            NearbyComputer("Beta", "192.168.1.11", 22),
            NearbyComputer("alpha", "192.168.1.12", 22),
        ).associateBy { it.name }
        assertEquals(listOf("alpha", "Beta", "zebra"), sortNearbyComputers(computers).map { it.name })
    }

    private fun addresses(vararg hosts: String): List<InetAddress> = hosts.map(InetAddress::getByName)
}
