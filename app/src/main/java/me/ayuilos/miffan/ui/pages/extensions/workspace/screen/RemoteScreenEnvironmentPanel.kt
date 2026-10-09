package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.repository.RemoteMachineProbe
import me.ayuilos.miffan.ui.pages.extensions.workspace.WorkspaceVM
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Unavailable

private data class CuaInstallRequest(val upgradePath: String?, val cua: RemoteMachineProbe.Cua)
private enum class EnvironmentStatus { OK, ACTION_NEEDED, UNSUPPORTED }

@Composable
internal fun RemoteScreenEnvironmentPanel(
    host: RemoteHostEntity,
    state: RemoteScreenEnvironmentState,
    vm: WorkspaceVM,
    blocked: Boolean,
) {
    val resources = LocalResources.current
    var pendingInstall by remember(host.id, host.connectionRevision) { mutableStateOf<CuaInstallRequest?>(null) }
    // A probe from a previously edited host identity must not offer an upgrade on the new host.
    val current = state.takeIf { it.connectionRevision == host.connectionRevision }
    val probe = current?.probe
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.workspace_screen_environment), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.workspace_screen_probe_help), style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !blocked && !state.busy, onClick = { vm.probeScreenHost(host) }) {
            Text(stringResource(R.string.workspace_screen_probe))
        }
        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(if (state.phase == RemoteScreenEnvironmentPhase.INSTALLING)
                    R.string.workspace_screen_installing else R.string.workspace_screen_probing),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        current?.installation?.let { outcome ->
            EnvironmentRow(stringResource(R.string.workspace_screen_cua),
                if (outcome.success) EnvironmentStatus.OK else EnvironmentStatus.ACTION_NEEDED,
                stringResource(if (outcome.success) R.string.workspace_screen_install_success else R.string.workspace_screen_install_failed))
            if (outcome.output.isNotBlank()) RemoteScreenLog(outcome.output, initiallyExpanded = true)
            else Text(stringResource(R.string.workspace_screen_no_output), style = MaterialTheme.typography.bodySmall)
        }
        current?.installationError?.let {
            Text(stringResource(R.string.workspace_screen_install_failed), color = MaterialTheme.colorScheme.error)
            RemoteScreenLog(it.localizedMessage ?: resources.getString(R.string.workspace_screen_connection_failed), initiallyExpanded = true)
        }
        current?.error?.let { Text(remoteScreenSetupError(resources, it), color = MaterialTheme.colorScheme.error) }
        if (probe != null) {
            EnvironmentRow(stringResource(R.string.workspace_screen_system),
                if (probe.os in setOf("macos", "linux")) EnvironmentStatus.OK else EnvironmentStatus.UNSUPPORTED,
                "${when (probe.os) { "macos" -> "macOS"; "linux" -> "Linux"; else -> probe.os }} · ${probe.arch}")
            val supportedSession = probe.session.type in setOf("quartz", "wayland", "x11")
            EnvironmentRow(stringResource(R.string.workspace_screen_session), when {
                !probe.session.present -> EnvironmentStatus.ACTION_NEEDED
                !supportedSession -> EnvironmentStatus.UNSUPPORTED
                else -> EnvironmentStatus.OK
            }, if (probe.session.present) listOfNotNull(probe.session.type, probe.session.desktop?.takeIf { it.isNotBlank() }).joinToString(" · ")
                else resources.getString(R.string.workspace_screen_no_session))
            if (probe.usesRdp) {
                val rdpServer = probe.rdpServer
                EnvironmentRow(stringResource(R.string.workspace_screen_vnc),
                    if (rdpServer != null) EnvironmentStatus.OK else EnvironmentStatus.ACTION_NEEDED,
                    when {
                        rdpServer == null -> resources.getString(R.string.workspace_screen_rdp_missing)
                        probe.rdp.running -> stringResource(R.string.workspace_screen_rdp_running, rdpServerLabel(rdpServer))
                        else -> stringResource(R.string.workspace_screen_rdp_autostart, rdpServerLabel(rdpServer))
                    })
                if (rdpServer == null) RemoteScreenRdpInstallGuidance(probe.session.desktop)
            } else {
                val server = probe.vnc.server?.takeIf { it != "none" && it.isNotBlank() }
                val macScreenSharing = server == "macos-screen-sharing"
                EnvironmentRow(stringResource(R.string.workspace_screen_vnc), when {
                    macScreenSharing && !probe.vnc.running -> EnvironmentStatus.ACTION_NEEDED
                    server == null && !supportedSession -> EnvironmentStatus.UNSUPPORTED
                    server == null -> EnvironmentStatus.ACTION_NEEDED
                    else -> EnvironmentStatus.OK
                }, when {
                    macScreenSharing && !probe.vnc.running -> resources.getString(R.string.workspace_screen_macos_sharing_help)
                    server == null -> resources.getString(R.string.workspace_screen_vnc_missing)
                    probe.vnc.running -> resources.getString(R.string.workspace_screen_vnc_running, server)
                    else -> resources.getString(R.string.workspace_screen_vnc_autostart, server)
                })
                probe.vnc.endpoint?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (server == null && probe.os == "linux") {
                    RemoteScreenVncInstallGuidance(probe.session.type, probe.session.desktop)
                }
            }
            // RDP carries the clipboard itself, so GNOME and KDE need no clipboard tool.
            val clipboard = if (probe.usesRdp) resources.getString(R.string.workspace_screen_clipboard_rdp)
                else probe.clipboard?.takeIf { it.isNotBlank() }
            EnvironmentRow(stringResource(R.string.workspace_screen_clipboard), when {
                clipboard != null -> EnvironmentStatus.OK
                probe.os == "linux" -> EnvironmentStatus.ACTION_NEEDED
                else -> EnvironmentStatus.UNSUPPORTED
            }, clipboard ?: resources.getString(R.string.workspace_screen_clipboard_missing))
            if (clipboard == null && probe.os == "linux") {
                val tool = when (probe.session.type) { "wayland" -> "wl-clipboard"; "x11" -> "xclip"; else -> null }
                if (tool != null) {
                    Text(stringResource(R.string.workspace_screen_install_clipboard, tool), style = MaterialTheme.typography.bodySmall)
                    RemoteScreenCopyCommand("Arch Linux", "sudo pacman -S $tool")
                    RemoteScreenCopyCommand("Debian / Ubuntu", "sudo apt install $tool")
                }
            }
            val cua = probe.cua
            val cuaPath = cua.path?.takeIf { it.isNotBlank() }
            EnvironmentRow(stringResource(R.string.workspace_screen_cua),
                if (cua.ok && cuaPath != null) EnvironmentStatus.OK else EnvironmentStatus.ACTION_NEEDED,
                when {
                    cuaPath == null -> resources.getString(R.string.workspace_screen_cua_missing)
                    !cua.ok -> resources.getString(R.string.workspace_screen_cua_outdated,
                        cua.version ?: resources.getString(R.string.workspace_screen_unknown_version), cua.min)
                    else -> cua.version ?: resources.getString(R.string.workspace_screen_unknown_version)
                })
            if (cuaPath == null || !cua.ok) {
                TextButton(enabled = !blocked && !state.busy, onClick = { pendingInstall = CuaInstallRequest(cuaPath, cua) }) {
                    Text(stringResource(if (cuaPath == null) R.string.workspace_screen_install else R.string.workspace_screen_upgrade))
                }
            }
        }
    }
    pendingInstall?.let { request ->
        val canConfirm = !blocked && !state.busy && probe?.cua == request.cua
        AlertDialog(
            onDismissRequest = { pendingInstall = null },
            title = { Text(stringResource(if (request.upgradePath == null) R.string.workspace_screen_confirm_install else R.string.workspace_screen_confirm_upgrade)) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.workspace_screen_install_disclosure))
                    RemoteScreenCopyCommand(host.name, remoteCuaDriverCommand(request.upgradePath))
                }
            },
            confirmButton = {
                TextButton(enabled = canConfirm, onClick = {
                    pendingInstall = null
                    vm.installScreenCuaDriver(host, request.upgradePath)
                }) { Text(stringResource(if (request.upgradePath == null) R.string.workspace_screen_install else R.string.workspace_screen_upgrade)) }
            },
            dismissButton = { TextButton(onClick = { pendingInstall = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun EnvironmentRow(label: String, status: EnvironmentStatus, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(when (status) {
            EnvironmentStatus.OK -> HugeIcons.CheckmarkCircle01
            EnvironmentStatus.ACTION_NEEDED -> HugeIcons.AlertCircle
            EnvironmentStatus.UNSUPPORTED -> HugeIcons.Unavailable
        }, contentDescription = stringResource(when (status) {
            EnvironmentStatus.OK -> R.string.workspace_screen_status_ok
            EnvironmentStatus.ACTION_NEEDED -> R.string.workspace_screen_status_action
            EnvironmentStatus.UNSUPPORTED -> R.string.workspace_screen_status_unsupported
        }), modifier = Modifier.size(20.dp), tint = when (status) {
            EnvironmentStatus.OK -> MaterialTheme.colorScheme.primary
            EnvironmentStatus.ACTION_NEEDED -> MaterialTheme.colorScheme.error
            EnvironmentStatus.UNSUPPORTED -> MaterialTheme.colorScheme.onSurfaceVariant
        })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
