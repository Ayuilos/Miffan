package me.ayuilos.miffan.ui.im.computer

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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.CheckmarkCircle01

@Composable
internal fun ComputerPrepareStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    var installCommand by remember(state.hostId) { mutableStateOf<String?>(null) }
    val probe = state.probe
    val unsupported = probe?.session?.desktop?.let { it.contains("gnome", true) || it.contains("kde", true) } == true && probe.session.type == "wayland" && !state.isMac
    Text(stringResource(R.string.im_computer_prepare), style = MaterialTheme.typography.headlineSmall)
    ComputerCheckRow(stringResource(R.string.im_computer_desktop), state.sessionReady,
        if (state.sessionReady) listOfNotNull(probe?.session?.type, probe?.session?.desktop).joinToString(" · ") else stringResource(R.string.im_computer_no_desktop))
    ComputerCheckRow(stringResource(R.string.im_computer_screen_service), state.screenServiceReady,
        if (state.screenServiceReady) stringResource(R.string.im_computer_ready) else stringResource(R.string.im_computer_no_screen_service))
    if (state.isMac) Text(stringResource(R.string.im_computer_mac_sharing_help))
    else if (unsupported) Text(stringResource(R.string.im_computer_unsupported_desktop), color = MaterialTheme.colorScheme.error)
    else if (!state.screenServiceReady && probe != null) RemoteScreenVncInstallGuidance(probe.session.type, probe.session.desktop)
    val clipboard = probe?.clipboard?.takeIf { it.isNotBlank() }
    ComputerCheckRow(stringResource(R.string.im_computer_clipboard), clipboard != null,
        clipboard ?: stringResource(R.string.im_computer_clipboard_optional))
    if (clipboard == null && probe?.os == "linux") {
        val tool = when (probe.session.type) { "wayland" -> "wl-clipboard"; "x11" -> "xclip"; else -> null }
        tool?.let {
            RemoteScreenCopyCommand("Arch Linux", "sudo pacman -S $it")
            RemoteScreenCopyCommand("Debian / Ubuntu", "sudo apt install $it")
        }
    }
    ComputerCheckRow("cua-driver", state.cuaReady,
        if (state.cuaReady) probe?.cua?.version.orEmpty()
        else stringResource(R.string.im_computer_cua_needed, probe?.cua?.min ?: "0.34"))
    if (!state.cuaReady && probe != null) {
        TextButton(enabled = !state.busy, onClick = { installCommand = vm.cuaInstallCommand() }) {
            Text(stringResource(if (probe.cua.path == null) R.string.im_computer_install else R.string.im_computer_upgrade))
        }
    }
    state.install?.let { result ->
        Text(stringResource(if (result.success) R.string.im_computer_install_success else R.string.im_computer_install_failed),
            color = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        if (!result.success) RemoteScreenLog(result.output.ifBlank { stringResource(R.string.im_computer_no_output) }, initiallyExpanded = true)
    }
    ComputerSetupButton(stringResource(if (state.prepared) R.string.im_computer_continue else R.string.im_computer_recheck), state.busy,
        onClick = { if (state.prepared) vm.continueAfterPrepare() else vm.reprobe() })
    if (state.prepared) TextButton(enabled = !state.busy, onClick = vm::reprobe) { Text(stringResource(R.string.im_computer_recheck)) }
    installCommand?.let { command ->
        AlertDialog(onDismissRequest = { installCommand = null },
            title = { Text(stringResource(R.string.im_computer_install_confirm)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.im_computer_install_disclosure))
                    RemoteScreenCopyCommand("cua-driver", command)
                }
            }, confirmButton = { TextButton(enabled = !state.busy && command == vm.cuaInstallCommand(), onClick = {
                installCommand = null
                vm.installCuaDriver()
            }) { Text(stringResource(if (probe?.cua?.path == null) R.string.im_computer_install else R.string.im_computer_upgrade)) } },
            dismissButton = { TextButton(onClick = { installCommand = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

@Composable
private fun ComputerCheckRow(label: String, ready: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(if (ready) HugeIcons.CheckmarkCircle01 else HugeIcons.AlertCircle,
            stringResource(if (ready) R.string.im_computer_ready else R.string.im_computer_needs_attention),
            Modifier.size(20.dp), tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
