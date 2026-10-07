package me.ayuilos.miffan.ui.im.computer

import android.content.res.Resources
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.PartnerComputer
import me.ayuilos.miffan.data.ai.computer.PartnerComputers
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

internal fun computerErrorMessage(resources: Resources, error: Throwable): String =
    when ((error as? RemoteScreenUnavailableException)?.problem) {
        RemoteScreenProblem.NO_GRAPHICAL_SESSION -> resources.getString(R.string.im_computer_no_desktop)
        RemoteScreenProblem.NO_VNC_SERVER -> resources.getString(R.string.im_computer_no_screen_service)
        RemoteScreenProblem.VNC_START_FAILED -> resources.getString(R.string.im_computer_screen_start_failed)
        RemoteScreenProblem.NO_WORKSPACE, RemoteScreenProblem.NOT_ENABLED,
        RemoteScreenProblem.BAD_ENDPOINT, RemoteScreenProblem.PASSWORD_MISSING -> resources.getString(R.string.im_computer_setup_needed)
        else -> error.workspaceErrorMessage(resources) ?: resources.getString(R.string.im_computer_connection_failed)
    }

@Composable
internal fun PartnerComputerPermissionRow(
    computer: PartnerComputer?,
    enabled: Boolean,
    busy: Boolean,
    approvalRequired: Boolean,
    onEnabled: (Boolean) -> Unit,
    onApprovalRequired: (Boolean) -> Unit,
    onSetup: () -> Unit,
    onScreen: () -> Unit,
) {
    var confirm by rememberSaveable(computer?.workspaceId, computer?.hostId) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.im_computer_control), style = MaterialTheme.typography.titleMedium)
                    Text(computer?.name ?: stringResource(R.string.im_computer_connect_help), style = MaterialTheme.typography.bodySmall)
                }
                if (computer != null) Switch(checked = enabled, enabled = !busy,
                    onCheckedChange = { if (it) confirm = true else onEnabled(false) })
            }
            if (computer != null && enabled) {
                HorizontalDivider()
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.computer_use_ask_before_actions), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.computer_use_ask_before_actions_help), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = approvalRequired, enabled = !busy, onCheckedChange = onApprovalRequired)
                }
            }
            if (computer == null) TextButton(onClick = onSetup, enabled = !busy) { Text(stringResource(R.string.im_computer_connect)) }
            else {
                if (computer.showsEntry) TextButton(onClick = onScreen, enabled = !busy) { Text(stringResource(R.string.im_computer_view_screen)) }
                TextButton(onClick = onSetup, enabled = !busy) { Text(stringResource(R.string.im_computer_recheck_or_change)) }
            }
        }
    }
    if (confirm && computer != null) AlertDialog(onDismissRequest = { if (!busy) confirm = false },
        title = { Text(stringResource(R.string.computer_use_confirm_enable)) },
        text = { Text(stringResource(R.string.computer_use_enable_disclosure)) },
        confirmButton = { TextButton(enabled = !busy, onClick = { confirm = false; onEnabled(true) }) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirm = false }) { Text(stringResource(R.string.common_cancel)) } })
}
