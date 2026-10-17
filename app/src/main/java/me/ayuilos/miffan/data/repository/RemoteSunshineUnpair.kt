package me.ayuilos.miffan.data.repository

import android.content.Context
import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.workspace.RemoteWorkspaceSession

private val pairingFailureJson = Json { ignoreUnknownKeys = true }

/** This is the phone certificate, distinct from the saved Sunshine server certificate. */
internal fun streamClientCertificateSha256(certificatePem: String): String {
    val certificate = CertificateFactory.getInstance("X.509").generateCertificate(certificatePem.byteInputStream())
    return MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

class RemoteSunshineUnpair internal constructor(assets: AssetManager, private val certificatePem: () -> String) {
    constructor(context: Context) : this(context.assets,
        RemoteStreamIdentityStore(context).let { identities -> { identities.get().certificatePem } })
    private val helper = RemoteMiffanHelper(assets)
    @Serializable data class Result(val removed: Int, val restarted: Boolean)

    @Serializable private data class PairRequest(val name: String, val cert: String)
    @Serializable internal data class PairResult(val added: Boolean, val replaced: Int, val restarted: Boolean,
        @SerialName("client_sha256") val clientSha256: String)

    /** Null means the helper wrote this identity; the caller still has to confirm pinned HTTPS pairing. */
    internal fun pair(ssh: RemoteWorkspaceSession, clientName: String): RemoteStreamManualPinReason? {
        require(clientName.isNotBlank() && clientName.length <= 64 && clientName.none { it.isISOControl() })
        val pem = certificatePem()
        val fingerprint = streamClientCertificateSha256(pem)
        helper.ensureInstalled(ssh)
        val request = Json.encodeToString(PairRequest.serializer(), PairRequest(clientName, pem)) + "\n"
        val result = ssh.execute(screenHelperCommand(ssh.platform, "sunshine pair"), timeoutMillis = 45_000,
            stdin = ByteArrayInputStream(request.toByteArray(Charsets.UTF_8)))
        check(!result.timedOut) { "Sunshine direct pairing timed out" }
        if (result.exitCode != 0) {
            val failure = runCatching { pairingFailureJson.decodeFromString<SunshineCommand>(result.stdout.trim()) }.getOrNull()
            check(failure != null && !failure.success) { "Could not verify Sunshine direct pairing failure" }
            return when (failure.detail) {
                "Unrecognized Sunshine state format" -> RemoteStreamManualPinReason.STATE_UNRECOGNIZED
                "Could not stop Sunshine to change pairing" -> RemoteStreamManualPinReason.SUNSHINE_DID_NOT_STOP
                "Cannot read Sunshine state or configuration; check administrator rights",
                "Cannot edit Sunshine state or control its service; check administrator rights" -> RemoteStreamManualPinReason.NO_PERMISSION
                else -> error("Sunshine direct pairing failed: " + failure.detail)
            }
        }
        val status = runCatching {
            val objectValue = Json.parseToJsonElement(result.stdout.trim()) as JsonObject
            check(objectValue.keys == setOf("added", "replaced", "restarted", "client_sha256"))
            check(objectValue.all { (key, value) -> value is JsonPrimitive && value.isString == (key == "client_sha256") })
            Json.decodeFromJsonElement(PairResult.serializer(), objectValue)
        }.getOrNull()
        check(status != null && status.added && status.replaced >= 0 && status.clientSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Could not verify Sunshine direct pairing result"
        }
        check(status.clientSha256 == fingerprint) { "Sunshine direct pairing client certificate fingerprint mismatch" }
        return null
    }

    /** Removes every Sunshine record of this phone; says how many, and whether Sunshine was restarted. */
    fun unpair(ssh: RemoteWorkspaceSession): Result {
        val fingerprint = streamClientCertificateSha256(certificatePem())
        helper.ensureInstalled(ssh)
        val result = ssh.execute(screenHelperCommand(ssh.platform, "sunshine unpair") + " $fingerprint", timeoutMillis = 45_000)
        check(!result.timedOut && result.exitCode == 0) {
            "Could not remove this phone's Sunshine pairing; the computer was kept: " +
                result.stderr.trim().ifEmpty { result.stdout.trim() }
        }
        val status = runCatching { Json.decodeFromString<Result>(result.stdout.trim()) }.getOrNull()
        check(status != null && status.removed >= 0) { "Could not verify Sunshine unpairing; the computer was kept" }
        return status
    }
}
