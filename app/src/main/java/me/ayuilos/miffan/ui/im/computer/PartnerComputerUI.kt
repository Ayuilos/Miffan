package me.ayuilos.miffan.ui.im.computer

import android.content.res.Resources
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.PartnerComputer
import me.ayuilos.miffan.data.ai.computer.PartnerComputers
import me.ayuilos.miffan.data.repository.RemoteScreenProblem
import me.ayuilos.miffan.data.repository.RemoteScreenUnavailableException
import me.ayuilos.miffan.utils.workspaceErrorMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
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
        else -> me.ayuilos.miffan.ui.pages.extensions.workspace.screen.remoteSshError(resources, error)
            ?: error.workspaceErrorMessage(resources) ?: resources.getString(R.string.im_computer_connection_failed)
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
        Column(Modifier.fillMaxWidth()) {
            if (computer == null) {
                PartnerComputerRow(stringResource(R.string.im_computer_control), stringResource(R.string.im_computer_connect_help),
                    enabled = !busy, onClick = onSetup) { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                return@Column
            }
            PartnerComputerRow(stringResource(R.string.im_computer_control), computer.name) {
                Switch(checked = enabled, enabled = !busy, onCheckedChange = { if (it) confirm = true else onEnabled(false) })
            }
            if (enabled) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                PartnerComputerRow(stringResource(R.string.computer_use_ask_before_actions), stringResource(R.string.computer_use_ask_before_actions_help)) {
                    Switch(checked = approvalRequired, enabled = !busy, onCheckedChange = onApprovalRequired)
                }
            }
            if (computer.showsEntry) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                PartnerComputerRow(stringResource(R.string.im_computer_view_screen), null, enabled = !busy, onClick = onScreen) {
                    Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            PartnerComputerRow(stringResource(R.string.im_computer_recheck_or_change), null, enabled = !busy, onClick = onSetup) {
                Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (confirm && computer != null) AlertDialog(onDismissRequest = { if (!busy) confirm = false },
        title = { Text(stringResource(R.string.computer_use_confirm_enable)) },
        text = { Text(stringResource(R.string.computer_use_enable_disclosure)) },
        confirmButton = { TextButton(enabled = !busy, onClick = { confirm = false; onEnabled(true) }) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirm = false }) { Text(stringResource(R.string.common_cancel)) } })
}

/** One row of the IM settings card; tappable when [onClick] is given. */
@Composable
private fun PartnerComputerRow(
    title: String,
    supporting: String?,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val content = @Composable {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (supporting == null) 16.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title)
                supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            trailing()
        }
    }
    if (onClick != null) Surface(onClick = onClick, enabled = enabled, color = Color.Transparent) { content() } else content()
}
