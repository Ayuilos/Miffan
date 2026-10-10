package me.ayuilos.miffan.data.repository

import me.rerere.stream.StreamFailureReason
import me.rerere.stream.StreamMouseButton
import org.junit.Assert.*
import org.junit.Test

class RemoteStreamTest {
    @Test fun keyboardCoversExistingScreenKeyboardAndPhysicalKeys() {
        val known = (32..126).toList() + (0xffbe..0xffd5).toList() + listOf(0xff08, 0xff09, 0xff0d, 0xff1b,
            0xffff, 0xff50, 0xff51, 0xff52, 0xff53, 0xff54, 0xff55, 0xff56, 0xff57, 0xff63,
            0xffe1, 0xffe2, 0xffe3, 0xffe4, 0xffe7, 0xffe8, 0xffe9, 0xffea, 0xffeb, 0xffec)
        known.forEach { assertNotNull("keysym $it", StreamKeys.virtualKey(it)) }
        assertEquals(0x41, StreamKeys.virtualKey('a'.code))
        assertEquals(0x70, StreamKeys.virtualKey(0xffbe))
        assertEquals(0x87, StreamKeys.virtualKey(0xffd5))
        assertEquals(0x25, StreamKeys.virtualKey(0xff51))
        assertNull(StreamKeys.virtualKey('中'.code))
    }
    @Test fun leftAndRightModifiersAreTrackedSeparatelyAndExtendedKeysCarryE0() {
        val events = mutableListOf<Triple<Int, Boolean, Int>>()
        val input = StreamDesktopInput({ _, _, _, _ -> }, { _, _ -> }, { _, _ -> }, { vk, down, mods -> events += Triple(vk, down, mods) })
        input.key(0xffe1, true); input.key(0xffe2, true); input.key(0xffe1, false)
        input.key(0xffe4, true); input.key(0xff51, true); input.key(0xffe2, false)
        input.key('a'.code, true); input.key(0xffe4, false); input.key('a'.code, false)
        assertEquals(listOf(1, 1, 1, 19, 19, 2, 2, 16, 0), events.map { it.third })
        assertEquals(0xa3, events[3].first)
        input.key(0xdead, true)
        assertEquals(9, events.size)
    }
    @Test fun rfbButtonsAndWheelUseEdgesWithVideoCoordinates() {
        val positions = mutableListOf<List<Int>>()
        val buttons = mutableListOf<Pair<StreamMouseButton, Boolean>>()
        val wheels = mutableListOf<Pair<Int, Int>>()
        val input = StreamDesktopInput({ x, y, w, h -> positions += listOf(x, y, w, h) },
            { button, down -> buttons += button to down }, { v, h -> wheels += v to h }, { _, _, _ -> })
        val size = RemoteVideoSize(1920, 1080)
        for (mask in listOf(1, 1, 7, 0, 8, 8, 0, 16, 0, 32, 0, 64, 0)) input.pointer(10, 20, mask, size)
        assertTrue(positions.all { it == listOf(10, 20, 1920, 1080) })
        assertEquals(listOf(StreamMouseButton.LEFT to true, StreamMouseButton.MIDDLE to true, StreamMouseButton.RIGHT to true,
            StreamMouseButton.LEFT to false, StreamMouseButton.MIDDLE to false, StreamMouseButton.RIGHT to false), buttons)
        assertEquals(listOf(120 to 0, -120 to 0, 0 to -120, 0 to 120), wheels)
    }
    @Test fun pasteTemporarilyReleasesModifiersAndRestoresTheirTracking() {
        val events = mutableListOf<Triple<Int, Boolean, Int>>()
        val input = StreamDesktopInput({ _, _, _, _ -> }, { _, _ -> }, { _, _ -> }, { vk, down, mods -> events += Triple(vk, down, mods) })
        input.key(0xffe1, true); input.key(0xffea, true); events.clear()
        input.paste(); input.key('a'.code, true)
        assertEquals(Triple(0xa0, false, 0), events[0])
        assertEquals(Triple(0xa5, false, 16), events[1])
        assertEquals(listOf(Triple(0xa2, true, 2), Triple(0x56, true, 2), Triple(0x56, false, 2), Triple(0xa2, false, 0)), events.subList(2, 6))
        assertEquals(Triple(0x41, true, 5), events.last())
    }
    private val network = StreamNetworkSnapshot("wifi|vpn", listOf(StreamSubnet("192.168.31.8", 24)), true)
    @Test fun candidatesPreferCurrentNetworkCacheThenSameSubnetLanThenVpnThenResolvedSsh() {
        val reported = listOf("192.168.50.1", "192.168.31.61", "100.64.0.5", "100.64.0.5", "invalid")
        assertEquals(listOf("192.168.31.61", "100.64.0.5"), orderStreamCandidates(null, reported, listOf("100.64.0.5"), network).addresses)
        assertEquals(listOf("100.64.0.5", "192.168.31.61"), orderStreamCandidates("100.64.0.5", reported, emptyList(), network).addresses)
        assertEquals(listOf("192.168.31.61", "100.64.0.5"), orderStreamCandidates("192.168.50.1", reported, emptyList(), network).addresses)
        assertEquals(listOf("100.64.0.5", "203.0.113.8"), orderStreamCandidates(null, listOf("100.64.0.5"), listOf("203.0.113.8", "203.0.113.9"), network).addresses)
    }
    @Test fun deniedPermissionFiltersCachedLanAndSshLanWithoutFilteringTailscale() {
        val result = orderStreamCandidates("192.168.31.61", listOf("192.168.31.61", "100.64.0.5"), listOf("192.168.31.61"), network.copy(localAllowed = false))
        assertEquals(listOf("100.64.0.5"), result.addresses); assertTrue(result.permissionSkipped)
        val ipv6Lan = orderStreamCandidates(null, emptyList(), listOf("2001:db8::2"),
            network.copy(subnets = listOf(StreamSubnet("2001:db8::1", 64)), localAllowed = false))
        assertTrue(ipv6Lan.addresses.isEmpty()); assertTrue(ipv6Lan.permissionSkipped)
    }
    @Test fun subnetsAndNumericAddressesRejectNamesAndCanonicaliseDuplicates() {
        assertTrue(StreamSubnet("192.168.31.8", 24).contains("192.168.31.61"))
        assertFalse(StreamSubnet("192.168.31.8", 24).contains("192.168.32.61"))
        assertTrue(StreamSubnet("10.8.0.3", 20).contains("10.8.15.4"))
        assertFalse(StreamSubnet("10.8.0.3", 20).contains("10.8.16.4"))
        assertEquals("192.168.31.0/24", StreamSubnet("192.168.31.8", 24).network())
        assertEquals("100.64.0.5", numericStreamAddress("100.064.0.5"))
        for (bad in listOf("localhost", "remote.local", "256.0.0.1", "1.2.3", "abcd:xyz", "::1%wlan0")) assertNull(numericStreamAddress(bad))
        assertNotNull(numericStreamAddress("2001:db8::1"))
    }
    @Test fun routeKeysDistinguishHostsIdentitiesAndNetworksAndIgnoreEnumerationOrder() {
        val key = streamRouteCacheKey("a", "rev1", "wifi1")
        assertEquals(key, streamRouteCacheKey("a", "rev1", "wifi1"))
        assertNotEquals(key, streamRouteCacheKey("b", "rev1", "wifi1"))
        assertNotEquals(key, streamRouteCacheKey("a", "rev2", "wifi1"))
        assertNotEquals(key, streamRouteCacheKey("a", "rev1", "wifi2"))
        assertEquals(streamNetworkKey(listOf("wifi", "vpn")), streamNetworkKey(listOf("vpn", "wifi")))
    }
    @Test fun certificateMismatchHasItsOwnExceptionAndNeverFallsBack() {
        val expected = "ab".repeat(32)
        val actual = "cd".repeat(32)
        verifyStreamCertificate(expected, expected)
        verifyStreamCertificate(expected, null) // TLS remains responsible for final attestation.
        assertNull(streamCertificateFailure(expected, expected).actualSha256)
        assertEquals(actual, streamCertificateFailure(expected, actual).actualSha256)
        val failure = assertThrows(RemoteStreamCertificateChangedException::class.java) { verifyStreamCertificate(expected, actual) }
        assertEquals(expected, failure.expectedSha256); assertEquals(actual, failure.actualSha256)
        assertEquals(RemoteScreenProblem.STREAM_CERTIFICATE_CHANGED, failure.problem)
    }
    private fun probe() = parseSunshineProbe("""{"installed":true,"version":"2026.1008.155609-1","running":true,"lan_encryption_mode":2,"wan_encryption_mode":2,"certificate_sha256":"${"AB".repeat(32)}","active_stream":false,"candidates":["192.168.31.61","100.64.0.5","localhost","100.64.0.5"],"future":true}""")
    @Test fun helperJsonNormalizesCertificatesAndFiltersUntrustedCandidates() {
        val p = probe(); assertEquals("ab".repeat(32), p.certificateSha256)
        assertTrue(p.encryptionEnforced); assertFalse(p.activeStream)
        assertEquals(listOf("192.168.31.61", "100.64.0.5"), p.candidates)
        assertEquals("2026.1008.155609-1", p.version)
        assertTrue(parseSunshineCommand("""{"success":true,"detail":"restarted"}""").success)
        assertThrows(Exception::class.java) { parseSunshineProbe("{}") }
    }
    @Test fun preflightAndFailureReasonsRemainDistinct() {
        val p = probe()
        assertNull(streamPreflightFallback(p, true))
        assertEquals(RemoteStreamFallbackReason.SUNSHINE_MISSING, streamPreflightFallback(p.copy(installed = false)))
        assertEquals(RemoteStreamFallbackReason.SUNSHINE_NOT_RUNNING, streamPreflightFallback(p.copy(running = false)))
        assertEquals(RemoteStreamFallbackReason.ENCRYPTION_NOT_ENFORCED, streamPreflightFallback(p.copy(wanEncryptionMode = 1)))
        assertEquals(RemoteStreamFallbackReason.NOT_PAIRED, streamPreflightFallback(p, false))
        assertEquals(RemoteStreamFallbackReason.DECODER_UNSUPPORTED, streamFailureFallback(StreamFailureReason.DECODER))
        assertEquals(RemoteStreamFallbackReason.HOST_REJECTED, streamFailureFallback(StreamFailureReason.HOST_REJECTED))
        assertEquals(RemoteStreamFallbackReason.UDP_UNREACHABLE, streamFailureFallback(StreamFailureReason.UDP_UNREACHABLE))
    }
}
