package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Resources
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.repository.RemoteMachineProbe
import me.ayuilos.miffan.data.repository.RemoteScreenProblem
import me.ayuilos.miffan.data.repository.RemoteScreenUnavailableException
import me.ayuilos.miffan.data.repository.RemoteStreamCertificateChangedException
import me.ayuilos.miffan.data.repository.RemoteStreamFallbackReason
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Tick01
import com.jcraft.jsch.JSchException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal fun remoteScreenSetupError(resources: Resources, error: Throwable): String =
    if (error is RemoteStreamCertificateChangedException) resources.getString(R.string.workspace_screen_stream_certificate_changed)
    else when ((error as? RemoteScreenUnavailableException)?.problem) {
        RemoteScreenProblem.NO_WORKSPACE -> resources.getString(R.string.workspace_screen_no_workspace)
        RemoteScreenProblem.NO_GRAPHICAL_SESSION -> resources.getString(R.string.workspace_screen_no_session)
        RemoteScreenProblem.NOT_ENABLED, RemoteScreenProblem.BAD_ENDPOINT, RemoteScreenProblem.PASSWORD_MISSING ->
            resources.getString(R.string.workspace_screen_config_incomplete)
        RemoteScreenProblem.VNC_START_FAILED, RemoteScreenProblem.RDP_START_FAILED -> resources.getString(R.string.workspace_screen_start_failed)
        RemoteScreenProblem.NO_RDP_SERVER -> resources.getString(R.string.workspace_screen_rdp_missing)
        RemoteScreenProblem.RDP_ALREADY_CONFIGURED -> resources.getString(R.string.workspace_screen_rdp_already_configured)
        RemoteScreenProblem.RDP_KEYRING_LOCKED -> resources.getString(R.string.workspace_screen_rdp_keyring_locked)
        RemoteScreenProblem.RDP_CREDENTIAL_SETUP_UNAVAILABLE -> resources.getString(R.string.workspace_screen_rdp_credentials_unavailable)
        RemoteScreenProblem.RDP_CERTIFICATE_CHANGED -> resources.getString(R.string.workspace_screen_rdp_certificate_changed)
        else -> remoteSshError(resources, error) ?: error.localizedMessage ?: resources.getString(R.string.workspace_screen_connection_failed)
    }

/** Why high-performance mode was not used, as the clause the fallback message wraps. */
internal fun streamFallbackReason(resources: Resources, reason: RemoteStreamFallbackReason): String = resources.getString(when (reason) {
    RemoteStreamFallbackReason.SUNSHINE_MISSING -> R.string.workspace_screen_stream_reason_missing
    RemoteStreamFallbackReason.SUNSHINE_NOT_RUNNING -> R.string.workspace_screen_stream_reason_not_running
    RemoteStreamFallbackReason.NOT_PAIRED -> R.string.workspace_screen_stream_reason_not_paired
    RemoteStreamFallbackReason.ENCRYPTION_NOT_ENFORCED -> R.string.workspace_screen_stream_reason_encryption
    RemoteStreamFallbackReason.UDP_UNREACHABLE -> R.string.workspace_screen_stream_reason_udp
    RemoteStreamFallbackReason.HOST_REJECTED -> R.string.workspace_screen_stream_reason_rejected
    RemoteStreamFallbackReason.DECODER_UNSUPPORTED -> R.string.workspace_screen_stream_reason_decoder
    RemoteStreamFallbackReason.LOCAL_NETWORK_PERMISSION -> R.string.workspace_screen_stream_reason_local_network
    RemoteStreamFallbackReason.MAC_PERMISSIONS -> R.string.workspace_screen_stream_reason_mac_permissions
    RemoteStreamFallbackReason.DISPLAY_ASLEEP -> R.string.workspace_screen_stream_reason_display_asleep
    RemoteStreamFallbackReason.OTHER -> R.string.workspace_screen_stream_reason_other
})

/**
 * SSH works but nothing accepts the forwarded screen connection, typically a fixed VNC port left
 * over from another desktop. Automatic connection lets the helper start the right service.
 */
internal fun isClosedScreenPort(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { it is JSchException && it.message?.contains("channel is not opened", ignoreCase = true) == true }

/** SSH failures in words the user can act on, instead of the transport's raw message. */
internal fun remoteSshError(resources: Resources, error: Throwable): String? {
    val chain = generateSequence(error) { it.cause }.toList()
    val ssh = chain.firstOrNull { it is JSchException }?.message.orEmpty()
    return when {
        ssh.contains("auth", ignoreCase = true) -> resources.getString(R.string.workspace_screen_ssh_auth)
        isClosedScreenPort(error) -> resources.getString(R.string.workspace_screen_port_closed)
        chain.any { it is SocketTimeoutException } || ssh.contains("timeout", ignoreCase = true) ->
            resources.getString(R.string.workspace_screen_ssh_timeout)
        chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } ||
            ssh.contains("connection refused", ignoreCase = true) -> resources.getString(R.string.workspace_screen_ssh_unreachable)
        else -> null
    }
}

/** Shared by the environment check and the screen failure panel so commands cannot drift. */
@Composable
internal fun RemoteScreenVncInstallGuidance(sessionType: String?, desktop: String? = null) {
    val server = when (sessionType) {
        "wayland" -> "wayvnc"
        "x11" -> "x11vnc"
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (server == null) {
            Text(stringResource(R.string.workspace_screen_session_unsupported))
        } else {
            Text(stringResource(R.string.workspace_screen_install_vnc, server))
            RemoteScreenCopyCommand("Arch Linux", "sudo pacman -S $server")
            RemoteScreenCopyCommand("Debian / Ubuntu", "sudo apt install $server")
        }
    }
}

@Composable
internal fun RemoteScreenCopyCommand(label: String, command: String) {
    val context = LocalContext.current
    var copied by remember(command) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(command, Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false)
                }
                IconButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label.ifBlank { "command" }, command))
                    copied = true
                }) {
                    Icon(if (copied) HugeIcons.Tick01 else HugeIcons.Copy01,
                        contentDescription = stringResource(if (copied) R.string.workspace_screen_command_copied else R.string.workspace_screen_copy_command),
                        modifier = Modifier.size(18.dp),
                        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun RemoteScreenLog(text: String, initiallyExpanded: Boolean = false) {
    var expanded by remember(text) { mutableStateOf(initiallyExpanded) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.workspace_screen_hide_output else R.string.workspace_screen_show_output))
        }
        if (expanded) {
            SelectionContainer {
                Text(text, Modifier.fillMaxWidth().heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()).padding(4.dp),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** GNOME and KDE sessions are shown over RDP (see `rdp_kind` in miffan.sh); other desktops use VNC. */
internal val RemoteMachineProbe.usesRdp: Boolean
    get() = os == "linux" && session.desktop?.let { desktop ->
        RDP_DESKTOPS.any { desktop.contains(it, ignoreCase = true) }
    } == true

/** The installed RDP server, or null when the desktop needs one installed. */
internal val RemoteMachineProbe.rdpServer: String?
    get() = rdp.server?.takeIf { it.isNotBlank() && it != "none" }

private val RDP_DESKTOPS = listOf("gnome", "kde", "plasma")

/** The desktop's own remote desktop service, by the name users see in their package manager. */
@Composable
internal fun rdpServerLabel(server: String): String = when (server) {
    "gnome-remote-desktop" -> stringResource(R.string.workspace_screen_rdp_gnome)
    "krdp" -> stringResource(R.string.workspace_screen_rdp_kde)
    else -> server
}

@Composable
internal fun RemoteScreenRdpInstallGuidance(desktop: String?) {
    val kde = desktop?.let { it.contains("kde", true) || it.contains("plasma", true) } == true
    val pkg = if (kde) "krdp" else "gnome-remote-desktop"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.workspace_screen_install_rdp, pkg))
        RemoteScreenCopyCommand("Arch Linux", "sudo pacman -S $pkg")
        RemoteScreenCopyCommand("Debian / Ubuntu", "sudo apt install $pkg")
    }
}
