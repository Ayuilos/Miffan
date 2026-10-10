package me.ayuilos.miffan.data.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.stream.StreamConfig
import me.rerere.workspace.screen.RemoteScreenState
import me.rerere.workspace.screen.RemoteScreenStats
import org.junit.Assert.*
import org.junit.Test

class RemoteStreamRequestTest {
    @Test fun qualityMapsResolutionFrameRateAndBitrate() {
        assertEquals(StreamConfig(1280, 720, 30, 4_000), RemoteScreenQuality.SAVER.streamConfig())
        assertEquals(StreamConfig(1920, 1080, 60, 10_000), RemoteStreamRequest().quality.streamConfig())
        assertEquals(StreamConfig(2560, 1440, 60, 20_000), RemoteScreenQuality.BEST.streamConfig())
    }
    @Test fun bestIsCappedProportionallyAndOnlyWhenHostGeometryIsUsable() {
        assertEquals(StreamConfig(1920, 1080, 60, 20_000), RemoteScreenQuality.BEST.streamConfig(1920, 1080))
        assertEquals(StreamConfig(1920, 1080, 60, 20_000), RemoteScreenQuality.BEST.streamConfig(2560, 1080))
        val small = RemoteScreenQuality.BEST.streamConfig(800, 600)
        assertTrue(small.width <= 800 && small.height <= 600)
        assertEquals(small.width * 9, small.height * 16)
        for (size in listOf(null to null, 1920 to null, 0 to 1080, -1 to 720, 10000 to 20000, 3840 to 2160))
            assertEquals(StreamConfig(2560, 1440, 60, 20_000), RemoteScreenQuality.BEST.streamConfig(size.first, size.second))
        assertEquals(StreamConfig(1920, 1080, 60, 10_000), RemoteScreenQuality.BALANCED.streamConfig(800, 600))
    }
    @Test fun skipAndDisabledHostNeverProbeSunshineOrProduceFallback(): Unit = runBlocking {
        var calls = 0
        for ((enabled, request) in listOf(true to RemoteStreamRequest(skip = true), false to RemoteStreamRequest(),
            false to RemoteStreamRequest(skip = true))) {
            val result = attemptRemoteStream(request.requested(enabled)) {
                calls++; StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.OTHER))
            }
            assertNull(result.fallback); assertNull(result.session); assertNull(result.address)
        }
        assertEquals(0, calls)
        val result = attemptRemoteStream(RemoteStreamRequest().requested(true)) {
            calls++; StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.SUNSHINE_MISSING))
        }
        assertEquals(1, calls)
        assertEquals(RemoteStreamFallbackReason.SUNSHINE_MISSING, result.fallback?.reason)
    }
    @Test fun connectionReportsRequestedEvenOnFallbackAndOnlyAcceptedStreamHasAddress() {
        val session = object : RemoteDesktopSession {
            override val protocol = RemoteDesktopProtocol.VNC
            override val state = MutableStateFlow<RemoteScreenState>(RemoteScreenState.Connecting)
            override val clipboard = MutableSharedFlow<String>()
            override val bytesReceived = 0L
            override val certificateSha256 = MutableStateFlow<String?>(null)
            override val rdpStats = null
            override val stats = MutableStateFlow(RemoteScreenStats())
            override var statsLogger: ((RemoteScreenStats) -> Unit)? = null
            override fun start(scope: kotlinx.coroutines.CoroutineScope) {}
            override fun setPaused(paused: Boolean) {}
            override fun setMaxFps(fps: Int) {}
            override fun pointer(x: Int, y: Int, buttons: Int) {}
            override fun key(keysym: Int, down: Boolean) {}
            override fun typeText(text: String) = false
            override fun sendClipboard(text: String) {}
            override fun close() {}
        }
        assertNull(session.audio)
        for ((enabled, request, result) in listOf(
            Triple(false, RemoteStreamRequest(), StreamOpenResult()),
            Triple(true, RemoteStreamRequest(skip = true), StreamOpenResult()),
            Triple(true, RemoteStreamRequest(), StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.UDP_UNREACHABLE))),
            Triple(true, RemoteStreamRequest(), StreamOpenResult(address = "100.64.0.5")),
        )) {
            val connection = RemoteScreenConnection("host", RemoteScreenPlatform.LINUX, "workspace",
                LeasedRemote(session) {}, result.fallback, request.requested(enabled), result.address) {}
            assertEquals(enabled && !request.skip, connection.streamRequested)
            assertEquals(result.address, connection.streamAddress)
            assertSame(result.fallback, connection.streamFallback)
            connection.close()
        }
    }
}
