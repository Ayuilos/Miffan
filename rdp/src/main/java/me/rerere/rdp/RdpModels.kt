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
