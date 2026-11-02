package me.rerere.workspace.relay

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire types for relay protocol v1 (relay/PROTOCOL.md). Unknown fields are ignored everywhere. */
internal val relayJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

@Serializable
data class RelayPace(val kbps: Int = 0, val burstKb: Int = 0)

@Serializable
data class RelayMediaPolicy(val allowed: Boolean = true, val kbps: Int = 0, val burstKb: Int = 0)

@Serializable
data class RelayLimits(val sessionsPerHost: Int = 0, val idleTimeoutS: Int = 0)

@Serializable
data class RelayQuotas(
    val hostDailyMb: Int = 0,
    val hostDailyActiveMin: Int = 0,
    val hostMonthlyControlMb: Int = 0,
    val hostMonthlyMediaMb: Int = 0,
)

@Serializable
data class RelayPolicy(
    val control: RelayPace = RelayPace(),
    val media: RelayMediaPolicy = RelayMediaPolicy(),
    val limits: RelayLimits = RelayLimits(),
    val quotas: RelayQuotas = RelayQuotas(),
)

@Serializable
data class RelayUsage(
    val day: String = "",
    val month: String = "",
    val dayMb: Double = 0.0,
    val dayActiveMin: Int = 0,
    val monthControlMb: Double = 0.0,
    val monthMediaMb: Double = 0.0,
    /** "ok", "media_paused" or "closed". */
    val budget: String = "ok",
    val resetsAt: String = "",
)

@Serializable
data class RelayCapabilities(val dgram: String = "none")

@Serializable
data class RelayInfo(
    val v: Int,
    val impl: String = "",
    val version: String = "",
    /** "open" or "token". */
    val access: String = "open",
    val capabilities: RelayCapabilities = RelayCapabilities(),
    val policy: RelayPolicy = RelayPolicy(),
    val edges: List<String> = emptyList(),
)

/** What the relay sent in `ready` for one session. */
@Serializable
data class RelayReady(
    val sid: String,
    val ch: String,
    val pace: RelayPace = RelayPace(),
    val policy: RelayPolicy = RelayPolicy(),
    val usage: RelayUsage = RelayUsage(),
)

@Serializable
internal data class RelayError(val reason: String, val retryAfter: Int? = null)

@Serializable
internal data class RelayText(
    val t: String,
    val nonce: String? = null,
    val reason: String? = null,
)

@Serializable
internal data class RelayAuth(val t: String = "auth", val device: String, val sig: String)

enum class RelayChannel(val wire: String) { CONTROL("control"), MEDIA("media") }

/**
 * A relay or connector refusal. [reason] is a protocol reason code (PROTOCOL §9), or [PLATFORM]
 * when the response did not come from the relay itself (for example Cloudflare's own error page
 * after the free quota runs out): that must never be shown as "computer offline".
 */
open class RelayException(
    val reason: String,
    val retryAfterSeconds: Int? = null,
    val httpStatus: Int? = null,
    message: String = reason,
    cause: Throwable? = null,
) : IOException(message, cause) {
    companion object {
        const val PLATFORM = "platform"
        const val UNREACHABLE = "unreachable"
        const val TIMEOUT = "timeout"
    }
}

/** Identifiers from PROTOCOL §2. */
object RelayIds {
    private val HOST_ID = Regex("^[a-z2-7]{26}$")

    fun isHostId(value: String): Boolean = HOST_ID.matches(value)

    /** `base32(SHA-256(spki))[:26]`, lowercase, no padding. */
    fun deviceId(devicePublicKeySpki: ByteArray): String = id(devicePublicKeySpki)

    fun hostId(hostPublicKey: ByteArray): String = id(hostPublicKey)

    private fun id(key: ByteArray): String =
        base32Lower(MessageDigest.getInstance("SHA-256").digest(key)).substring(0, 26)

    internal fun base32Lower(data: ByteArray): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(alphabet[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(alphabet[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    internal fun b64u(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    /** The bytes the device signs in the end-to-end handshake (PROTOCOL §5.2). */
    fun sessionSignaturePayload(hostId: String, sid: String, channel: RelayChannel, nonce: String): ByteArray =
        "miffan-relay/v1 session\n$hostId\n$sid\n${channel.wire}\n$nonce".toByteArray(Charsets.UTF_8)
}

/** The phone's device key. Implementations keep the private key in Android Keystore. */
interface RelayDeviceSigner {
    /** SubjectPublicKeyInfo DER of the P-256 public key. */
    val publicKeySpki: ByteArray

    /** ECDSA P-256 / SHA-256 signature in DER form. */
    fun sign(data: ByteArray): ByteArray
}

/**
 * A session that had opened ended because the relay (or the connector through it) closed it with
 * [reason]: `idle`, `peer_closed`, `quota_exhausted`, `rate_limited`, `relay_busy` (PROTOCOL §6.4,
 * §9), any later code, or [RelayException.UNREACHABLE] when the relay stopped answering pings.
 */
class RelaySessionClosedException(reason: String, val channel: RelayChannel) :
    RelayException(reason, message = "Relay closed the ${channel.wire} session: $reason")
