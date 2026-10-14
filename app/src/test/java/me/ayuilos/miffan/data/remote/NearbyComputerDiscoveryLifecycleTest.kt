package me.ayuilos.miffan.data.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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

/** The JVM Android stub's SDK_INT is zero, so these exercise the serial legacy resolver. */
class NearbyComputerDiscoveryLifecycleTest {
    private val context = mockk<Context>()
    private val manager = mockk<NsdManager>(relaxed = true)
    private val wifi = mockk<WifiManager>()
    private val multicastLock = mockk<WifiManager.MulticastLock>(relaxed = true)
    private val discoveryListener = slot<NsdManager.DiscoveryListener>()
    private val resolutions = mutableListOf<Pair<NsdServiceInfo, NsdManager.ResolveListener>>()
    private val discovery = NearbyComputerDiscovery(context)

    init {
        every { context.applicationContext } returns context
        every { context.getSystemService(NsdManager::class.java) } returns manager
        every { context.getSystemService(WifiManager::class.java) } returns wifi
        every { wifi.createMulticastLock(any()) } returns multicastLock
        every { multicastLock.isHeld } returns true
        every {
            manager.discoverServices("_ssh._tcp", NsdManager.PROTOCOL_DNS_SD, capture(discoveryListener))
        } returns Unit
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
        val emissions = mutableListOf<List<NearbyComputer>>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { emissions += it }
        }
        runCurrent()
        assertEquals(listOf(emptyList<NearbyComputer>()), emissions)
        verify(exactly = 1) { multicastLock.setReferenceCounted(false) }
        verify(exactly = 1) { multicastLock.acquire() }
        every { manager.stopServiceDiscovery(any()) } throws IllegalArgumentException("already stopped")
        collector.cancelAndJoin()
        verify(exactly = 1) { manager.stopServiceDiscovery(discoveryListener.captured) }
        verify(exactly = 1) { multicastLock.release() }
    }

    @Test
    fun legacyResolvesAreSerialAndLostServiceCannotBeRestoredByLateResult() = runTest {
        val emissions = mutableListOf<List<NearbyComputer>>()
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
        assertEquals(emptyList<NearbyComputer>(), emissions.last())
        resolutions[1].second.onServiceResolved(second)
        runCurrent()
        assertEquals(listOf(NearbyComputer("Linux", "192.168.1.11", 22)), emissions.last())
        discoveryListener.captured.onServiceLost(second)
        runCurrent()
        assertEquals(emptyList<NearbyComputer>(), emissions.last())
        collector.cancelAndJoin()
    }

    @Test
    fun stuckResolveTimesOutAndItsLateCallbackDoesNotChangeTheNextResolve() = runTest {
        val emissions = mutableListOf<List<NearbyComputer>>()
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
        assertEquals(emptyList<NearbyComputer>(), emissions.last())
        resolutions[1].second.onServiceResolved(second)
        runCurrent()
        assertEquals(listOf(NearbyComputer("Linux", "192.168.1.11", 22)), emissions.last())
        collector.cancelAndJoin()
    }

    @Test
    fun discoveryStartFailureCompletesWithoutThrowingAndReleasesLock() = runTest {
        val emissions = mutableListOf<List<NearbyComputer>>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            discovery.observe().collect { emissions += it }
        }
        runCurrent()
        discoveryListener.captured.onStartDiscoveryFailed("_ssh._tcp", NsdManager.FAILURE_INTERNAL_ERROR)
        runCurrent()
        assertTrue(collector.isCompleted)
        assertEquals(listOf(emptyList<NearbyComputer>()), emissions)
        verify(exactly = 1) { multicastLock.release() }
    }

    @Suppress("DEPRECATION")
    private fun service(name: String, host: String): NsdServiceInfo = mockk {
        every { serviceName } returns name
        every { this@mockk.host } returns InetAddress.getByName(host)
        every { port } returns 22
    }
}
