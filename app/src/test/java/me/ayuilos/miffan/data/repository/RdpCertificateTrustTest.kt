package me.ayuilos.miffan.data.repository

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.rerere.workspace.screen.RemoteScreenState
import org.junit.Assert.*
import org.junit.Test

class RdpCertificateTrustTest {
    private val pin = "ab".repeat(32)
    private val connected = RemoteScreenState.Connected("RDP", 1920, 1080, 1)

    @Test fun savedPinWinsAndMissingHelperKeepsTofu() {
        assertEquals(pin, expectedRdpCertificate(pin, "cd".repeat(32)))
        assertEquals(pin, expectedRdpCertificate(pin, null))
        assertEquals(pin, expectedRdpCertificate(null, pin))
        assertNull(expectedRdpCertificate(null, null))
    }

    @Test fun certificateReceiptDoesNotPinUntilConnected() = runTest {
        val state = MutableStateFlow<RemoteScreenState>(RemoteScreenState.Connecting)
        val certificate = MutableStateFlow<String?>(null)
        val saved = mutableListOf<String>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            persistVerifiedRdpCertificate(state, certificate, pin) { saved += it }
        }
        certificate.value = pin
        assertTrue(saved.isEmpty())
        state.value = connected
        observer.join()
        assertEquals(listOf(pin), saved)
        state.value = RemoteScreenState.Closed(null)
        assertEquals(listOf(pin), saved)
    }

    @Test fun failedAuthenticationOrCertificateMismatchNeverPins() = runTest {
        for ((state, actual) in listOf(
            RemoteScreenState.Closed(SecurityException("mismatch")) to "cd".repeat(32),
            RemoteScreenState.Closed(IllegalStateException("authentication failed")) to pin,
            RemoteScreenState.Closed(null) to pin,
            connected to "cd".repeat(32), connected to null,
        )) {
            persistVerifiedRdpCertificate(MutableStateFlow(state), MutableStateFlow(actual), pin) {
                fail("Unverified or failed session persisted a certificate")
            }
        }
    }

    @Test fun cancelledConnectionNeverPins() = runTest {
        val state = MutableStateFlow<RemoteScreenState>(RemoteScreenState.Connecting)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            persistVerifiedRdpCertificate(state, MutableStateFlow(pin), pin) { fail("Cancelled connection pinned") }
        }
        observer.cancel()
        observer.join()
        state.value = connected
    }
}
