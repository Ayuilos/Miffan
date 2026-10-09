package me.ayuilos.miffan.data.repository

import kotlinx.serialization.json.Json
import me.rerere.rdp.RdpSecurity
import me.ayuilos.miffan.data.db.entity.RemoteScreenEndpoint
import me.ayuilos.miffan.data.db.entity.RemoteScreenProtocol

private val helperJson = Json { ignoreUnknownKeys = true }
internal fun parseHelperProbe(output: String): RemoteMachineProbe =
    helperJson.decodeFromString(output.trim().lineSequence().last())

internal fun selectDesktopProtocol(
    selection: RemoteScreenProtocol, probe: RemoteMachineProbe,
    endpoint: RemoteScreenEndpoint = RemoteScreenEndpoint.Helper,
): RemoteDesktopProtocol = when (selection) {
    RemoteScreenProtocol.RDP -> RemoteDesktopProtocol.RDP
    RemoteScreenProtocol.VNC -> RemoteDesktopProtocol.VNC
    RemoteScreenProtocol.AUTO -> if (endpoint == RemoteScreenEndpoint.Helper && probe.os == "linux" &&
        probe.session.desktop.orEmpty().lowercase().let { "gnome" in it || "kde" in it || "plasma" in it })
        RemoteDesktopProtocol.RDP else RemoteDesktopProtocol.VNC
}

internal fun parseRdpStart(output: String): RemoteMachineProbe.Rdp {
    val status = runCatching { helperJson.decodeFromString<RemoteMachineProbe.Rdp>(output.trim().lineSequence().last()) }
        .getOrElse { throw RemoteScreenUnavailableException(RemoteScreenProblem.RDP_START_FAILED) }
    val problem = when (status.error) {
        null -> null
        "no_graphical_session" -> RemoteScreenProblem.NO_GRAPHICAL_SESSION
        "no_rdp_server" -> RemoteScreenProblem.NO_RDP_SERVER
        "rdp_already_configured" -> RemoteScreenProblem.RDP_ALREADY_CONFIGURED
        "keyring_locked" -> RemoteScreenProblem.RDP_KEYRING_LOCKED
        "credential_setup_unavailable" -> RemoteScreenProblem.RDP_CREDENTIAL_SETUP_UNAVAILABLE
        else -> RemoteScreenProblem.RDP_START_FAILED
    }
    if (problem != null) throw RemoteScreenUnavailableException(problem, desktop = status.desktop)
    if (status.server !in setOf("gnome-remote-desktop", "krdp") || status.port !in 1024..65535 || status.username.isNullOrBlank() || status.mode !in setOf("headless", "user"))
        throw RemoteScreenUnavailableException(RemoteScreenProblem.RDP_START_FAILED)
    return status.copy(certificateSha256 = status.certificateSha256?.let {
        runCatching { normalizeRdpFingerprint(it) }.getOrElse {
            throw RemoteScreenUnavailableException(RemoteScreenProblem.RDP_START_FAILED)
        }
    })
}

internal fun normalizeRdpFingerprint(value: String): String = value.replace(":", "").lowercase().also {
    require(it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' }) { "Invalid RDP SHA-256 fingerprint" }
}

/** KRDP 6.7 explicitly disables NLA for both configured users and PAM; no retry downgrade. */
internal fun rdpSecurityForServer(server: String?) =
    if (server == "krdp") RdpSecurity.TLS else RdpSecurity.NLA
