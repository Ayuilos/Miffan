package me.ayuilos.miffan.ui.im.computer

import android.content.res.Resources
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.PartnerComputer
import me.ayuilos.miffan.data.ai.computer.PartnerComputers
import me.ayuilos.miffan.data.model.ComputerUseMode
import me.ayuilos.miffan.data.repository.RemoteScreenProblem
import me.ayuilos.miffan.data.repository.RemoteScreenUnavailableException
import me.ayuilos.miffan.utils.workspaceErrorMessage
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun rememberPartnerComputer(assistantId: Uuid): State<PartnerComputer?> {
    val computers: PartnerComputers = koinInject()
    return remember(computers, assistantId) { computers.observe(assistantId) }
        .collectAsStateWithLifecycle(initialValue = null)
}

@Composable
internal fun rememberPartnerUsesPhone(assistantId: Uuid): State<Boolean> {
    val computers: PartnerComputers = koinInject()
    return remember(computers, assistantId) { computers.observeUsesPhone(assistantId) }
        .collectAsStateWithLifecycle(initialValue = false)
}

internal fun computerErrorMessage(resources: Resources, error: Throwable): String =
    when ((error as? RemoteScreenUnavailableException)?.problem) {
        RemoteScreenProblem.NO_GRAPHICAL_SESSION -> resources.getString(R.string.im_computer_no_desktop)
        RemoteScreenProblem.NO_VNC_SERVER -> resources.getString(R.string.im_computer_no_screen_service)
        RemoteScreenProblem.VNC_START_FAILED, RemoteScreenProblem.RDP_START_FAILED -> resources.getString(R.string.im_computer_screen_start_failed)
        RemoteScreenProblem.NO_RDP_SERVER, RemoteScreenProblem.RDP_ALREADY_CONFIGURED, RemoteScreenProblem.RDP_KEYRING_LOCKED,
        RemoteScreenProblem.RDP_CREDENTIAL_SETUP_UNAVAILABLE, RemoteScreenProblem.RDP_CERTIFICATE_CHANGED ->
            me.ayuilos.miffan.ui.pages.extensions.workspace.screen.remoteScreenSetupError(resources, error)
        RemoteScreenProblem.NO_WORKSPACE, RemoteScreenProblem.NOT_ENABLED,
        RemoteScreenProblem.BAD_ENDPOINT, RemoteScreenProblem.PASSWORD_MISSING -> resources.getString(R.string.im_computer_setup_needed)
        RemoteScreenProblem.WINDOWS_NOT_SUPPORTED -> resources.getString(R.string.im_computer_windows_unsupported)
        RemoteScreenProblem.FIREWALL_REVIEW_NEEDED, RemoteScreenProblem.FIREWALL_CHECK_FAILED, RemoteScreenProblem.STREAM_UNAVAILABLE ->
            me.ayuilos.miffan.ui.pages.extensions.workspace.screen.remoteScreenSetupError(resources, error)
        else -> me.ayuilos.miffan.ui.pages.extensions.workspace.screen.remoteSshError(resources, error)
            ?: error.workspaceErrorMessage(resources) ?: resources.getString(R.string.im_computer_connection_failed)
    }

/** Ask first / automatic / off, with what the chosen mode means. Automatic confirms first. */
@Composable
internal fun ComputerUseModeSelector(
    mode: ComputerUseMode,
    enabled: Boolean,
    onMode: (ComputerUseMode) -> Unit,
    modifier: Modifier = Modifier,
    /** False for a server or NAS without a screen: the modes then only cover commands and files. */
    screen: Boolean = true,
) {
    var confirmAuto by rememberSaveable { mutableStateOf(false) }
    val modes = listOf(
        ComputerUseMode.ASK to R.string.computer_use_mode_ask,
        ComputerUseMode.AUTO to R.string.computer_use_mode_auto,
        ComputerUseMode.OFF to R.string.computer_use_mode_off,
    )
    // One row per mode; only the chosen one explains itself, so the choice stays readable.
    Column(modifier.selectableGroup()) {
        modes.forEach { (value, label) ->
            val selected = mode == value
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
                        onClick = { if (value == ComputerUseMode.AUTO && !selected) confirmAuto = true else onMode(value) })
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = selected, onClick = null, enabled = enabled)
                Column(Modifier.weight(1f).padding(top = 1.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    if (selected) Text(stringResource(when (value) {
                        ComputerUseMode.ASK -> if (screen) R.string.computer_use_mode_ask_help else R.string.computer_use_mode_ask_help_no_screen
                        ComputerUseMode.AUTO -> if (screen) R.string.computer_use_mode_auto_help else R.string.computer_use_mode_auto_help_no_screen
                        ComputerUseMode.OFF -> if (screen) R.string.computer_use_mode_off_help else R.string.computer_use_mode_off_help_no_screen
                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (confirmAuto) AlertDialog(onDismissRequest = { confirmAuto = false },
        title = { Text(stringResource(R.string.computer_use_confirm_auto)) },
        text = { Text(stringResource(if (screen) R.string.computer_use_auto_disclosure else R.string.computer_use_auto_disclosure_no_screen)) },
        confirmButton = { TextButton(onClick = { confirmAuto = false; onMode(ComputerUseMode.AUTO) }) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmAuto = false }) { Text(stringResource(R.string.common_cancel)) } })
}
