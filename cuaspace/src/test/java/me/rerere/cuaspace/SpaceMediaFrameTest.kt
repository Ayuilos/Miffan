package me.rerere.cuaspace

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceMediaFrameTest {
    private fun packet(header: String, payload: ByteArray): ByteBuffer {
        val head = header.toByteArray()
        return ByteBuffer.allocate(8 + head.size + payload.size).putInt(head.size).putInt(payload.size).put(head).put(payload).also { it.flip() }
    }

    @Test fun videoPacketsBecomeFrames() {
        val payload = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 3)
        val frame = SpaceMedia.frame(packet(
            """{"direction":"video","message":{"session_id":"m","sequence":258981846469,"geometry_epoch":1,""" +
                """"codec_epoch":2,"width_px":1280,"height_px":800,"capture_timestamp_us":99,"codec":"h264","keyframe":true}}""",
            payload))!!
        assertEquals(258981846469L, frame.sequence)
        assertEquals(2L, frame.codecEpoch)
        assertEquals(1280 to 800, frame.width to frame.height)
        assertTrue(frame.keyframe)
        assertArrayEquals(payload, frame.data)
    }

    @Test fun audioAndBrokenPacketsAreSkipped() {
        // Audio starts with its RAU2 magic.
        assertNull(SpaceMedia.frame(ByteBuffer.wrap("RAU2".toByteArray() + ByteArray(40))))
        assertNull(SpaceMedia.frame(ByteBuffer.wrap(byteArrayOf(0, 0, 0))))
        // A header length past the packet, or a payload longer than what arrived.
        assertNull(SpaceMedia.frame(ByteBuffer.allocate(16).putInt(100).putInt(0).also { it.flip() }))
        val short = packet("""{"direction":"video","message":{"codec":"h264"}}""", ByteArray(4))
        assertNull(SpaceMedia.frame(ByteBuffer.wrap(short.array(), 0, short.limit() - 2)))
        // Other codecs and other directions are not video for the H.264 decoder.
        assertNull(SpaceMedia.frame(packet("""{"direction":"video","message":{"codec":"png"}}""", ByteArray(4))))
        assertNull(SpaceMedia.frame(packet("""{"direction":"app_icon","message":{}}""", ByteArray(4))))
        assertNull(SpaceMedia.frame(packet("not json", ByteArray(4))))
    }
}
