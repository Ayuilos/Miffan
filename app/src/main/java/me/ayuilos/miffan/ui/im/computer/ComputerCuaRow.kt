package me.ayuilos.miffan.ui.im.computer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.ComputerHolder
import me.ayuilos.miffan.data.ai.computer.CuaUpdateState
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenCopyCommand
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenLog
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.Tick01
import kotlin.uuid.Uuid

/**
 * The partner's computer-use helper (cua-driver) in the checks: installed, too old, or with a newer
 * release, and an update waiting, running or finished. Updates run in [me.ayuilos.miffan.data.ai.computer.CuaUpdates],
 * so this row only reflects them; [onUpdate] asks the user to confirm one.
 */
@Composable
internal fun CuaRow(
    state: ComputerSetupState,
    update: CuaUpdateState?,
    partnerUsing: ComputerHolder?,
    statusPage: Boolean,
    divider: Boolean,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onCancelWaiting: () -> Unit,
) {
    val cua = state.probe?.cua
    val installed = cua?.path != null
    when {
        update is CuaUpdateState.Running -> ChecklistRow(CheckState.WAITING,
            update.target?.let { stringResource(R.string.im_computer_update_running_to, shortVersion(it)) }
                ?: stringResource(R.string.im_computer_update_running), divider = divider) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                UpdateStep(stringResource(R.string.im_computer_update_step_install), done = update.verifying, current = !update.verifying)
                UpdateStep(stringResource(R.string.im_computer_update_step_verify), done = false, current = update.verifying)
            }
            Text(stringResource(R.string.im_computer_update_leave_ok), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        update is CuaUpdateState.Waiting -> ChecklistRow(if (state.cuaReady) CheckState.DONE else CheckState.TODO,
            stringResource(if (state.cuaReady) R.string.im_computer_check_helper_done else R.string.im_computer_check_helper_update),
            divider = divider) {
            UpdateCard {
                Text(stringResource(R.string.im_computer_update_waiting, update.partner.ifBlank { stringResource(R.string.im_computer_update_partner) }),
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onCancelWaiting, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(stringResource(R.string.im_computer_update_cancel_waiting))
                }
            }
        }
        update is CuaUpdateState.Updated -> ChecklistRow(CheckState.DONE, stringResource(R.string.im_computer_update_latest),
            update.probe.cua.version?.let { stringResource(R.string.im_computer_update_done_to, it) }, divider = divider)
        update is CuaUpdateState.Failed -> ChecklistRow(if (update.stillWorks) CheckState.DONE else CheckState.TODO,
            stringResource(if (update.stillWorks) R.string.im_computer_check_helper_done else R.string.im_computer_check_helper_update),
            divider = divider) {
            UpdateCard {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(HugeIcons.AlertCircle, null, Modifier.padding(top = 2.dp).size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.im_computer_update_failed), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onErrorContainer)
                            val kept = update.probe?.cua?.version
                            if (update.unreachable) Text(stringResource(R.string.im_computer_update_failed_network),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(if (update.stillWorks && kept != null) stringResource(R.string.im_computer_update_failed_kept, shortVersion(kept))
                                else stringResource(R.string.im_computer_update_failed_broken),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
                Button(onClick = onUpdate, enabled = !state.busy) { Text(stringResource(R.string.im_computer_update_retry)) }
                SetupDisclosure(stringResource(R.string.im_computer_update_log)) {
                    RemoteScreenLog(update.output.ifBlank { stringResource(R.string.im_computer_no_output) })
                }
            }
        }
        state.cuaReady && statusPage && state.cuaUpdateTo != null -> ChecklistRow(CheckState.DONE,
            stringResource(R.string.im_computer_check_helper_done), divider = divider) {
            UpdateOffer(state, partnerUsing, onUpdate)
        }
        state.cuaReady -> ChecklistRow(CheckState.DONE, stringResource(R.string.im_computer_check_helper_done), divider = divider)
        installed -> ChecklistRow(CheckState.TODO, stringResource(R.string.im_computer_check_helper_update),
            stringResource(R.string.im_computer_check_helper_too_old), divider = divider) {
            Button(enabled = !state.busy, onClick = onUpdate) { Text(stringResource(R.string.im_computer_update)) }
        }
        else -> ChecklistRow(if (state.task == ComputerSetupTask.INSTALLING) CheckState.WAITING else CheckState.TODO,
            stringResource(R.string.im_computer_check_helper), stringResource(R.string.im_computer_check_helper_help), divider = divider) {
            if (state.task != ComputerSetupTask.INSTALLING) Button(enabled = !state.busy, onClick = onInstall) {
                Text(stringResource(R.string.im_computer_install))
            }
            state.install?.takeIf { !it.success }?.let { result ->
                Text(stringResource(R.string.im_computer_install_failed), color = MaterialTheme.colorScheme.error)
                RemoteScreenLog(result.output.ifBlank { stringResource(R.string.im_computer_no_output) })
            }
        }
    }
}

/** A newer release of a working driver: the version, why, and either Update or a wait for the partner using the computer. */
@Composable
private fun UpdateOffer(state: ComputerSetupState, partnerUsing: ComputerHolder?, onUpdate: () -> Unit) {
    val check = state.updateCheck
    val target = state.cuaUpdateTo ?: return
    UpdateCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
                Text(stringResource(R.string.im_computer_update_new), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(stringResource(R.string.im_computer_update_to, shortVersion(target)), style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium)
        }
        if (partnerUsing != null) {
            PartnerUsingLine(partnerUsing)
            FilledTonalButton(onClick = onUpdate, enabled = !state.busy) {
                Icon(HugeIcons.Clock01, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.im_computer_update_when_done))
            }
        } else {
            Text(stringResource(R.string.im_computer_update_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onUpdate, enabled = !state.busy) { Text(stringResource(R.string.im_computer_update)) }
        }
        SetupDisclosure(stringResource(R.string.im_computer_update_details)) {
            state.probe?.cua?.version?.let { VersionLine(stringResource(R.string.im_computer_update_current), it) }
            VersionLine(stringResource(R.string.im_computer_update_latest_version), target)
            state.probe?.cua?.min?.let { VersionLine(stringResource(R.string.im_computer_update_minimum), it) }
            check?.releaseNotesUrl?.takeIf { it.startsWith("https://") }?.let { url ->
                val uri = LocalUriHandler.current
                TextButton(onClick = { uri.openUri(url) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(stringResource(R.string.im_computer_update_notes))
                }
            }
        }
    }
}

@Composable
private fun PartnerUsingLine(holder: ComputerHolder) {
    val partner = LocalSettings.current.assistants.find { it.id == runCatching { Uuid.parse(holder.assistantId) }.getOrNull() }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            partner?.let { AssistantAvatar(it.name, it.avatar, Modifier.size(28.dp)) }
            Text(stringResource(R.string.im_computer_update_partner_using, holder.name.ifBlank { stringResource(R.string.im_computer_update_partner) }),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun UpdateCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun UpdateStep(label: String, done: Boolean, current: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Icon(HugeIcons.Tick01, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                current -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                else -> Surface(Modifier.size(8.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.outlineVariant) {}
            }
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
            color = if (done || current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VersionLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Confirms a cua-driver update: what is downloaded and replaced, that partners pause meanwhile (or that it
 * waits for the partner using the computer), the Windows rollback, and the exact command behind "details".
 */
@Composable
internal fun CuaUpdateDialog(
    state: ComputerSetupState,
    command: String,
    partnerUsing: ComputerHolder?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val current = state.probe?.cua?.version
    val target = state.cuaUpdateTo ?: state.updateCheck?.latestVersion?.takeIf { it != current }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.im_computer_update_confirm)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogLine(HugeIcons.Download01, if (target != null && current != null) stringResource(R.string.im_computer_update_confirm_download, target, current)
                    else stringResource(R.string.im_computer_update_confirm_download_latest))
                DialogLine(HugeIcons.Clock01, if (partnerUsing != null) stringResource(R.string.im_computer_update_confirm_wait,
                    partnerUsing.name.ifBlank { stringResource(R.string.im_computer_update_partner) })
                    else stringResource(R.string.im_computer_update_confirm_pause))
                if (state.isWindows) DialogLine(HugeIcons.Shield01, stringResource(R.string.im_computer_update_confirm_rollback))
                SetupDisclosure(stringResource(R.string.im_computer_show_command)) { RemoteScreenCopyCommand("", command) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) {
            Text(stringResource(if (partnerUsing != null) R.string.im_computer_update_when_done else R.string.im_computer_update))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun DialogLine(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** `0.37.0` → `0.37`: the patch number means nothing to the user. */
internal fun shortVersion(version: String): String = version.removeSuffix(".0")
