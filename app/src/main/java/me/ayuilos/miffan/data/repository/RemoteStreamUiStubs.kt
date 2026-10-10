package me.ayuilos.miffan.data.repository

import android.view.Surface
import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow
import me.rerere.stream.StreamStats

// TEMPORARY (claude/p6c-ui): the P6C_SPEC contract, stubbed so the UI compiles before Codex's data
// layer lands. Delete this file when merging codex/p6c-data; the real members replace these.

data class RemoteVideoSize(val width: Int, val height: Int)

interface RemoteSurfaceTarget {
    val videoSize: StateFlow<RemoteVideoSize?>
    fun setSurface(surface: Surface?)
}

val RemoteDesktopSession.surface: RemoteSurfaceTarget? get() = null
val RemoteDesktopSession.streamStats: StateFlow<StreamStats>? get() = null

data class RemoteStreamFallback(val reason: RemoteStreamFallbackReason, val detail: String? = null)

enum class RemoteStreamFallbackReason {
    SUNSHINE_MISSING, SUNSHINE_NOT_RUNNING, NOT_PAIRED, ENCRYPTION_NOT_ENFORCED,
    UDP_UNREACHABLE, HOST_REJECTED, DECODER_UNSUPPORTED, LOCAL_NETWORK_PERMISSION, OTHER,
}

val RemoteScreenConnection.streamFallback: RemoteStreamFallback? get() = null

class RemoteStreamCertificateChangedException(val expectedSha256: String, val actualSha256: String?) :
    RemoteScreenUnavailableException(RemoteScreenProblem.RDP_CERTIFICATE_CHANGED, "Sunshine certificate fingerprint changed")

data class RemoteStreamStatus(
    val installed: Boolean, val version: String?, val running: Boolean,
    val encryptionEnforced: Boolean, val paired: Boolean, val activeStream: Boolean,
    val candidates: List<String>,
)

interface RemoteStreamPairing : Closeable {
    val pin: String
    suspend fun await(): String
}

val RemoteHostScreenConfig.streamEnabled: Boolean get() = false

object RemoteStreamPermissions { val localNetwork: String? = null }

suspend fun RemoteScreenRepository.streamStatus(hostId: String, expectedRevision: String): RemoteStreamStatus = TODO()
suspend fun RemoteScreenRepository.startStreamPairing(hostId: String, expectedRevision: String): RemoteStreamPairing = TODO()
suspend fun RemoteScreenRepository.enforceStreamEncryption(hostId: String, expectedRevision: String): RemoteCommandOutcome = TODO()
suspend fun RemoteScreenRepository.pinStreamCertificate(hostId: String, sha256: String): Boolean = TODO()
suspend fun RemoteScreenRepository.setStreamEnabled(hostId: String, enabled: Boolean): Boolean = TODO()
