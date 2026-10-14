package me.ayuilos.miffan.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import javax.net.SocketFactory

/** The JVM Android stub's SDK_INT is zero, so these exercise the serial legacy resolver. */
class NearbyComputerDiscoveryLifecycleTest {
    private val context = mockk<Context>()
    private val manager = mockk<NsdManager>(relaxed = true)
    private val wifi = mockk<WifiManager>()
    private val multicastLock = mockk<WifiManager.MulticastLock>(relaxed = true)
    private val discoveryListener = slot<NsdManager.DiscoveryListener>()
    private val resolutions = mutableListOf<Pair<NsdServiceInfo, NsdManager.ResolveListener>>()
    private val listeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private var probeBanner: suspend (String, Int) -> String? = { _, _ -> null }
    private val probes = mutableListOf<Triple<String, SocketFactory, Int>>()
    private val discovery = NearbyComputerDiscovery(context) { host, port, factory, timeout ->
        probes += Triple(host, factory, timeout)
        probeBanner(host, port)
    }

    init {
        every { context.applicationContext } returns context
        every { context.getSystemService(NsdManager::class.java) } returns manager
        every { context.getSystemService(WifiManager::class.java) } returns wifi
        every { wifi.createMulticastLock(any()) } returns multicastLock
        every { multicastLock.isHeld } returns true
        every { manager.discoverServices(any(), NsdManager.PROTOCOL_DNS_SD, any()) } answers {
            val type = firstArg<String>()
            val listener = thirdArg<NsdManager.DiscoveryListener>()
            listeners[type] = listener
            if (type == "_ssh._tcp") discoveryListener.captured = listener
        }
        @Suppress("DEPRECATION")
        every { manager.resolveService(any(), any<NsdManager.ResolveListener>()) } answers {
            resolutions += firstArg<NsdServiceInfo>() to secondArg<NsdManager.ResolveListener>()
        }
    }

    @Test
    fun collectionStartsDiscoveryAndCancellationReleasesResources() = runTest {
        val flow = discovery.observe()
        verify { manager wasNot Called }
        verify { multicastLock wasNot Called }
        val emissions = mutableListOf<NearbyScan>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { emissions += it }
        }
        runCurrent()
        assertEquals(NearbyScan(emptyList(), true), emissions.first())
        assertEquals(NearbyScan(emptyList(), false), emissions.last())
        verify(exactly = 1) { multicastLock.setReferenceCounted(false) }
        verify(exactly = 1) { multicastLock.acquire() }
        assertEquals(setOf("_ssh._tcp", "_sftp-ssh._tcp", "_nvstream._tcp", "_rfb._tcp", "_smb._tcp",
            "_workstation._tcp", "_device-info._tcp", "_companion-link._tcp"), listeners.keys)
        every { manager.stopServiceDiscovery(any()) } throws IllegalArgumentException("already stopped")
        collector.cancelAndJoin()
        verify(exactly = 1) { manager.stopServiceDiscovery(discoveryListener.captured) }
        verify(exactly = 8) { manager.stopServiceDiscovery(any()) }
        verify(exactly = 1) { multicastLock.release() }
    }

    @Test
    fun legacyResolvesAreSerialAndLostServiceCannotBeRestoredByLateResult() = runTest {
        val emissions = mutableListOf<NearbyScan>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            discovery.observe().collect { emissions += it }
        }
        runCurrent()
        val first = service("Mac", "192.168.1.10")
        val second = service("Linux", "192.168.1.11")
        discoveryListener.captured.onServiceFound(first)
        discoveryListener.captured.onServiceFound(first) // Same name on a second interface.
        discoveryListener.captured.onServiceFound(second)
        runCurrent()
        assertEquals(1, resolutions.size)
        discoveryListener.captured.onServiceLost(first)
        resolutions[0].second.onServiceResolved(first)
        runCurrent()
        assertEquals(2, resolutions.size)
        assertEquals(emptyList<NearbyComputer>(), emissions.last().computers)
        resolutions[1].second.onServiceResolved(second)
        runCurrent()
        assertEquals(listOf(NearbyComputer("Linux", "192.168.1.11", 22, NearbySystem.UNKNOWN)), emissions.last().computers)
        discoveryListener.captured.onServiceLost(second)
        runCurrent()
        assertEquals(emptyList<NearbyComputer>(), emissions.last().computers)
        collector.cancelAndJoin()
    }

    @Test
    fun stuckResolveTimesOutAndItsLateCallbackDoesNotChangeTheNextResolve() = runTest {
        val emissions = mutableListOf<NearbyScan>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            discovery.observe().collect { emissions += it }
        }
        runCurrent()
        val first = service("Stuck", "192.168.1.10")
        val second = service("Linux", "192.168.1.11")
        discoveryListener.captured.onServiceFound(first)
        discoveryListener.captured.onServiceFound(second)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, resolutions.size)
        resolutions[0].second.onServiceResolved(first)
        runCurrent()
        assertEquals(emptyList<NearbyComputer>(), emissions.last().computers)
        resolutions[1].second.onServiceResolved(second)
        runCurrent()
        assertEquals(listOf(NearbyComputer("Linux", "192.168.1.11", 22, NearbySystem.UNKNOWN)), emissions.last().computers)
        collector.cancelAndJoin()
    }

    @Test
    fun oneDiscoveryStartFailureDoesNotStopOtherBrowsers() = runTest {
        val emissions = mutableListOf<NearbyScan>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            discovery.observe().collect { emissions += it }
        }
        runCurrent()
        discoveryListener.captured.onStartDiscoveryFailed("_ssh._tcp", NsdManager.FAILURE_INTERNAL_ERROR)
        runCurrent()
        assertTrue(collector.isActive)
        collector.cancelAndJoin()
        assertEquals(NearbyScan(emptyList(), true), emissions.first())
        assertEquals(NearbyScan(emptyList(), false), emissions.last())
        verify(exactly = 1) { multicastLock.release() }
    }

    @Test
    fun identicalNamesOnDifferentServiceTypesAreTrackedIndependently() = runTest {
        val emissions = mutableListOf<NearbyScan>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            discovery.observe().collect { emissions += it }
        }
        runCurrent()
        val ssh = service("Computer", "192.168.1.10")
        val sftp = service("Computer", "192.168.1.10")
        listeners.getValue("_ssh._tcp").onServiceFound(ssh)
        listeners.getValue("_sftp-ssh._tcp").onServiceFound(sftp)
        runCurrent()
        resolutions[0].second.onServiceResolved(ssh)
        runCurrent()
        resolutions[1].second.onServiceResolved(sftp)
        runCurrent()
        assertEquals(listOf(NearbyComputer("Computer", "192.168.1.10", 22, NearbySystem.MAC)), emissions.last().computers)
        listeners.getValue("_sftp-ssh._tcp").onServiceLost(sftp)
        runCurrent()
        assertEquals(listOf(NearbyComputer("Computer", "192.168.1.10", 22, NearbySystem.UNKNOWN)), emissions.last().computers)
        collector.cancelAndJoin()
    }

    @Test
    fun hintIsListedOnlyAfterSshBannerAndIsRemovedWhenLost() = runTest {
        val banner = CompletableDeferred<String?>()
        probeBanner = { _, port -> assertEquals(22, port); banner.await() }
        val emissions = mutableListOf<NearbyScan>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            discovery.observe().collect { emissions += it }
        }
        runCurrent()
        val hint = service("CachyOS Sunshine", "192.168.1.61")
        every { hint.port } returns 47989
        listeners.getValue("_nvstream._tcp").onServiceFound(hint)
        runCurrent()
        resolutions[0].second.onServiceResolved(hint)
        runCurrent()
        assertEquals(emptyList<NearbyComputer>(), emissions.last().computers)
        banner.complete("SSH-2.0-OpenSSH_9.6 CachyOS")
        runCurrent()
        assertEquals(listOf(NearbyComputer("CachyOS Sunshine", "192.168.1.61", 22, NearbySystem.LINUX, "CachyOS")), emissions.last().computers)
        listeners.getValue("_nvstream._tcp").onServiceLost(hint)
        runCurrent()
        assertEquals(emptyList<NearbyComputer>(), emissions.last().computers)
        collector.cancelAndJoin()
    }

    @Test
    fun lateHintProbeCannotResurrectLostAdvertisement() = runTest {
        val banner = CompletableDeferred<String?>()
        probeBanner = { _, _ -> banner.await() }
        val emissions = mutableListOf<NearbyScan>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            discovery.observe().collect { emissions += it }
        }
        runCurrent()
        val hint = service("Lost", "192.168.1.61")
        listeners.getValue("_smb._tcp").onServiceFound(hint)
        runCurrent()
        resolutions[0].second.onServiceResolved(hint)
        runCurrent()
        listeners.getValue("_smb._tcp").onServiceLost(hint)
        runCurrent()
        banner.complete("SSH-2.0-OpenSSH_9.6 Debian")
        runCurrent()
        assertEquals(emptyList<NearbyComputer>(), emissions.last().computers)
        collector.cancelAndJoin()
    }

    @Test
    fun sweepsBindToEachNetworkAndRemainSearchingUntilVpnSweepFinishes() = runTest {
        val connectivity = mockk<ConnectivityManager>()
        val wifiFactory = mockk<SocketFactory>()
        val vpnFactory = mockk<SocketFactory>()
        val wifiNetwork = network(connectivity, "192.168.31.1", 30, vpn = false, wifiFactory)
        val vpnNetwork = network(connectivity, "100.64.0.4", 32, vpn = true, vpnFactory)
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { connectivity.allNetworks } returns arrayOf(vpnNetwork, wifiNetwork)
        val gate = CompletableDeferred<String?>()
        probeBanner = { host, _ -> if (host.startsWith("100.")) gate.await() else null }
        val emissions = mutableListOf<NearbyScan>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            discovery.observe().collect { emissions += it }
        }
        runCurrent()
        assertTrue(emissions.last().tailscaleActive)
        assertTrue(emissions.last().searching)
        assertTrue(Triple("192.168.31.2", wifiFactory, 400) in probes)
        assertTrue(probes.filter { it.first.startsWith("100.") }.all { it.second === vpnFactory && it.third == 700 })
        gate.complete(null)
        runCurrent()
        assertEquals(254, probes.size)
        assertEquals(NearbyScan(emptyList(), searching = false, tailscaleActive = true), emissions.last())
        collector.cancelAndJoin()
    }

    private fun network(
        manager: ConnectivityManager,
        ip: String,
        prefix: Int,
        vpn: Boolean,
        factory: SocketFactory,
    ): Network {
        val network = mockk<Network>()
        val address = mockk<LinkAddress> {
            every { this@mockk.address } returns InetAddress.getByName(ip)
            every { prefixLength } returns prefix
        }
        val properties = mockk<LinkProperties> {
            every { linkAddresses } returns listOf(address)
            every { domains } returns null
        }
        val capabilities = mockk<NetworkCapabilities> {
            every { hasTransport(any()) } answers {
                if (vpn) firstArg<Int>() == NetworkCapabilities.TRANSPORT_VPN
                else firstArg<Int>() == NetworkCapabilities.TRANSPORT_WIFI
            }
        }
        every { manager.getNetworkCapabilities(network) } returns capabilities
        every { manager.getLinkProperties(network) } returns properties
        every { network.socketFactory } returns factory
        return network
    }

    @Suppress("DEPRECATION")
    private fun service(name: String, host: String): NsdServiceInfo = mockk {
        every { serviceName } returns name
        every { this@mockk.host } returns InetAddress.getByName(host)
        every { port } returns 22
        every { attributes } returns emptyMap()
    }
}
