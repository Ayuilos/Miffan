package me.rerere.rdp

import java.security.MessageDigest

class RdpCredentials(val username: String, val password: String, val domain: String? = null) {
    override fun toString() = "RdpCredentials(username=$username, password=<redacted>, domain=$domain)"
}

enum class RdpSecurity { AUTO, TLS, NLA }

data class RdpOptions(
    val width: Int = 1920,
    val height: Int = 1080,
    val certificateSha256: String? = null,
    /** AUTO offers NLA and TLS; use TLS for krdp installations with no NLA SAM account. */
    val security: RdpSecurity = RdpSecurity.AUTO,
    /** Diagnostic A/B switch; production connections should keep low latency enabled. */
    val lowLatency: Boolean = true,
    /** Diagnostic switches for measuring each latency change independently. */
    val eventDriven: Boolean = true,
    val ackBeforeSink: Boolean = true,
    val directBitmap: Boolean = true,
    val liveNetworkStats: Boolean = true,
) {
    init {
        require(width in 320..8192 && height in 240..8192)
        certificateSha256?.let { CertificateFingerprint.normalize(it) }
    }
}

data class RdpStats(
    val frames: Long = 0,
    val framesPerSecond: Double = 0.0,
    val bytesReceived: Long = 0,
    val bytesSent: Long = 0,
    val encoding: String = "Unknown",
    val decoder: String? = null,
    /** null until a decoder is used or when Android cannot classify the codec. */
    val h264HardwareAccelerated: Boolean? = null,
    val security: String = "Unknown",
    val width: Int = 0,
    val height: Int = 0,
    val decodedFrames: Long = 0,
    val decodeFramesPerSecond: Double = 0.0,
    val decodeMs: Double = 0.0,
    val decodeMeanMs: Double = 0.0,
    val decodeMaxMs: Double = 0.0,
    val inFlightFrames: Int = 0,
    val peakInFlightFrames: Int = 0,
    /** Age of the queued access unit when it has not produced output yet. */
    val pendingDecodeMs: Double = 0.0,
    val outputWaitTimeouts: Long = 0,
    val yuvToRgbMs: Double = 0.0,
    val yuvToRgbMeanMs: Double = 0.0,
    val yuvToRgbMaxMs: Double = 0.0,
    /** Includes JNI allocation/copy, onPixels and onFrameComplete; excludes later GPU upload. */
    val sinkMs: Double = 0.0,
    val sinkMeanMs: Double = 0.0,
    val sinkMaxMs: Double = 0.0,
    /** First surface command in a frame to successful FrameAcknowledge transport send (including channel queue). */
    val surfaceToAckMs: Double = 0.0,
    val surfaceToAckMeanMs: Double = 0.0,
    val surfaceToAckMaxMs: Double = 0.0,
    val ackQueueMs: Double = 0.0,
    val ackQueueMeanMs: Double = 0.0,
    val ackQueueMaxMs: Double = 0.0,
    val lowLatencySupported: Boolean? = null,
    /** Requested keys accepted by configure; acceptance does not prove driver behavior. */
    val decoderConfiguration: String = "Unconfigured",
    val neonYuv: Boolean? = null,
    /** First SurfaceCommand to EndFrame callback entry; includes earlier command processing. */
    val frameDataMs: Double = 0.0,
    val frameDataMeanMs: Double = 0.0,
    val frameDataMaxMs: Double = 0.0,
    val surfaceWorkMs: Double = 0.0,
    val surfaceWorkMeanMs: Double = 0.0,
    val surfaceWorkMaxMs: Double = 0.0,
    val loopWaitMs: Double = 0.0,
    val loopWaitMeanMs: Double = 0.0,
    val loopWaitMaxMs: Double = 0.0,
    /** EndFrame GDI work, excluding synchronous sink copying. */
    val composeMs: Double = 0.0,
    val composeMeanMs: Double = 0.0,
    val composeMaxMs: Double = 0.0,
    val inputWaitMs: Double = 0.0,
    val inputWaitMeanMs: Double = 0.0,
    val inputWaitMaxMs: Double = 0.0,
    val outputWaitMs: Double = 0.0,
    val outputWaitMeanMs: Double = 0.0,
    val outputWaitMaxMs: Double = 0.0,
    val outputAccessMs: Double = 0.0,
    val outputAccessMeanMs: Double = 0.0,
    val outputAccessMaxMs: Double = 0.0,
    val outputReleaseMs: Double = 0.0,
    val outputReleaseMeanMs: Double = 0.0,
    val outputReleaseMaxMs: Double = 0.0,
    val inputQueueMs: Double = 0.0,
    val inputQueueMeanMs: Double = 0.0,
    val inputQueueMaxMs: Double = 0.0,
    val transportReadCalls: Long = 0,
    val transportReadBytes: Long = 0,
    val frameReadCalls: Long = 0,
    val directBitmapFrames: Long = 0,
    /** Actual ACK transport sends completed while this frame still had a deferred sink. */
    val ackBeforeSinkFrames: Long = 0,
)

internal object CertificateFingerprint {
    fun normalize(value: String): String = value.replace(":", "").lowercase().also {
        require(it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' }) { "Invalid SHA-256 fingerprint" }
    }
    fun matches(expected: String?, actual: String): Boolean {
        val normalized = runCatching { normalize(actual) }.getOrNull() ?: return false
        if (expected == null) return true
        val pin = runCatching { normalize(expected) }.getOrNull() ?: return false
        return MessageDigest.isEqual(pin.toByteArray(Charsets.US_ASCII), normalized.toByteArray(Charsets.US_ASCII))
    }
}
