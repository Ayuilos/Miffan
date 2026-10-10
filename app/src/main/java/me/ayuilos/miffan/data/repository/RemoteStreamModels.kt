package me.ayuilos.miffan.data.repository

import java.io.Closeable
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

data class RemoteStreamFallback(val reason: RemoteStreamFallbackReason, val detail: String? = null)
enum class RemoteStreamFallbackReason {
    SUNSHINE_MISSING, SUNSHINE_NOT_RUNNING, NOT_PAIRED, ENCRYPTION_NOT_ENFORCED,
    UDP_UNREACHABLE, HOST_REJECTED, DECODER_UNSUPPORTED, LOCAL_NETWORK_PERMISSION, MAC_PERMISSIONS, DISPLAY_ASLEEP, OTHER,
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

/** User-selected picture quality; RDP remains server-paced. */
enum class RemoteScreenQuality { SAVER, BALANCED, BEST }

data class RemoteStreamRequest(
    val quality: RemoteScreenQuality = RemoteScreenQuality.BALANCED,
    val skip: Boolean = false,
)

internal fun RemoteStreamRequest.requested(hostEnabled: Boolean): Boolean = hostEnabled && !skip

/** Guard the entire Sunshine attempt, including its probe, when this connection opts out. */
internal suspend fun attemptRemoteStream(requested: Boolean, attempt: suspend () -> StreamOpenResult): StreamOpenResult =
    if (requested) attempt() else StreamOpenResult()

internal fun RemoteScreenQuality.streamConfig(hostWidth: Int? = null, hostHeight: Int? = null): StreamConfig {
    val nominal = when (this) {
        RemoteScreenQuality.SAVER -> StreamConfig(1280, 720, 30, 4_000)
        RemoteScreenQuality.BALANCED -> StreamConfig(1920, 1080, 60, 10_000)
        RemoteScreenQuality.BEST -> StreamConfig(2560, 1440, 60, 20_000)
    }
    if (this != RemoteScreenQuality.BEST || hostWidth == null || hostHeight == null ||
        hostWidth !in 16..8192 || hostHeight !in 9..8192) return nominal
    val scale = minOf(1.0, hostWidth.toDouble() / nominal.width, hostHeight.toDouble() / nominal.height)
    // Preserve 16:9 exactly, including displays smaller than the usual 720p tier.
    val units = (160 * scale).toInt().coerceAtLeast(1)
    return nominal.copy(width = units * 16, height = units * 9)
}
