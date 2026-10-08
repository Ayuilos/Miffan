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
import me.ayuilos.miffan.data.model.ComputerUseMode
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
        RemoteScreenProblem.VNC_START_FAILED -> resources.getString(R.string.im_computer_screen_start_failed)
        RemoteScreenProblem.NO_WORKSPACE, RemoteScreenProblem.NOT_ENABLED,
        RemoteScreenProblem.BAD_ENDPOINT, RemoteScreenProblem.PASSWORD_MISSING -> resources.getString(R.string.im_computer_setup_needed)
        else -> me.ayuilos.miffan.ui.pages.extensions.workspace.screen.remoteSshError(resources, error)
            ?: error.workspaceErrorMessage(resources) ?: resources.getString(R.string.im_computer_connection_failed)
    }

@Composable
internal fun PartnerComputerPermissionRow(
    computer: PartnerComputer?,
    /** Bound to a local workspace on this phone, which only the professional interface manages. */
    usesPhone: Boolean,
    mode: ComputerUseMode,
    busy: Boolean,
    onMode: (ComputerUseMode) -> Unit,
    onSetup: () -> Unit,
    onScreen: () -> Unit,
    onDisconnect: () -> Unit,
    /** Opens the computer's own page in "my computers". */
    onOpenComputer: () -> Unit,
) {
    var confirmDisconnect by rememberSaveable(computer?.workspaceId) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth()) {
            if (computer == null && usesPhone) {
                PartnerComputerRow(stringResource(R.string.im_computer_phone_title), stringResource(R.string.im_computer_phone_help)) {}
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                PartnerComputerRow(stringResource(R.string.im_computer_phone_switch), null, enabled = !busy, onClick = onSetup) {
                    Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            if (computer == null) {
                PartnerComputerRow(stringResource(R.string.im_computer_control), stringResource(R.string.im_computer_connect_help),
                    enabled = !busy, onClick = onSetup) { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                return@Column
            }
            PartnerComputerRow(stringResource(R.string.im_computer_control), computer.name, enabled = !busy, onClick = onOpenComputer) {
                Icon(HugeIcons.ArrowRight01, stringResource(R.string.im_computer_open_details), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ComputerUseModeSelector(mode, enabled = !busy, onMode = onMode,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
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
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            PartnerComputerRow(stringResource(R.string.im_computer_disconnect), null, enabled = !busy,
                color = MaterialTheme.colorScheme.error, onClick = { confirmDisconnect = true }) {}
        }
    }
    if (confirmDisconnect && computer != null) AlertDialog(onDismissRequest = { confirmDisconnect = false },
        title = { Text(stringResource(R.string.im_computer_disconnect_title, computer.name)) },
        text = { Text(stringResource(R.string.im_computer_disconnect_help)) },
        confirmButton = {
            TextButton(onClick = { confirmDisconnect = false; onDisconnect() }) {
                Text(stringResource(R.string.im_computer_disconnect_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.common_cancel)) } })
}

/** Ask first / automatic / off, with what the chosen mode means. Automatic confirms first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComputerUseModeSelector(
    mode: ComputerUseMode,
    enabled: Boolean,
    onMode: (ComputerUseMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmAuto by rememberSaveable { mutableStateOf(false) }
    val modes = listOf(
        ComputerUseMode.ASK to R.string.computer_use_mode_ask,
        ComputerUseMode.AUTO to R.string.computer_use_mode_auto,
        ComputerUseMode.OFF to R.string.computer_use_mode_off,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (value, label) ->
                SegmentedButton(selected = mode == value, enabled = enabled,
                    onClick = { if (value == ComputerUseMode.AUTO && mode != value) confirmAuto = true else onMode(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size)) { Text(stringResource(label), maxLines = 1) }
            }
        }
        Text(stringResource(when (mode) {
            ComputerUseMode.ASK -> R.string.computer_use_mode_ask_help
            ComputerUseMode.AUTO -> R.string.computer_use_mode_auto_help
            ComputerUseMode.OFF -> R.string.computer_use_mode_off_help
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmAuto) AlertDialog(onDismissRequest = { confirmAuto = false },
        title = { Text(stringResource(R.string.computer_use_confirm_auto)) },
        text = { Text(stringResource(R.string.computer_use_auto_disclosure)) },
        confirmButton = { TextButton(onClick = { confirmAuto = false; onMode(ComputerUseMode.AUTO) }) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmAuto = false }) { Text(stringResource(R.string.common_cancel)) } })
}

/** One row of the IM settings card; tappable when [onClick] is given. */
@Composable
private fun PartnerComputerRow(
    title: String,
    supporting: String?,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val content = @Composable {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (supporting == null) 16.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = color)
                supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            trailing()
        }
    }
    if (onClick != null) Surface(onClick = onClick, enabled = enabled, color = Color.Transparent) { content() } else content()
}
