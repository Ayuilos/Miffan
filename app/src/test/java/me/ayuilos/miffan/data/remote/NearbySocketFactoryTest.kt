package me.ayuilos.miffan.data.remote

import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.Socket
import java.net.SocketException
import javax.net.SocketFactory

class NearbySocketFactoryTest {
    private val fallback = mockk<SocketFactory>()

    @Test
    fun failedBindingUsesFallbackWithoutOpeningAnotherSocket() {
        val preferred = mockk<SocketFactory>()
        every { preferred.createSocket() } throws SocketException("Operation not permitted")

        assertSame(fallback, chooseNearbySocketFactory(preferred, fallback))

        verify(exactly = 1) { preferred.createSocket() }
        verify { fallback wasNot Called }
    }

    @Test
    fun workingBindingKeepsPreferredFactoryAndClosesTestSocket() {
        val socket = mockk<Socket>(relaxed = true)
        val preferred = mockk<SocketFactory>()
        every { preferred.createSocket() } returns socket

        assertSame(preferred, chooseNearbySocketFactory(preferred, fallback))

        verify(exactly = 1) { preferred.createSocket() }
        verify(exactly = 1) { socket.close() }
        verify { fallback wasNot Called }
    }

    @Test
    fun missingNetworkUsesFallbackWithoutOpeningASocket() {
        assertSame(fallback, chooseNearbySocketFactory(null, fallback))

        verify { fallback wasNot Called }
    }
}
