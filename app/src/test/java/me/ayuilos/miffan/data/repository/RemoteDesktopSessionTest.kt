package me.ayuilos.miffan.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import me.rerere.rdp.*
import me.rerere.workspace.screen.*
import org.junit.Assert.*
import org.junit.Test

class RemoteDesktopSessionTest {
    private val sink = object : RemoteScreenFrameSink {
        override fun onSize(width: Int, height: Int, scale: Int) {}
        override fun onPixels(rect: RfbRect, pixels: IntArray) {}
        override fun onFrameComplete() {}
    }
    @Test fun vncRetainsExactStatsClipboardAndLifetime() {
        var closed = false
        val delegate = RemoteScreenSession(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(),
            Closeable { closed = true }, null, null, RemoteScreenOptions(), sink)
        val adapter = VncDesktopSession(delegate)
        assertEquals(RemoteDesktopProtocol.VNC, adapter.protocol)
        assertSame(delegate.state, adapter.state)
        assertSame(delegate.stats, adapter.stats)
        assertSame(delegate.clipboard, adapter.clipboard)
        val logger: (RemoteScreenStats) -> Unit = {}
        adapter.statsLogger = logger
        assertSame(logger, delegate.statsLogger)
        assertNull(adapter.rdpStats)
        adapter.close()
        assertTrue(closed)
        assertTrue(adapter.state.value is RemoteScreenState.Closed)
    }
    @Test fun rdpDelegatesAndExposesNativeStatsWithoutPinning() {
        var closed = false
        val delegate = RdpSession(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(),
            Closeable { closed = true }, RdpCredentials("user", "password"), RdpOptions(), sink)
        val adapter = RdpDesktopSession(delegate, null)
        assertEquals(RemoteDesktopProtocol.RDP, adapter.protocol)
        assertSame(delegate.stats, adapter.rdpStats)
        assertSame(delegate.certificateSha256, adapter.certificateSha256)
        assertSame(delegate.clipboard, adapter.clipboard)
        assertFalse(adapter.typeText("中文"))
        assertTrue(adapter.typeText("ASCII\t\n"))
        adapter.close()
        assertTrue(closed)
        assertNull((adapter.state.value as RemoteScreenState.Closed).error)
    }
}
