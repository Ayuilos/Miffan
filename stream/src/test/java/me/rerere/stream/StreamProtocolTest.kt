package me.rerere.stream

import org.junit.Assert.*
import org.junit.Test

class StreamProtocolTest {
    @Test fun urlEncodingAndForbiddenRoutes() {
        assertEquals("/pair?devicename=a+b%26c", StreamProtocol.path("pair", mapOf("devicename" to "a b&c")))
        for (route in listOf("cancel", "unpair", "../launch")) assertThrows(IllegalArgumentException::class.java) { StreamProtocol.path(route, emptyMap()) }
    }
    @Test fun serverAndAppsXml() {
        val xml = """<root status_code="200"><appversion>7.1.431.-1</appversion><PairStatus>1</PairStatus><currentgame>42</currentgame><ServerCodecModeSupport>257</ServerCodecModeSupport><App><ID>42</ID><AppTitle>A &amp; B</AppTitle></App></root>"""
        val root = StreamProtocol.xml(xml.toByteArray()); val info = StreamProtocol.server(root)
        assertTrue(info.paired); assertEquals(42, info.currentApp); assertEquals(257, info.codecModeSupport)
        assertEquals(listOf(StreamApp(42, "A & B")), StreamProtocol.apps(root))
        assertThrows(StreamException::class.java) { StreamProtocol.xml("<root status_code=\"401\"/>".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { StreamProtocol.xml("<!DOCTYPE root><root/>".toByteArray()) }
    }
    @Test fun httpFraming() {
        assertEquals("test", StreamProtocol.httpBody("HTTP/1.0 200 OK\r\nContent-Length: 4\r\n\r\ntest".toByteArray()).decodeToString())
        assertEquals("test", StreamProtocol.httpBody("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nte\r\n2\r\nst\r\n0\r\n\r\n".toByteArray()).decodeToString())
        assertThrows(IllegalArgumentException::class.java) { StreamProtocol.httpBody("HTTP/1.0 200 OK\r\nContent-Length: 5\r\n\r\ntest".toByteArray()) }
    }
    @Test fun numericUdpCandidatesOnly() {
        for (address in listOf("100.64.0.5", "192.0.2.1", "::1", "2001:db8::1")) assertTrue(StreamProtocol.numericAddress(address))
        for (address in listOf("localhost", "127.0.0.1:48010", "256.1.1.1", "1.2.3", "01.2.3.4", "host:abc")) assertFalse(StreamProtocol.numericAddress(address))
    }
    @Test fun errorAndInputMapping() {
        assertEquals(StreamFailureReason.ENCRYPTION_REQUIRED, streamFailure(3, -110).reason)
        assertEquals(StreamFailureReason.UDP_UNREACHABLE, streamFailure(8, 35).reason)
        assertEquals(StreamFailureReason.UDP_UNREACHABLE, streamFailure(0, -100).reason)
        assertEquals(StreamFailureReason.TCP_RTSP, streamFailure(4, 5).reason)
        assertEquals(StreamFailureReason.HOST_REJECTED, streamFailure(4, 403).reason)
        assertEquals(StreamFailureReason.DECODER, streamFailure(9, -200).reason)
        assertEquals(listOf(1, 2, 3, 4, 5), StreamMouseButton.entries.map { it.wire })
        assertEquals("00ff10", StreamProtocol.hex(byteArrayOf(0, -1, 16)))
        assertArrayEquals(byteArrayOf(0, -1, 16), StreamProtocol.unhex("00ff10"))
        assertThrows(IllegalArgumentException::class.java) { StreamProtocol.unhex("gg") }
    }
}
