package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import me.ayuilos.miffan.data.repository.RemoteStreamStatus
import me.ayuilos.miffan.data.repository.RemoteSunshinePermission
import me.ayuilos.miffan.ui.pages.extensions.workspace.WorkspaceVM

/**
 * High-performance mode for one computer: the switch, then what Sunshine on the computer still
 * needs. Every change on the computer (pairing, the encryption setting) is its own explicit step.
 */
@Composable
internal fun RemoteStreamSettings(
    host: RemoteHostEntity,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    state: RemoteStreamSetupState,
    vm: WorkspaceVM,
    blocked: Boolean,
) {
    val resources = LocalResources.current
    val macOS = RemoteScreenPlatform.parse(host.screenPlatform) == RemoteScreenPlatform.MACOS
    var confirmEncryption by remember(host.id) { mutableStateOf(false) }
    // A result from an earlier host identity must not offer actions on the edited host.
    val current = state.takeIf { it.connectionRevision == host.connectionRevision }
    val status = current?.status
    val busy = blocked || state.busy
    LaunchedEffect(host.id, host.connectionRevision, enabled) {
        if (enabled && current?.status == null && !state.busy) vm.checkStream(host)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.workspace_screen_stream), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = enabled, onCheckedChange = onEnabledChange, enabled = !blocked)
        }
        Text(stringResource(R.string.workspace_screen_stream_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!enabled) return@Column
        if (state.phase in setOf(RemoteStreamSetupPhase.CHECKING, RemoteStreamSetupPhase.ENFORCING, RemoteStreamSetupPhase.STARTING)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(when (state.phase) {
                    RemoteStreamSetupPhase.ENFORCING -> R.string.workspace_screen_stream_enforcing
                    RemoteStreamSetupPhase.STARTING -> R.string.workspace_screen_stream_starting
                    else -> R.string.workspace_screen_stream_checking
                }), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (current?.startFailed == true) Text(stringResource(R.string.workspace_screen_stream_start_failed), color = MaterialTheme.colorScheme.error)
        current?.error?.let { Text(remoteScreenSetupError(resources, it), color = MaterialTheme.colorScheme.error) }
        current?.enforcement?.takeUnless { it.success }?.let { outcome ->
            Text(stringResource(R.string.workspace_screen_stream_enforce_failed), color = MaterialTheme.colorScheme.error)
            if (outcome.output.isNotBlank()) RemoteScreenLog(outcome.output, initiallyExpanded = true)
        }
        current?.enforcementError?.let {
            Text(stringResource(R.string.workspace_screen_stream_enforce_failed), color = MaterialTheme.colorScheme.error)
            RemoteScreenLog(it.localizedMessage ?: resources.getString(R.string.workspace_screen_connection_failed), initiallyExpanded = true)
        }
        current?.pairingError?.let { Text(stringResource(R.string.workspace_screen_stream_pair_failed), color = MaterialTheme.colorScheme.error) }
        if (current?.paired == true) Text(stringResource(R.string.workspace_screen_stream_pair_done), color = MaterialTheme.colorScheme.primary)
        if (status != null) {
            EnvironmentRow(stringResource(R.string.workspace_screen_stream_sunshine),
                if (status.installed) EnvironmentStatus.OK else EnvironmentStatus.ACTION_NEEDED,
                when {
                    !status.installed -> resources.getString(R.string.workspace_screen_stream_missing)
                    !status.running -> resources.getString(R.string.workspace_screen_stream_not_running)
                    else -> resources.getString(R.string.workspace_screen_stream_running,
                        status.version ?: resources.getString(R.string.workspace_screen_unknown_version))
                })
            if (status.installed && !status.running) {
                TextButton(enabled = !busy, onClick = { vm.startSunshine(host) }) { Text(stringResource(R.string.workspace_screen_stream_start)) }
            }
            if (status.installed && macOS) {
                RemoteSunshinePermissions(status, current, enabled = !busy,
                    onOpen = { permission -> vm.openSunshineSettings(host, permission) })
            }
            if (status.displayAsleep == true) {
                Text(stringResource(R.string.workspace_screen_stream_display_asleep), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status.installed) {
                EnvironmentRow(stringResource(R.string.workspace_screen_stream_encryption),
                    if (status.encryptionEnforced) EnvironmentStatus.OK else EnvironmentStatus.ACTION_NEEDED,
                    stringResource(if (status.encryptionEnforced) R.string.workspace_screen_stream_encryption_on
                        else R.string.workspace_screen_stream_encryption_off))
                if (!status.encryptionEnforced) {
                    TextButton(enabled = !busy, onClick = { confirmEncryption = true }) {
                        Text(stringResource(R.string.workspace_screen_stream_enforce))
                    }
                }
                EnvironmentRow(stringResource(R.string.workspace_screen_stream_pairing),
                    if (status.paired) EnvironmentStatus.OK else EnvironmentStatus.ACTION_NEEDED,
                    stringResource(if (status.paired) R.string.workspace_screen_stream_paired else R.string.workspace_screen_stream_unpaired))
                // Sunshine only accepts a PIN while it runs, so pairing waits for it.
                if (!status.paired && status.running) {
                    TextButton(enabled = !busy, onClick = { vm.pairStream(host) }) {
                        Text(stringResource(R.string.workspace_screen_stream_pair))
                    }
                }
                EnvironmentRow(stringResource(R.string.workspace_screen_stream_addresses),
                    if (status.candidates.isNotEmpty()) EnvironmentStatus.OK else EnvironmentStatus.ACTION_NEEDED,
                    if (status.candidates.isNotEmpty()) status.candidates.joinToString(" → ")
                    else resources.getString(R.string.workspace_screen_stream_no_addresses))
                Text(stringResource(R.string.workspace_screen_stream_firewall), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(enabled = !busy, onClick = { vm.checkStream(host) }) { Text(stringResource(R.string.workspace_screen_stream_check)) }
    }
    if (confirmEncryption && status != null) {
        RemoteStreamEncryptionDialog(activeStream = status.activeStream, enabled = !busy,
            onConfirm = { confirmEncryption = false; vm.enforceStreamEncryption(host) },
            onDismiss = { confirmEncryption = false })
    }
    if (state.phase == RemoteStreamSetupPhase.PAIRING && state.connectionRevision == host.connectionRevision) {
        RemoteStreamPairingDialog(pin = state.pin, onCancel = vm::cancelStreamPairing)
    }
}

/**
 * Sunshine's two macOS grants. They can only be switched on at the Mac, so each row can open the
 * right System Settings page there; a value the computer cannot report stays "cannot confirm".
 */
@Composable
internal fun RemoteSunshinePermissions(
    status: RemoteStreamStatus,
    state: RemoteStreamSetupState?,
    enabled: Boolean,
    onOpen: (RemoteSunshinePermission) -> Unit,
) {
    @Composable
    fun row(permission: RemoteSunshinePermission, label: Int, granted: Boolean?, denied: Int) {
        EnvironmentRow(stringResource(label), when (granted) {
            true -> EnvironmentStatus.OK
            false -> EnvironmentStatus.ACTION_NEEDED
            null -> EnvironmentStatus.UNSUPPORTED
        }, stringResource(when (granted) {
            true -> R.string.workspace_screen_stream_permission_granted
            false -> denied
            null -> R.string.workspace_screen_stream_permission_unknown
        }))
        if (granted != true) TextButton(enabled = enabled, onClick = { onOpen(permission) }) {
            Text(stringResource(R.string.workspace_screen_stream_open_settings))
        }
    }
    row(RemoteSunshinePermission.SCREEN_RECORDING, R.string.workspace_screen_stream_screen_recording, status.screenRecording,
        R.string.workspace_screen_stream_screen_denied)
    row(RemoteSunshinePermission.ACCESSIBILITY, R.string.workspace_screen_stream_accessibility, status.accessibility,
        R.string.workspace_screen_stream_permission_unknown)
    Text(stringResource(R.string.workspace_screen_stream_permission_help), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state?.settingsOpened != null) Text(stringResource(R.string.workspace_screen_stream_settings_opened),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    if (state?.settingsFailed == true) Text(stringResource(R.string.workspace_screen_stream_open_settings_failed),
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

/** States exactly what changes in Sunshine before the user allows it. */
@Composable
internal fun RemoteStreamEncryptionDialog(activeStream: Boolean, enabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_screen_stream_enforce_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.workspace_screen_stream_enforce_disclosure))
                if (activeStream) Text(stringResource(R.string.workspace_screen_stream_enforce_busy), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = enabled && !activeStream, onClick = onConfirm) { Text(stringResource(R.string.workspace_screen_stream_enforce)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * One "Enable" for people who do not want the details: require encryption (after the user
 * confirms exactly that change), pair, then switch the mode on. Each step runs at most once, so
 * a refused confirmation, a cancelled PIN or a failure ends the flow through [onFinished].
 */
@Composable
internal fun RemoteStreamEnableFlow(
    state: RemoteStreamSetupState,
    onStart: () -> Unit,
    onEnforce: () -> Unit,
    onPair: () -> Unit,
    onCancelPairing: () -> Unit,
    onEnable: () -> Unit,
    /** True when high-performance mode ended up switched on. */
    onFinished: (Boolean) -> Unit,
) {
    var askEncryption by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var enforced by remember { mutableStateOf(false) }
    var paired by remember { mutableStateOf(false) }
    var switched by remember { mutableStateOf(false) }
    val status = state.status
    LaunchedEffect(state.phase, status, state.enabled) {
        if (state.busy || status == null || askEncryption) return@LaunchedEffect
        when {
            !status.installed -> onFinished(false)
            // Pressing "Turn on" includes starting Sunshine once if it is installed but closed.
            !status.running -> if (started) onFinished(false) else { started = true; onStart() }
            !status.encryptionEnforced -> if (enforced) onFinished(false) else askEncryption = true
            !status.paired -> if (paired) onFinished(false) else { paired = true; onPair() }
            state.enabled != true -> if (switched) onFinished(false) else { switched = true; onEnable() }
            else -> onFinished(true)
        }
    }
    if (askEncryption && status != null) {
        RemoteStreamEncryptionDialog(activeStream = status.activeStream, enabled = !state.busy,
            onConfirm = { askEncryption = false; enforced = true; onEnforce() },
            onDismiss = { askEncryption = false; onFinished(false) })
    }
    if (state.phase == RemoteStreamSetupPhase.PAIRING) RemoteStreamPairingDialog(pin = state.pin, onCancel = onCancelPairing)
}

/** Shows the PIN to type into Sunshine's web page and waits; only cancelling closes it early. */
@Composable
internal fun RemoteStreamPairingDialog(pin: String?, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.workspace_screen_stream_pair_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.workspace_screen_stream_pair_steps))
                if (pin != null) {
                    Text(pin.toList().joinToString(" "), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        fontFamily = FontFamily.Monospace, fontSize = 40.sp, style = MaterialTheme.typography.displaySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(if (pin == null) R.string.workspace_screen_stream_pair_starting else R.string.workspace_screen_stream_pair_waiting),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
    )
}
