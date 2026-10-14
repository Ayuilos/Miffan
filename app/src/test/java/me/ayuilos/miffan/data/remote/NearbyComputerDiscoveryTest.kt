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
    fun bannerIdentifiesWindowsAndLinuxButNotGenericOpenSsh() {
        assertEquals(NearbyBanner(NearbySystem.WINDOWS), nearbyBannerSystem("SSH-2.0-OpenSSH_for_Windows_9.5"))
        for (distribution in listOf("Ubuntu", "Debian", "Raspbian", "Fedora", "Arch", "CachyOS", "Alpine", "NixOS")) {
            assertEquals(NearbyBanner(NearbySystem.LINUX, distribution), nearbyBannerSystem("SSH-2.0-OpenSSH_9.6p1 $distribution-3"))
        }
        assertEquals(NearbyBanner(NearbySystem.UNKNOWN), nearbyBannerSystem("SSH-2.0-OpenSSH_9.6"))
        assertEquals(NearbyBanner(NearbySystem.UNKNOWN), nearbyBannerSystem("SSH-2.0-OpenSSH_9.6 March-1"))
    }

    @Test
    fun mdnsNameAndPortWinAndBannerFillsSystemRegardlessOfSourceOrder() {
        val ad = NearbyAdvertisement("Recognisable computer", "192.168.1.10", 2222)
        val banner = NearbyComputer("reverse-name", ad.host, 22, NearbySystem.LINUX, "Debian")
        val expected = listOf(NearbyComputer(ad.name, ad.host, 2222, NearbySystem.LINUX, "Debian"))
        assertEquals(expected, mergeNearbyComputers(listOf(ad), listOf(banner)))
        assertEquals(expected, mergeNearbyComputers(listOf(ad, ad.copy(name = "Sunshine", sshPort = null)), listOf(banner)))
    }

    @Test
    fun hintsNeedBannerAndAppleEvidenceCanFillUnknownSystem() {
        val ad = NearbyAdvertisement("Mac", "192.168.1.10", null, apple = true)
        assertEquals(emptyList<NearbyComputer>(), mergeNearbyComputers(listOf(ad), emptyList()))
        assertEquals(listOf(NearbyComputer(ad.name, ad.host, 22, NearbySystem.MAC)),
            mergeNearbyComputers(listOf(ad), listOf(NearbyComputer(null, ad.host, 22, NearbySystem.UNKNOWN))))
        assertEquals(listOf(NearbyComputer(ad.name, ad.host, 2222, NearbySystem.MAC)),
            mergeNearbyComputers(listOf(ad.copy(sshPort = 2222)), emptyList()))
    }

    @Test
    fun appleEvidenceRequiresMacModelOrKnownServiceType() {
        org.junit.Assert.assertTrue(nearbyAppleEvidence("_sftp-ssh._tcp", null))
        org.junit.Assert.assertTrue(nearbyAppleEvidence("_companion-link._tcp", null))
        org.junit.Assert.assertTrue(nearbyAppleEvidence("_device-info._tcp", "MacBookPro18,3"))
        org.junit.Assert.assertFalse(nearbyAppleEvidence("_device-info._tcp", "iPhone15,2"))
        org.junit.Assert.assertFalse(nearbyAppleEvidence("_ssh._tcp", null))
    }

    @Test
    fun sweepOnlyHostMayHaveNoNameAndAliasesCollapseByHost() {
        val computer = NearbyComputer(null, "192.168.1.10", 22, NearbySystem.UNKNOWN)
        assertEquals(listOf(computer), mergeNearbyComputers(emptyList(), listOf(computer)))
        val ads = listOf(NearbyAdvertisement("Alpha", computer.host, 22), NearbyAdvertisement("Beta", computer.host, 2222))
        assertEquals(listOf(computer.copy(name = "Alpha")), mergeNearbyComputers(ads, listOf(computer)))
        assertEquals(listOf(computer.copy(name = "Beta", port = 2222)), mergeNearbyComputers(ads.drop(1), listOf(computer)))
    }

    @Test
    fun sweepIsCappedAndExcludesOwnNetworkAndBroadcast() {
        val slash24 = nearbySweepAddresses("192.168.31.10", 24)
        assertEquals(253, slash24.size)
        assertEquals("192.168.31.1", slash24.first())
        assertEquals("192.168.31.254", slash24.last())
        org.junit.Assert.assertFalse("192.168.31.10" in slash24)
        assertEquals(slash24, nearbySweepAddresses("192.168.31.10", 16))
        val slash25 = nearbySweepAddresses("192.168.31.140", 25)
        assertEquals(125, slash25.size)
        assertEquals("192.168.31.129", slash25.first())
        assertEquals("192.168.31.254", slash25.last())
        org.junit.Assert.assertFalse("192.168.31.140" in slash25)
        assertEquals(listOf("192.168.31.142"), nearbySweepAddresses("192.168.31.141", 30))
        assertEquals(emptyList<String>(), nearbySweepAddresses("192.168.31.141", 31))
        assertEquals(emptyList<String>(), nearbySweepAddresses("192.168.31.141", 32))
        assertEquals(emptyList<String>(), nearbySweepAddresses("invalid", 24))
    }

    @Test
    fun physicalNetworkIsChosenInsteadOfVpnEvenWhenVpnReportsWifi() {
        val vpn = NearbyInterface("100.100.1.1", 32, wifi = true, vpn = true)
        val cellular = NearbyInterface("10.0.0.1", 24)
        val ethernet = NearbyInterface("192.168.2.10", 24, ethernet = true)
        val wifi = NearbyInterface("192.168.31.10", 24, wifi = true)
        assertEquals(wifi, chooseNearbyInterface(listOf(vpn, cellular, ethernet, wifi)))
        assertEquals(ethernet, chooseNearbyInterface(listOf(vpn, cellular, ethernet)))
        assertNull(chooseNearbyInterface(listOf(vpn, cellular)))
    }

    @Test
    fun reverseNameOnlyStripsFinalLocalOrRouterDomain() {
        assertNull(nearbyReverseName("192.168.31.61", "192.168.31.61"))
        assertEquals("cachyos-x8664", nearbyReverseName("192.168.31.61", "cachyos-x8664.lan."))
        assertEquals("Mac", nearbyReverseName("192.168.31.61", "Mac.LOCAL"))
        assertEquals("linux", nearbyReverseName("192.168.31.61", "linux.home"))
        assertEquals("linux", nearbyReverseName("192.168.31.61", "linux.router.example", listOf("router.example")))
        assertEquals("linux.lan.example", nearbyReverseName("192.168.31.61", "linux.lan.example"))
    }

    @Test
    fun tailscaleDetectionNeedsBothVpnAndCgnatAddress() {
        val headscale = NearbyInterface("100.64.0.4", 32, vpn = true)
        assertEquals(headscale, chooseNearbyTailscaleInterface(listOf(headscale)))
        assertEquals(headscale.copy(address = "100.127.255.254"),
            chooseNearbyTailscaleInterface(listOf(headscale.copy(address = "100.127.255.254"))))
        for (address in listOf("100.63.255.255", "100.128.0.1", "192.168.1.4", "invalid")) {
            assertNull(chooseNearbyTailscaleInterface(listOf(headscale.copy(address = address))))
        }
        assertNull(chooseNearbyTailscaleInterface(listOf(headscale.copy(vpn = false, wifi = true))))
        assertNull(chooseNearbyInterface(listOf(headscale)))
    }

    @Test
    fun tailscaleRangeUsesOwnSlash24AndExcludesPhone() {
        val addresses = nearbySweepAddresses("100.64.0.4", 24)
        assertEquals(253, addresses.size)
        assertEquals("100.64.0.1", addresses.first())
        assertEquals("100.64.0.254", addresses.last())
        org.junit.Assert.assertTrue("100.64.0.5" in addresses)
        org.junit.Assert.assertFalse("100.64.0.4" in addresses)
        org.junit.Assert.assertFalse("100.64.1.5" in addresses)
    }

    @Test
    fun networkTagsArePreservedAndNeverMergedAcrossNetworks() {
        val local = NearbyComputer("CachyOS", "192.168.31.61", 22, NearbySystem.LINUX, "Arch")
        val tailscale = local.copy(host = "100.64.0.5", network = NearbyNetwork.TAILSCALE)
        assertEquals(setOf(local, tailscale), mergeNearbyComputers(emptyList(), listOf(local, tailscale)).toSet())
        // Even an overlapping address on two networks has independent names and evidence.
        val overlap = tailscale.copy(host = local.host, name = "tailnet-name", system = NearbySystem.WINDOWS)
        assertEquals(setOf(local, overlap), mergeNearbyComputers(emptyList(), listOf(local, overlap)).toSet())
        val ads = listOf(NearbyAdvertisement("LAN name", local.host, 22))
        assertEquals(setOf(local.copy(name = "LAN name"), overlap), mergeNearbyComputers(ads, listOf(local, overlap)).toSet())
    }

    @Test
    fun tailscaleReverseNamesUseOnlyFirstLabelForMagicDnsAndHeadscale() {
        assertEquals("cachyos-x8664", nearbyReverseName("100.64.0.5", "cachyos-x8664.tail123.ts.net.", network = NearbyNetwork.TAILSCALE))
        assertEquals("cachyos-x8664", nearbyReverseName("100.64.0.5", "cachyos-x8664.headscale.example.", network = NearbyNetwork.TAILSCALE))
        assertNull(nearbyReverseName("100.64.0.5", "100.64.0.5", network = NearbyNetwork.TAILSCALE))
    }

    private fun addresses(vararg hosts: String): List<InetAddress> = hosts.map(InetAddress::getByName)
}
