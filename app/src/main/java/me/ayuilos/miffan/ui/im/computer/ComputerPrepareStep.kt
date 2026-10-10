package me.ayuilos.miffan.ui.im.computer

import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenRdpInstallGuidance
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.rdpServer
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.rdpServerLabel
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.usesRdp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenCopyCommand
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenLog
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenVncInstallGuidance
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteStreamEnableFlow
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteStreamSetupPhase
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteStreamSetupState
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.remoteScreenSetupError
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.InformationCircle

@Composable
internal fun ComputerPrepareStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    var installCommand by remember(state.hostId) { mutableStateOf<String?>(null) }
    val streamSetups by vm.streamSetups.collectAsStateWithLifecycle()
    val stream = state.hostId?.let { streamSetups[it] }?.takeIf { it.connectionRevision == state.hostRevision }
    var enablingStream by remember(state.hostId) { mutableStateOf(false) }
    val probe = state.probe
    val rdp = probe?.usesRdp == true
    // RDP carries the clipboard itself, so GNOME and KDE need no clipboard tool.
    val clipboard = if (rdp) stringResource(R.string.workspace_screen_clipboard_rdp) else probe?.clipboard?.takeIf { it.isNotBlank() }
    val upgrade = probe?.cua?.path != null
    ComputerSetupStepLayout(
        title = stringResource(R.string.im_computer_prepare),
        supporting = stringResource(R.string.im_computer_prepare_help),
        state = state, onDismissError = vm::dismissError,
        primary = if (state.prepared) SetupAction(stringResource(R.string.im_computer_continue), onClick = vm::continueAfterPrepare)
        else SetupAction(stringResource(R.string.im_computer_recheck), onClick = vm::reprobe),
    ) {
        if (probe != null) SetupCard(spacing = 0.dp) {
            CheckRow(stringResource(R.string.im_computer_desktop), if (state.sessionReady) CheckStatus.READY else CheckStatus.NEEDED,
                if (state.sessionReady) listOfNotNull(probe.session.type, probe.session.desktop).joinToString(" · ")
                else stringResource(R.string.im_computer_no_desktop))
            HorizontalDivider(Modifier.padding(start = 36.dp))
            CheckRow(stringResource(R.string.im_computer_screen_service), if (state.screenServiceReady) CheckStatus.READY else CheckStatus.NEEDED,
                when {
                    !state.screenServiceReady -> stringResource(R.string.im_computer_no_screen_service)
                    state.isMac -> stringResource(R.string.im_computer_mac_screen_sharing)
                    rdp -> probe.rdpServer?.let { rdpServerLabel(it) }.orEmpty()
                    else -> probe.vnc.server.orEmpty()
                }) {
                when {
                    state.screenServiceReady -> Unit
                    state.isMac -> Text(stringResource(R.string.im_computer_mac_sharing_help), style = MaterialTheme.typography.bodyMedium)
                    rdp -> RemoteScreenRdpInstallGuidance(probe.session.desktop)
                    else -> RemoteScreenVncInstallGuidance(probe.session.type, probe.session.desktop)
                }
            }
            HorizontalDivider(Modifier.padding(start = 36.dp))
            CheckRow("cua-driver", if (state.cuaReady) CheckStatus.READY else CheckStatus.NEEDED,
                if (state.cuaReady) probe.cua.version.orEmpty() else stringResource(R.string.im_computer_cua_needed, probe.cua.min)) {
                if (!state.cuaReady) FilledTonalButton(enabled = !state.busy, onClick = { installCommand = vm.cuaInstallCommand() }) {
                    Text(stringResource(if (upgrade) R.string.im_computer_upgrade else R.string.im_computer_install))
                }
                state.install?.takeIf { !it.success }?.let { result ->
                    Text(stringResource(R.string.im_computer_install_failed), color = MaterialTheme.colorScheme.error)
                    RemoteScreenLog(result.output.ifBlank { stringResource(R.string.im_computer_no_output) }, initiallyExpanded = true)
                }
            }
            HorizontalDivider(Modifier.padding(start = 36.dp))
            CheckRow(stringResource(R.string.im_computer_clipboard), if (clipboard != null) CheckStatus.READY else CheckStatus.OPTIONAL,
                clipboard ?: stringResource(R.string.im_computer_clipboard_optional)) {
                val tool = when (probe.session.type) { "wayland" -> "wl-clipboard"; "x11" -> "xclip"; else -> null }
                if (clipboard == null && probe.os == "linux" && tool != null) SetupDisclosure(stringResource(R.string.im_computer_show_commands)) {
                    RemoteScreenCopyCommand("Arch Linux", "sudo pacman -S $tool")
                    RemoteScreenCopyCommand("Debian / Ubuntu", "sudo apt install $tool")
                }
            }
            // Smoother picture through Sunshine: optional, offered only where it is already installed.
            if (probe.os == "linux" && stream != null) {
                HorizontalDivider(Modifier.padding(start = 36.dp))
                StreamCheckRow(stream, busy = state.busy || enablingStream, onEnable = { enablingStream = true }, onRecheck = vm::checkStream)
            }
        }
    }
    if (enablingStream && stream != null) {
        RemoteStreamEnableFlow(stream, onEnforce = vm::enforceStreamEncryption, onPair = vm::pairStream,
            onCancelPairing = vm::cancelStreamPairing, onEnable = vm::enableStream, onFinished = { enablingStream = false })
    }
    installCommand?.let { command ->
        AlertDialog(onDismissRequest = { installCommand = null },
            title = { Text(stringResource(R.string.im_computer_install_confirm)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.im_computer_install_disclosure))
                    RemoteScreenCopyCommand("", command)
                }
            }, confirmButton = { TextButton(enabled = !state.busy && command == vm.cuaInstallCommand(), onClick = {
                installCommand = null
                vm.installCuaDriver()
            }) { Text(stringResource(if (upgrade) R.string.im_computer_upgrade else R.string.im_computer_install)) } },
            dismissButton = { TextButton(onClick = { installCommand = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

private enum class CheckStatus { READY, NEEDED, OPTIONAL }

@Composable
private fun StreamCheckRow(stream: RemoteStreamSetupState, busy: Boolean, onEnable: () -> Unit, onRecheck: () -> Unit) {
    val resources = LocalResources.current
    val status = stream.status
    val enabled = stream.streamReady && stream.enabled == true
    CheckRow(stringResource(R.string.im_computer_stream), if (enabled) CheckStatus.READY else CheckStatus.OPTIONAL, when {
        stream.phase == RemoteStreamSetupPhase.CHECKING -> stringResource(R.string.workspace_screen_stream_checking)
        stream.phase == RemoteStreamSetupPhase.ENFORCING -> stringResource(R.string.workspace_screen_stream_enforcing)
        status == null -> stream.error?.let { remoteScreenSetupError(resources, it) } ?: stringResource(R.string.im_computer_stream_optional)
        !status.installed -> stringResource(R.string.im_computer_stream_optional)
        !status.running -> stringResource(R.string.workspace_screen_stream_not_running)
        enabled -> stringResource(R.string.im_computer_stream_enabled)
        else -> stringResource(R.string.im_computer_stream_available)
    }) {
        when {
            stream.busy -> Unit
            status == null || !status.running -> if (status?.installed != false) {
                TextButton(enabled = !busy, onClick = onRecheck) { Text(stringResource(R.string.im_computer_recheck)) }
            }
            !enabled -> FilledTonalButton(enabled = !busy, onClick = onEnable) { Text(stringResource(R.string.im_computer_stream_enable)) }
        }
        if (stream.pairingError != null) Text(stringResource(R.string.workspace_screen_stream_pair_failed), color = MaterialTheme.colorScheme.error)
        if (stream.enforcementError != null || stream.enforcement?.success == false) {
            Text(stringResource(R.string.workspace_screen_stream_enforce_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CheckRow(label: String, status: CheckStatus, detail: String, extra: @Composable ColumnScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Icon(when (status) {
            CheckStatus.READY -> HugeIcons.CheckmarkCircle01
            CheckStatus.NEEDED -> HugeIcons.AlertCircle
            CheckStatus.OPTIONAL -> HugeIcons.InformationCircle
        }, stringResource(when (status) {
            CheckStatus.READY -> R.string.im_computer_ready
            else -> R.string.im_computer_needs_attention
        }), Modifier.size(20.dp), tint = when (status) {
            CheckStatus.READY -> MaterialTheme.colorScheme.primary
            CheckStatus.NEEDED -> MaterialTheme.colorScheme.error
            CheckStatus.OPTIONAL -> MaterialTheme.colorScheme.onSurfaceVariant
        })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            extra()
        }
    }
}
