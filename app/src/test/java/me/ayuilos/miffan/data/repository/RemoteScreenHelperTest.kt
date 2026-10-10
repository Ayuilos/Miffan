package me.ayuilos.miffan.data.repository

import me.ayuilos.miffan.data.db.entity.RemoteScreenEndpoint
import me.ayuilos.miffan.data.db.entity.RemoteScreenProtocol
import org.junit.Assert.*
import org.junit.Test

class RemoteScreenHelperTest {
    private fun probe(desktop: String, os: String = "linux") = parseHelperProbe(
        """{"helper":7,"os":"$os","arch":"aarch64","cua":{"min":"0.34.0","ok":false},"vnc":{},"session":{"present":true,"type":"wayland","desktop":"$desktop"},"rdp":{"server":"krdp","version":"6.7.5","running":false},"future":true}""")
    @Test fun automaticProtocolPreservesVncForOtherDesktopsAndManualEndpoints() {
        for (desktop in listOf("GNOME", "KDE", "KDE:Plasma")) {
            assertEquals(RemoteDesktopProtocol.RDP, selectDesktopProtocol(RemoteScreenProtocol.AUTO, probe(desktop)))
            assertEquals(RemoteDesktopProtocol.VNC, selectDesktopProtocol(RemoteScreenProtocol.VNC, probe(desktop)))
            assertEquals(RemoteDesktopProtocol.VNC, selectDesktopProtocol(RemoteScreenProtocol.AUTO, probe(desktop), RemoteScreenEndpoint.Tcp(5900)))
        }
        for (desktop in listOf("niri", "sway", "XFCE", ""))
            assertEquals(RemoteDesktopProtocol.VNC, selectDesktopProtocol(RemoteScreenProtocol.AUTO, probe(desktop)))
        assertEquals(RemoteDesktopProtocol.VNC, selectDesktopProtocol(RemoteScreenProtocol.AUTO, probe("macos", "macos")))
        assertEquals(RemoteDesktopProtocol.RDP, selectDesktopProtocol(RemoteScreenProtocol.RDP, probe("niri")))
    }
    @Test fun rdpOutputAcceptsMetadataAndRejectsUnsafeOrMalformedEndpoints() {
        val status = parseRdpStart("""{"server":"gnome-remote-desktop","port":45891,"username":"miffan-1001","mode":"headless","desktop":"GNOME","error":null,"log":null}""")
        assertEquals(45891, status.port)
        assertEquals("headless", status.mode)
        assertNull(status.certificateSha256) // Legacy helpers retain TOFU.
        for (output in listOf("not JSON", "{}", """{"port":22,"username":"user","mode":"user"}""",
            """{"port":65536,"username":"user","mode":"user"}"""))
            assertThrows(RemoteScreenUnavailableException::class.java) { parseRdpStart(output) }
    }
    @Test fun helperCertificateIsNormalizedAndMalformedAttestationFailsClosed() {
        fun output(fingerprint: String) = """{"server":"krdp","port":45891,"username":"miffan-1001","mode":"user","certificate_sha256":"$fingerprint"}"""
        assertEquals("ab".repeat(32), parseRdpStart(output("AB:".repeat(31) + "AB")).certificateSha256)
        for (bad in listOf("", "ab", "gg".repeat(32)))
            assertThrows(RemoteScreenUnavailableException::class.java) { parseRdpStart(output(bad)) }
    }
    @Test fun errorsHaveStableTypesWithoutEchoingSecretServerOutput() {
        for ((code, problem) in mapOf("rdp_already_configured" to RemoteScreenProblem.RDP_ALREADY_CONFIGURED,
            "keyring_locked" to RemoteScreenProblem.RDP_KEYRING_LOCKED,
            "credential_setup_unavailable" to RemoteScreenProblem.RDP_CREDENTIAL_SETUP_UNAVAILABLE,
            "no_rdp_server" to RemoteScreenProblem.NO_RDP_SERVER, "other" to RemoteScreenProblem.RDP_START_FAILED)) {
            val error = assertThrows(RemoteScreenUnavailableException::class.java) {
                parseRdpStart("""{"error":"$code","log":"secret-value"}""")
            }
            assertEquals(problem, error.problem)
            assertFalse(error.message.orEmpty().contains("secret-value"))
        }
    }
    @Test fun gnomeRequiresNlaAndKrdpUsesItsExplicitTlsProtocol() {
        assertEquals(me.rerere.rdp.RdpSecurity.NLA, rdpSecurityForServer("gnome-remote-desktop"))
        assertEquals(me.rerere.rdp.RdpSecurity.TLS, rdpSecurityForServer("krdp"))
    }
    @Test fun kdeCredentialFailureRejectsEndpointAndPreservesDesktopWithoutLeakingDiagnostics() {
        for (code in listOf("keyring_locked", "credential_setup_unavailable")) {
            val error = assertThrows(RemoteScreenUnavailableException::class.java) {
                parseRdpStart("""{"server":"krdp","port":45891,"username":"miffan-1001","mode":"user","desktop":"KDE:Plasma","error":"$code","log":"raw-secret-tool-output","certificate_sha256":"${"ab".repeat(32)}"}""")
            }
            assertEquals(if (code == "keyring_locked") RemoteScreenProblem.RDP_KEYRING_LOCKED
                else RemoteScreenProblem.RDP_CREDENTIAL_SETUP_UNAVAILABLE, error.problem)
            assertEquals("KDE:Plasma", error.desktop)
            assertNull(error.detail)
            assertFalse(error.message.orEmpty().contains("raw-secret-tool-output"))
        }
    }
    @Test fun fingerprintNormalizationIsStrict() {
        assertEquals("ab".repeat(32), normalizeRdpFingerprint("AB:".repeat(31) + "AB"))
        for (bad in listOf("", "ab", "gg".repeat(32), "ab".repeat(33)))
            assertThrows(IllegalArgumentException::class.java) { normalizeRdpFingerprint(bad) }
    }
}
