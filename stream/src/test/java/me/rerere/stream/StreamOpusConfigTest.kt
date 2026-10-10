package me.rerere.stream

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class StreamOpusConfigTest {
    @Test fun headerPreservesNegotiatedStereoMultistreamMapping() {
        val header = StreamOpusConfig(48000, 2, 1, 1, 240, byteArrayOf(0, 1)).header()
        assertEquals("OpusHead", String(header, 0, 8, Charsets.US_ASCII))
        val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply { position(8) }
        assertEquals(1, fields.get().toInt()); assertEquals(2, fields.get().toInt())
        assertEquals(0, fields.short.toInt()); assertEquals(48000, fields.int)
        assertEquals(0, fields.short.toInt()); assertEquals(1, fields.get().toInt())
        assertEquals(1, fields.get().toInt()); assertEquals(1, fields.get().toInt())
        assertEquals(0, fields.get().toInt()); assertEquals(1, fields.get().toInt())
        val alternate = StreamOpusConfig(48000, 2, 2, 0, 480, byteArrayOf(1, 0)).header()
        assertArrayEquals(byteArrayOf(2, 0, 1, 0), alternate.copyOfRange(19, 23))
    }
    @Test fun unsupportedConfigurationsAreRejectedBeforePlayback() {
        val stereo = StreamOpusConfig(48000, 2, 1, 1, 240, byteArrayOf(0, 1))
        for (bad in listOf(stereo.copy(channels = 6), stereo.copy(rate = 0), stereo.copy(samples = 0),
            stereo.copy(streams = 2), stereo.copy(mapping = byteArrayOf(0, 3))))
            assertThrows(IllegalArgumentException::class.java) { bad.header() }
    }
    @Test fun backlogExpiryUsesMonotonicTimeAndOneHundredMillisecondBudget() {
        assertFalse(audioBacklogExpired(1_000_000_000, 1_100_000_000))
        assertTrue(audioBacklogExpired(1_000_000_000, 1_100_000_001))
    }
}
