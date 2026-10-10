package me.rerere.stream

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Negotiated RTSP Opus multistream configuration, never guessed from the packet. */
internal data class StreamOpusConfig(val rate: Int, val channels: Int, val streams: Int,
    val coupled: Int, val samples: Int, val mapping: ByteArray) {
    fun header(): ByteArray {
        require(rate in 8000..48000 && channels == 2 && samples in 1..5760)
        require(streams in 1..2 && coupled in 0..streams && streams + coupled == channels)
        require(mapping.size == channels && mapping.all { (it.toInt() and 255) < streams + coupled })
        return ByteBuffer.allocate(21 + channels).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII)); put(1); put(channels.toByte())
            putShort(0); putInt(rate); putShort(0)
            put(1); put(streams.toByte()); put(coupled.toByte()); put(mapping)
        }.array()
    }
}

internal fun audioBacklogExpired(oldestNanos: Long, nowNanos: Long): Boolean =
    nowNanos - oldestNanos > 100_000_000L
