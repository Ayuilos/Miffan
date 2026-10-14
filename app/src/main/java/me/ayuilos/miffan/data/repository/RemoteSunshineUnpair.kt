package me.ayuilos.miffan.data.repository

import android.content.Context
import android.content.res.AssetManager
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.workspace.RemoteWorkspaceSession

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
    @Serializable private data class Result(val removed: Int, val restarted: Boolean)

    fun unpair(ssh: RemoteWorkspaceSession) {
        val fingerprint = streamClientCertificateSha256(certificatePem())
        helper.ensureInstalled(ssh)
        val result = ssh.execute(screenHelperCommand(ssh.platform, "sunshine unpair") + " $fingerprint", timeoutMillis = 45_000)
        check(!result.timedOut && result.exitCode == 0) {
            "Could not remove this phone's Sunshine pairing; the computer was kept: " +
                result.stderr.trim().ifEmpty { result.stdout.trim() }
        }
        val status = runCatching { Json.decodeFromString<Result>(result.stdout.trim()) }.getOrNull()
        check(status != null && status.removed >= 0) { "Could not verify Sunshine unpairing; the computer was kept" }
    }
}
