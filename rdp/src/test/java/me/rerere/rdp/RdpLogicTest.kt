package me.rerere.rdp

import org.junit.Assert.*
import org.junit.Test
import me.rerere.workspace.screen.RfbKeys

class RdpLogicTest {
    @Test fun credentialsRedactPassword() {
        val password = "do-not-print-this"
        val value = RdpCredentials("alice", password, "domain").toString()
        assertFalse(value.contains(password))
        assertTrue(value.contains("<redacted>"))
    }
    @Test fun fingerprintsRequireCompleteSha256() {
        val pin = "ab".repeat(32)
        assertTrue(CertificateFingerprint.matches(null, pin))
        assertTrue(CertificateFingerprint.matches(pin.uppercase().chunked(2).joinToString(":"), pin))
        assertFalse(CertificateFingerprint.matches("00".repeat(32), pin))
        assertFalse(CertificateFingerprint.matches(null, "short"))
        assertFalse(CertificateFingerprint.matches("xz".repeat(32), pin))
    }
    @Test fun optionsValidateBoundsAndPins() {
        assertEquals(1920, RdpOptions().width)
        assertThrows(IllegalArgumentException::class.java) { RdpOptions(width = 0) }
        assertThrows(IllegalArgumentException::class.java) { RdpOptions(height = 8193) }
        assertThrows(IllegalArgumentException::class.java) { RdpOptions(certificateSha256 = "bad") }
    }
    @Test fun x11KeysMapToSetOneIncludingExtendedKeys() {
        assertEquals(0x1e, RdpKeys.scancode('a'.code))
        assertEquals(RdpKeys.scancode('a'.code), RdpKeys.scancode('A'.code))
        assertEquals(0x153, RdpKeys.scancode(RfbKeys.DELETE))
        assertEquals(0x14b, RdpKeys.scancode(RfbKeys.LEFT))
        assertEquals(0x1c, RdpKeys.scancode(RfbKeys.RETURN))
        assertEquals(0x15b, RdpKeys.scancode(RfbKeys.SUPER_L))
        assertEquals(0x11d, RdpKeys.scancode(0xffe4))
        assertEquals(0x3b, RdpKeys.scancode(RfbKeys.function(1)))
        assertEquals(0x58, RdpKeys.scancode(RfbKeys.function(12)))
        assertEquals(0x0b, RdpKeys.scancode('0'.code))
        assertNull(RdpKeys.scancode('中'.code))
    }
}
