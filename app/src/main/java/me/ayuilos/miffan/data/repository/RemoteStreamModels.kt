package me.ayuilos.miffan.data.repository

import java.io.Closeable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.stream.StreamFailureReason

@Serializable
data class RemoteStreamStatus(
    val installed: Boolean, val version: String?, val running: Boolean,
    val encryptionEnforced: Boolean, val paired: Boolean, val activeStream: Boolean,
    val candidates: List<String>,
)

interface RemoteStreamPairing : Closeable {
    val pin: String
    suspend fun await(): String
}

data class RemoteStreamFallback(val reason: RemoteStreamFallbackReason, val detail: String? = null)
enum class RemoteStreamFallbackReason {
    SUNSHINE_MISSING, SUNSHINE_NOT_RUNNING, NOT_PAIRED, ENCRYPTION_NOT_ENFORCED,
    UDP_UNREACHABLE, HOST_REJECTED, DECODER_UNSUPPORTED, LOCAL_NETWORK_PERMISSION, OTHER,
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
) {
    val encryptionEnforced get() = lanEncryptionMode == 2 && wanEncryptionMode == 2
}
@Serializable
internal data class SunshineCommand(val success: Boolean, val detail: String)
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
    !probe.running -> RemoteStreamFallbackReason.SUNSHINE_NOT_RUNNING
    !probe.encryptionEnforced -> RemoteStreamFallbackReason.ENCRYPTION_NOT_ENFORCED
    paired == false -> RemoteStreamFallbackReason.NOT_PAIRED
    else -> null
}
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
