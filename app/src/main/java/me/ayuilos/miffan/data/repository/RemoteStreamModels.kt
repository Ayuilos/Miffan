package me.ayuilos.miffan.data.repository

import java.io.Closeable
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.stream.StreamFailureReason
import me.rerere.stream.StreamConfig

@Serializable
data class RemoteStreamStatus(
    val installed: Boolean, val version: String?, val running: Boolean,
    val encryptionEnforced: Boolean, val paired: Boolean, val activeStream: Boolean,
    val candidates: List<String>,
    val displayAsleep: Boolean? = null,
    val screenRecording: Boolean? = null,
    val accessibility: Boolean? = null,
    val permissionsFromLog: Boolean = false,
)

enum class RemoteSunshinePermission { SCREEN_RECORDING, ACCESSIBILITY }

interface RemoteStreamPairing : Closeable {
    val pin: String
    suspend fun await(): String
}

sealed interface RemoteStreamDirectPairing {
    /** Sunshine now trusts this phone; [certificateSha256] is the pinned server certificate. */
    data class Paired(val certificateSha256: String) : RemoteStreamDirectPairing
    /** Pairing restarts Sunshine and would interrupt someone streaming from this computer. */
    data object ActiveStream : RemoteStreamDirectPairing
    /** Offer the existing manual PIN pairing flow. */
    data class ManualPinNeeded(val reason: RemoteStreamManualPinReason) : RemoteStreamDirectPairing
}
enum class RemoteStreamManualPinReason { CERTIFICATE_UNAVAILABLE, STATE_UNRECOGNIZED, NO_PERMISSION, SUNSHINE_DID_NOT_STOP, NOT_CONFIRMED }

internal fun streamDirectPairingPreflight(probe: SunshineProbe, interruptActiveStream: Boolean): RemoteStreamDirectPairing? {
    check(probe.installed && probe.running) { "Sunshine is not running" }
    if (probe.certificateSha256 == null)
        return RemoteStreamDirectPairing.ManualPinNeeded(RemoteStreamManualPinReason.CERTIFICATE_UNAVAILABLE)
    if (probe.activeStream && !interruptActiveStream) return RemoteStreamDirectPairing.ActiveStream
    return null
}

data class RemoteStreamFallback(val reason: RemoteStreamFallbackReason, val detail: String? = null)
enum class RemoteStreamFallbackReason {
    SUNSHINE_MISSING, SUNSHINE_NOT_RUNNING, NOT_PAIRED, ENCRYPTION_NOT_ENFORCED,
    UDP_UNREACHABLE, HOST_REJECTED, DECODER_UNSUPPORTED, LOCAL_NETWORK_PERMISSION, MAC_PERMISSIONS, DISPLAY_ASLEEP, SCREEN_LOCKED, OTHER,
}
class RemoteStreamCertificateChangedException(val expectedSha256: String, val actualSha256: String?) :
    RemoteScreenUnavailableException(RemoteScreenProblem.STREAM_CERTIFICATE_CHANGED, "Sunshine certificate fingerprint changed")

@Serializable
internal data class SunshineProbe(
    val installed: Boolean, val version: String? = null, val running: Boolean,
    @SerialName("lan_encryption_mode") val lanEncryptionMode: Int? = null,
    @SerialName("wan_encryption_mode") val wanEncryptionMode: Int? = null,
    @SerialName("certificate_sha256") val certificateSha256: String? = null,
    @SerialName("active_stream") val activeStream: Boolean,
    val candidates: List<String> = emptyList(),
    @SerialName("display_asleep") val displayAsleep: Boolean? = null,
    /** macOS only; a stream's keys cannot type the lock screen password. */
    @SerialName("screen_locked") val screenLocked: Boolean? = null,
    val permissions: SunshinePermissions? = null,
    @SerialName("permissions_from_log") val permissionsFromLog: Boolean? = null,
) {
    val encryptionEnforced get() = lanEncryptionMode == 2 && wanEncryptionMode == 2
}
@Serializable
internal data class SunshinePermissions(
    @SerialName("screen_recording") val screenRecording: Boolean? = null,
    val accessibility: Boolean? = null,
)
@Serializable
internal data class SunshineCommand(val success: Boolean, val detail: String,
    @SerialName("display_asleep") val displayAsleep: Boolean? = null)
private val streamJson = Json { ignoreUnknownKeys = true }
internal fun parseSunshineProbe(output: String): SunshineProbe =
    streamJson.decodeFromString<SunshineProbe>(output.trim().lineSequence().last()).let {
        it.copy(certificateSha256 = it.certificateSha256?.let(::normalizeRdpFingerprint),
            candidates = it.candidates.mapNotNull(::numericStreamAddress).distinct())
    }
internal fun parseSunshineCommand(output: String): SunshineCommand =
    streamJson.decodeFromString(output.trim().lineSequence().last())

internal fun streamPreflightFallback(probe: SunshineProbe, paired: Boolean? = null): RemoteStreamFallbackReason? = when {
    !probe.installed -> RemoteStreamFallbackReason.SUNSHINE_MISSING
    probe.permissions?.let { it.screenRecording == false || it.accessibility == false } == true -> RemoteStreamFallbackReason.MAC_PERMISSIONS
    !probe.running -> RemoteStreamFallbackReason.SUNSHINE_NOT_RUNNING
    probe.displayAsleep == true -> RemoteStreamFallbackReason.DISPLAY_ASLEEP
    probe.screenLocked == true -> RemoteStreamFallbackReason.SCREEN_LOCKED
    !probe.encryptionEnforced -> RemoteStreamFallbackReason.ENCRYPTION_NOT_ENFORCED
    paired == false -> RemoteStreamFallbackReason.NOT_PAIRED
    else -> null
}

/** Unknown permission/display state never becomes a denial. */
internal fun streamDiagnosticFallback(probe: SunshineProbe, original: RemoteStreamFallback): RemoteStreamFallback = when {
    original.reason !in setOf(RemoteStreamFallbackReason.HOST_REJECTED, RemoteStreamFallbackReason.UDP_UNREACHABLE,
        RemoteStreamFallbackReason.OTHER) -> original
    probe.permissions?.let { it.screenRecording == false || it.accessibility == false } == true ->
        RemoteStreamFallback(RemoteStreamFallbackReason.MAC_PERMISSIONS)
    probe.displayAsleep == true -> RemoteStreamFallback(RemoteStreamFallbackReason.DISPLAY_ASLEEP)
    else -> original
}

/** Wake only an explicitly sleeping, running Mac before starting a stream. */
internal suspend fun prepareStreamProbe(platform: RemoteScreenPlatform, probe: SunshineProbe,
    wake: suspend () -> SunshineCommand): SunshineProbe =
    if (platform == RemoteScreenPlatform.MACOS && probe.installed && probe.running && probe.displayAsleep == true)
        probe.copy(displayAsleep = wake().displayAsleep ?: true) else probe
internal fun streamFailureFallback(reason: StreamFailureReason): RemoteStreamFallbackReason = when (reason) {
    StreamFailureReason.ENCRYPTION_REQUIRED -> RemoteStreamFallbackReason.ENCRYPTION_NOT_ENFORCED
    StreamFailureReason.HOST_REJECTED -> RemoteStreamFallbackReason.HOST_REJECTED
    StreamFailureReason.DECODER -> RemoteStreamFallbackReason.DECODER_UNSUPPORTED
    StreamFailureReason.UDP_UNREACHABLE -> RemoteStreamFallbackReason.UDP_UNREACHABLE
    else -> RemoteStreamFallbackReason.OTHER
}

internal fun verifyStreamCertificate(stored: String?, actual: String?) {
    if (stored != null && actual != null && stored != actual)
        throw RemoteStreamCertificateChangedException(stored, actual)
}

/** An earlier matching SSH attestation is not the actual certificate seen by a later TLS failure. */
internal fun streamCertificateFailure(expected: String?, attested: String?): RemoteStreamCertificateChangedException =
    RemoteStreamCertificateChangedException(expected.orEmpty(), attested?.takeIf { it != expected })

/** User-selected picture quality; RDP remains server-paced. [CUSTOM] streams at [RemoteStreamCustom]. */
enum class RemoteScreenQuality { SAVER, BALANCED, BEST, CUSTOM }

/** A user-picked 16:9 stream size and frame rate; the bitrate follows from them. */
data class RemoteStreamCustom(val height: Int = 1080, val fps: Int = 30) {
    init { require(height in HEIGHTS && fps in FRAME_RATES) }
    companion object {
        val HEIGHTS = listOf(720, 1080, 1440, 2160)
        val FRAME_RATES = listOf(30, 60, 90, 120)
        /** Falls back to the default for values from an older or corrupted preference. */
        fun of(height: Int, fps: Int) = if (height in HEIGHTS && fps in FRAME_RATES) RemoteStreamCustom(height, fps) else RemoteStreamCustom()
    }
}

data class RemoteStreamRequest(
    val quality: RemoteScreenQuality = RemoteScreenQuality.BALANCED,
    val skip: Boolean = false,
    val custom: RemoteStreamCustom = RemoteStreamCustom(),
)

internal fun RemoteStreamRequest.requested(hostEnabled: Boolean): Boolean = hostEnabled && !skip

/** Guard the entire Sunshine attempt, including its probe, when this connection opts out. */
internal suspend fun attemptRemoteStream(requested: Boolean, attempt: suspend () -> StreamOpenResult): StreamOpenResult =
    if (requested) attempt() else StreamOpenResult()

internal fun RemoteScreenQuality.streamConfig(hostWidth: Int? = null, hostHeight: Int? = null,
    custom: RemoteStreamCustom = RemoteStreamCustom()): StreamConfig {
    val nominal = when (this) {
        RemoteScreenQuality.SAVER -> StreamConfig(1280, 720, 30, 4_000)
        RemoteScreenQuality.BALANCED -> StreamConfig(1920, 1080, 60, 10_000)
        RemoteScreenQuality.BEST -> StreamConfig(2560, 1440, 60, 20_000)
        RemoteScreenQuality.CUSTOM -> StreamConfig(custom.height * 16 / 9, custom.height, custom.fps, customBitrate(custom.height, custom.fps))
    }
    if (this == RemoteScreenQuality.SAVER || this == RemoteScreenQuality.BALANCED || hostWidth == null || hostHeight == null ||
        hostWidth !in 16..8192 || hostHeight !in 9..8192) return nominal
    val scale = minOf(1.0, hostWidth.toDouble() / nominal.width, hostHeight.toDouble() / nominal.height)
    // Preserve 16:9 exactly, including displays smaller than the usual 720p tier.
    val units = (nominal.height / 9 * scale).toInt().coerceAtLeast(1)
    val capped = nominal.copy(width = units * 16, height = units * 9)
    // A custom size larger than the computer's screen would only spend bandwidth on upscaling.
    return if (this == RemoteScreenQuality.CUSTOM) capped.copy(bitrateKbps = customBitrate(capped.height, custom.fps)) else capped
}

/** Matches the presets at 720p30, 1080p60 and 1440p60; sizes between tiers use the next tier up. */
private fun customBitrate(height: Int, fps: Int): Int {
    val at60 = when {
        height <= 720 -> 6_000
        height <= 1080 -> 10_000
        height <= 1440 -> 20_000
        else -> 40_000
    }
    // Higher frame rates need fewer extra bits per frame, as each frame differs less from the last.
    val factor = when {
        fps <= 30 -> 2.0 / 3
        fps <= 60 -> 1.0
        fps <= 90 -> 1.4
        else -> 1.75
    }
    return (at60 * factor / 500).roundToInt() * 500
}
