package me.ayuilos.miffan.ui.im.computer

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.SpaceSetupJob
import me.ayuilos.miffan.data.ai.tools.spaceNetworkFixCommand
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.thread.threadAssistantName
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Cube
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.WifiOff01


/** First disk size, roughly: the runtime, the image and room to work. */
private const val SPACE_DISK_GB = 5

@Composable
private fun sharePartners(state: ComputerSetupState): List<Assistant> {
    val assistants = LocalSettings.current.assistants
    return state.sharePartners.mapNotNull { id -> assistants.find { it.id == id } }
}

/** Use the desktop, or give the partners their own computer inside this one. */
@Composable
internal fun ComputerHowStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    val partners = sharePartners(state)
    val computer = state.target?.name.orEmpty()
    val first = partners.firstOrNull()
    SetupScreen(
        title = if (partners.size == 1 && first != null) stringResource(R.string.im_space_how_title, threadAssistantName(first), computer)
            else stringResource(R.string.im_space_how_title_many, computer),
        subtitle = stringResource(R.string.im_space_how_help),
        state = state, onDismissError = vm::dismissError,
        hero = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                partners.take(3).forEach { AssistantAvatar(it.name, it.avatar, Modifier.size(56.dp)) }
                DeviceTile(state.system.icon(), size = 56.dp)
            }
        },
        primary = SetupAction(stringResource(R.string.im_space_continue), onClick = vm::continueHow),
    ) {
        HowOption(
            selected = !state.useSpace, icon = HugeIcons.Computer,
            title = stringResource(R.string.im_space_desktop_title),
            body = stringResource(R.string.im_space_desktop_body),
            note = stringResource(R.string.im_space_desktop_note),
            enabled = !state.busy,
        ) { vm.chooseSpace(false) }
        HowOption(
            selected = state.useSpace, icon = HugeIcons.Cube,
            title = stringResource(if (partners.size > 1) R.string.im_space_own_title_many else R.string.im_space_own_title),
            body = stringResource(R.string.im_space_own_body, computer),
            note = stringResource(R.string.im_space_own_cost),
            tag = stringResource(R.string.im_space_safer),
            enabled = !state.busy,
        ) { vm.chooseSpace(true) }
        if (state.useSpace) {
            var adjusting by rememberSaveable { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.pickedTier == null && state.spaceCapacity != null)
                        stringResource(R.string.im_space_tier_line, state.spaceTier.summary(), computer)
                    else stringResource(R.string.im_space_tier_line_picked, state.spaceTier.summary()),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { adjusting = true }, enabled = !state.busy) { Text(stringResource(R.string.im_space_tier_adjust)) }
            }
            if (adjusting) SpaceTierSheet(
                current = state.spaceTier,
                recommended = state.recommendedTier.takeIf { state.spaceCapacity != null },
                capacity = state.spaceCapacity,
                usedMemoryMb = state.spaceUsedMemoryMb,
                count = partners.size,
                onPick = { vm.pickTier(it); adjusting = false },
                onDismiss = { adjusting = false },
            )
        }
    }
}

@Composable
private fun HowOption(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    body: String,
    note: String,
    enabled: Boolean,
    tag: String? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = if (selected) colors.primaryContainer else colors.surfaceContainerHighest,
                contentColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    tag?.let {
                        Surface(shape = RoundedCornerShape(8.dp), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                            Text(it, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

/** cua is missing, or the computer cannot run it yet (Docker on Linux): what is in place and what is not. */
@Composable
internal fun ComputerSpaceInstallStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    val probe = state.spaceProbe
    val computer = state.target?.name.orEmpty()
    val dockerOk = probe?.docker?.ok != false
    val cua = probe?.cuaInstalled == true
    SetupScreen(
        title = stringResource(R.string.im_space_install_title, computer),
        subtitle = stringResource(R.string.im_space_install_help),
        state = state, onDismissError = vm::dismissError,
        hero = { DeviceTile(HugeIcons.Download01, size = 72.dp) },
        primary = if (!dockerOk) SetupAction(stringResource(R.string.im_space_check_again), onClick = vm::recheckSpaces)
            else SetupAction(stringResource(if (cua) R.string.im_space_continue else R.string.im_space_install_action), onClick = vm::installCua),
        secondary = SetupAction(stringResource(R.string.im_space_later)) { vm.back() },
    ) {
        SetupCard(padding = 16.dp, spacing = 0.dp) {
            ChecklistRow(CheckState.DONE, stringResource(R.string.im_space_check_connected, computer))
            probe?.diskFreeGb?.let { free ->
                ChecklistRow(if (free >= SPACE_DISK_GB) CheckState.DONE else CheckState.TODO,
                    stringResource(if (free >= SPACE_DISK_GB) R.string.im_space_check_disk else R.string.im_space_check_disk_low, free, SPACE_DISK_GB))
            }
            probe?.docker?.let { docker ->
                ChecklistRow(if (docker.ok) CheckState.DONE else CheckState.TODO,
                    stringResource(if (docker.ok) R.string.im_space_check_docker else R.string.im_space_check_docker_missing),
                    if (docker.ok) null else stringResource(R.string.im_space_check_docker_fix))
            }
            ChecklistRow(if (cua) CheckState.DONE else CheckState.TODO,
                stringResource(if (cua) R.string.im_space_check_cua else R.string.im_space_check_cua_missing),
                if (cua) null else stringResource(R.string.im_space_check_cua_detail), divider = false)
        }
        if (!cua) SetupDisclosure(stringResource(R.string.im_space_show_command)) {
            CommandPreview(SPACE_INSTALL_COMMAND)
        }
    }
}

/** What `miffan space install` runs, shown before the user agrees. */
private const val SPACE_INSTALL_COMMAND =
    "curl -fsSL https://cua.ai/install.sh | sh -s -- --cli-only --yes --no-modify-path --no-onboarding"

/** The run under way: checked, installed, making the computer, checking the internet. */
@Composable
internal fun ComputerSpacePrepareStep(vm: ComputerSetupVM, state: ComputerSetupState, onChat: () -> Unit) {
    val job by vm.spaceJob.collectAsStateWithLifecycle()
    val partners = sharePartners(state)
    val first = partners.firstOrNull()
    val computer = state.target?.name.orEmpty()
    val failed = job?.stage == SpaceSetupJob.Stage.FAILED
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(failed) {
        while (!failed) { now = System.currentTimeMillis(); delay(1_000) }
    }
    val elapsed = ((now - (job?.startedAt ?: now)) / 1000).coerceAtLeast(0)
    val creating = job?.stage == SpaceSetupJob.Stage.CREATING
    // The failure sits in the error card; dismissing it clears nothing on the run itself.
    val shown = if (failed) state.copy(error = job?.error) else state
    SetupScreen(
        title = if (partners.size == 1 && first != null) stringResource(R.string.im_space_prepare_title, threadAssistantName(first))
            else stringResource(R.string.im_space_prepare_title_many),
        subtitle = stringResource(R.string.im_space_prepare_help),
        state = shown, onDismissError = vm::dismissError,
        hero = {
            Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                if (!failed) CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 5.dp)
                if (first != null) AssistantAvatar(first.name, first.avatar, Modifier.size(56.dp))
            }
        },
        primary = if (failed) SetupAction(stringResource(R.string.im_space_retry), onClick = vm::retrySpaces) else null,
        secondary = SetupAction(stringResource(R.string.im_space_go_chat), onClick = onChat),
    ) {
        SetupCard(padding = 16.dp, spacing = 0.dp) {
            ChecklistRow(CheckState.DONE, stringResource(R.string.im_space_stage_check, computer))
            ChecklistRow(CheckState.DONE, stringResource(R.string.im_space_stage_install))
            ChecklistRow(if (creating) CheckState.WAITING else if (failed) CheckState.TODO else CheckState.DONE,
                stringResource(R.string.im_space_stage_create),
                if (creating) stringResource(R.string.im_space_stage_create_detail, formatElapsed(elapsed)) else null)
            ChecklistRow(if (job?.stage == SpaceSetupJob.Stage.CHECKING_NETWORK) CheckState.WAITING else CheckState.LATER,
                stringResource(R.string.im_space_stage_network), divider = false)
        }
    }
}

private fun formatElapsed(seconds: Long): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** Made, but offline: the firewall fix when the check points at ufw, else a hint to look at the network. */
@Composable
internal fun ComputerSpaceNetworkStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    val job by vm.spaceJob.collectAsStateWithLifecycle()
    val partners = sharePartners(state)
    val first = partners.firstOrNull()
    val computer = state.target?.name.orEmpty()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val checking = job?.stage == SpaceSetupJob.Stage.CHECKING_NETWORK
    var running by rememberSaveable { mutableStateOf(false) }
    val hostId = state.hostId
    // The Spaces of one run share a host, so one command fits them all.
    val command = spaceNetworkFixCommand(job?.network?.values?.firstOrNull { it?.firewallFix == true })
    if (running && hostId != null) HostCommandTerminal(hostId, command,
        // A fixed firewall checks again by itself; the terminal stays open to show what happened.
        onFinished = { ok -> if (ok) vm.recheckNetwork() },
        onDismiss = { running = false })
    SetupScreen(
        title = if (partners.size == 1 && first != null) stringResource(R.string.im_space_network_title, threadAssistantName(first))
            else stringResource(R.string.im_space_network_title_many),
        subtitle = stringResource(if (job?.firewallHint == true) R.string.im_space_network_help_ufw else R.string.im_space_network_help, computer),
        state = state, onDismissError = vm::dismissError,
        hero = { DeviceTile(HugeIcons.WifiOff01, size = 72.dp) },
        primary = SetupAction(stringResource(if (checking) R.string.im_space_net_checking else R.string.im_space_check_again),
            enabled = !checking, onClick = vm::recheckNetwork),
        secondary = SetupAction(stringResource(R.string.im_space_network_skip), onClick = vm::skipNetwork),
    ) {
        if (job?.firewallHint == true) SetupCard {
            Text(stringResource(R.string.im_space_network_run, computer), style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { CommandPreview(command) }
                IconButton(onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("command", command))) }
                }) { Icon(HugeIcons.Copy01, stringResource(R.string.im_space_copy_command)) }
            }
            Text(stringResource(R.string.im_space_network_note), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Already connected to the computer: run it from here, typing the password on the phone.
            FilledTonalButton(onClick = { running = true }, enabled = !checking && hostId != null,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.im_space_run_here)) }
        }
    }
}

/** What the partner can and cannot do now, and that it acts without asking (changeable). */
@Composable
internal fun ComputerSpaceDoneStep(vm: ComputerSetupVM, state: ComputerSetupState, onChat: (Assistant) -> Unit, onChangeMode: (Assistant) -> Unit) {
    val partners = sharePartners(state)
    val first = partners.firstOrNull()
    val computer = state.target?.name.orEmpty()
    SetupScreen(
        title = if (partners.size == 1 && first != null) stringResource(R.string.im_space_done_title, threadAssistantName(first))
            else stringResource(R.string.im_space_done_title_many),
        subtitle = stringResource(R.string.im_space_done_help, computer),
        state = state, onDismissError = vm::dismissError,
        hero = {
            Box(Modifier.size(84.dp)) {
                if (first != null) AssistantAvatar(first.name, first.avatar, Modifier.size(76.dp))
                Box(Modifier.align(Alignment.BottomEnd).size(32.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(HugeIcons.Cube, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        },
        primary = first?.let { partner ->
            SetupAction(stringResource(R.string.im_computer_go_chat_with, threadAssistantName(partner))) { onChat(partner) }
        },
    ) {
        SetupCard(spacing = 14.dp) {
            DoneLine(HugeIcons.Tick01, MaterialTheme.colorScheme.primary, stringResource(R.string.im_space_can),
                stringResource(R.string.im_space_can_body))
            DoneLine(HugeIcons.Cancel01, MaterialTheme.colorScheme.error, stringResource(R.string.im_space_cannot),
                stringResource(R.string.im_space_cannot_body, computer))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.im_space_mode_auto), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.im_space_mode_note), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (first != null) TextButton(onClick = { onChangeMode(first) }) { Text(stringResource(R.string.im_space_mode_change)) }
            }
        }
    }
}

@Composable
private fun DoneLine(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 1.dp).size(22.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = tint)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
