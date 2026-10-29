package me.ayuilos.miffan.ui.im.computer

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.model.withWorkspaceBinding
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.im.ImCardDivider
import me.ayuilos.miffan.ui.im.ImCardGroup
import me.ayuilos.miffan.ui.im.ImEmpty
import me.ayuilos.miffan.ui.im.ImListRow
import me.ayuilos.miffan.ui.im.ImPageBar
import me.ayuilos.miffan.ui.im.ImPartnerVM
import me.ayuilos.miffan.ui.im.ImSectionLabel
import me.ayuilos.miffan.ui.im.thread.threadAssistantName
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Cube
import me.rerere.hugeicons.stroke.Exchange01
import me.rerere.hugeicons.stroke.View
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import me.ayuilos.miffan.data.ai.computer.PartnerSpaces
import me.ayuilos.miffan.data.ai.computer.SpaceUsage
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * How one partner uses its computer: which computer, what it may do before asking, the tasks it set
 * up there, and changing or dropping the computer. The partner profile shows a one-line summary.
 */
@Composable
fun PartnerComputerPage(assistantId: Uuid, vm: ImPartnerVM = koinViewModel(key = assistantId.toString(), parameters = { parametersOf(assistantId) })) {
    val nav = LocalNavController.current
    val partner by vm.assistant.collectAsStateWithLifecycle()
    val computer by rememberPartnerComputer(assistantId)
    val tasks by rememberPartnerTasks(assistantId, computer?.hostId)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val failure = stringResource(R.string.im_p5_failed)
    var busy by remember { mutableStateOf(false) }
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var resizing by rememberSaveable { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val spaces: PartnerSpaces = koinInject()
    val resetDone = stringResource(R.string.im_space_reset_done)
    fun perform(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { snackbar.showSnackbar(failure) }
            finally { busy = false }
        }
    }
    val name = threadAssistantName(partner)
    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_partner_computer_title, name)) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val bound = computer
        val owner = partner
        if (owner == null) ImEmpty(stringResource(R.string.im_p5_missing_partner))
        else if (bound == null) Column(Modifier.padding(padding).padding(16.dp)) {
            ImCardGroup {
                ImListRow(HugeIcons.Computer, stringResource(R.string.im_computer_control), { nav.navigate(Screen.ComputerSetup(assistantId.toString())) },
                    supporting = stringResource(R.string.im_computer_connect_help))
            }
        }
        else if (bound.space) LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (bound.spaceHandoff == null) item("handoff-backfill") {
                LaunchedEffect(bound.workspaceId) { runCatching { spaces.handoffFolder(bound.workspaceId, owner.name) } }
            }
            item("space") { SpaceCard(bound.name, bound.hostId, bound.workspaceId, bound.spaceTier?.memoryMb, spaces) }
            item("size") {
                Spacer(Modifier.height(8.dp))
                ImCardGroup {
                    ImListRow(null, stringResource(R.string.im_space_tier_row), { resizing = true }, enabled = !busy,
                        supporting = bound.spaceTier?.summary())
                }
            }
            bound.spaceHandoff?.let { folder ->
                item("handoff") {
                    val context = LocalContext.current
                    val copied = stringResource(R.string.im_space_handoff_copied)
                    ImSectionLabel(stringResource(R.string.im_space_handoff_title))
                    Spacer(Modifier.height(8.dp))
                    ImCardGroup {
                        ImListRow(HugeIcons.Folder01, "~/$folder", {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Miffan", "~/$folder"))
                            scope.launch { snackbar.showSnackbar(copied) }
                        }, supporting = stringResource(R.string.im_space_handoff_detail, bound.name, name),
                            trailing = { Icon(HugeIcons.Copy01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
                    }
                }
            }
            item("mode") {
                ImSectionLabel(stringResource(R.string.im_partner_computer_mode))
                Spacer(Modifier.height(8.dp))
                ImCardGroup {
                    ComputerUseModeSelector(owner.computerUse, enabled = !busy, screen = false,
                        onMode = { mode -> perform { vm.update { it.copy(computerUse = mode) } } },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            item("manage") {
                Spacer(Modifier.height(16.dp))
                ImCardGroup {
                    ImListRow(null, stringResource(if (resetting) R.string.im_space_resetting else R.string.im_space_reset), { confirmReset = true },
                        supporting = stringResource(R.string.im_space_reset_detail), enabled = !busy, trailing = null)
                    ImCardDivider(start = 16.dp)
                    ImListRow(null, stringResource(R.string.im_space_delete), { confirmDelete = true }, enabled = !busy,
                        supporting = stringResource(R.string.im_space_delete_detail, name),
                        titleColor = MaterialTheme.colorScheme.error, trailing = null)
                }
            }
        }
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("computer") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                shape = RoundedCornerShape(14.dp), modifier = Modifier.size(48.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(HugeIcons.Computer, null) }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(bound.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!bound.screenEnabled) Text(stringResource(R.string.im_computers_screen_missing), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (bound.showsEntry) Button(onClick = { nav.navigate(Screen.PartnerScreen(assistantId.toString())) }, Modifier.weight(1f)) {
                                Icon(HugeIcons.View, null, Modifier.size(18.dp))
                                Text(stringResource(R.string.im_profile_view_screen), Modifier.padding(start = 6.dp))
                            }
                            FilledTonalButton(onClick = { nav.navigate(Screen.ComputerDetail(bound.hostId)) }, Modifier.weight(1f)) {
                                Text(stringResource(R.string.im_partner_computer_details))
                            }
                        }
                    }
                }
            }
            item("mode") {
                ImSectionLabel(stringResource(R.string.im_partner_computer_mode))
                Spacer(Modifier.height(8.dp))
                ImCardGroup {
                    ComputerUseModeSelector(owner.computerUse, enabled = !busy, screen = bound.screenEnabled,
                        onMode = { mode -> perform { vm.update { it.copy(computerUse = mode) } } },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            if (tasks.isNotEmpty()) item("tasks") {
                Spacer(Modifier.height(8.dp))
                PartnerTasksSection(bound.hostId, tasks, name)
            }
            item("manage") {
                Spacer(Modifier.height(16.dp))
                ImCardGroup {
                    ImListRow(HugeIcons.Exchange01, stringResource(R.string.im_computer_recheck_or_change),
                        { nav.navigate(Screen.ComputerSetup(assistantId.toString())) }, enabled = !busy)
                    ImCardDivider(start = 16.dp)
                    ImListRow(null, stringResource(R.string.im_computer_disconnect), { confirmDisconnect = true }, enabled = !busy,
                        titleColor = MaterialTheme.colorScheme.error, trailing = null)
                }
            }
        }
    }
    val bound = computer
    if (resizing && bound != null) {
        val mac = bound.platform == me.ayuilos.miffan.data.repository.RemoteScreenPlatform.MACOS
        val probe by produceState<me.ayuilos.miffan.data.ai.computer.CuaSpaceProbe?>(null, bound.hostId) {
            value = runCatching { spaces.probe(bound.hostId) }.getOrNull()
        }
        val used by produceState(0, bound.hostId) { value = runCatching { spaces.usedMemoryMb(bound.hostId) }.getOrDefault(0) }
        val current = bound.spaceTier ?: me.ayuilos.miffan.data.ai.computer.SpaceTier.DEFAULT
        val resized = me.ayuilos.miffan.data.ai.computer.SpaceTier.entries.associateWith { stringResource(R.string.im_space_resized, it.summary()) }
        SpaceTierSheet(
            current = current,
            recommended = null,
            capacity = me.ayuilos.miffan.data.ai.computer.SpaceCapacity.from(probe?.host),
            // This Space's own share is free to move.
            usedMemoryMb = (used - current.memoryMb).coerceAtLeast(0),
            count = 1,
            note = if (mac) stringResource(R.string.im_space_tier_mac_fixed) else null,
            enabled = !mac && !busy,
            onPick = { tier ->
                resizing = false
                if (tier != current) perform {
                    spaces.resize(bound.workspaceId, tier)
                    snackbar.showSnackbar(resized.getValue(tier))
                }
            },
            onDismiss = { resizing = false },
        )
    }
    if (confirmReset && bound != null) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text(stringResource(R.string.im_space_reset_confirm_title, name)) },
        text = { Text(stringResource(R.string.im_space_reset_confirm_body)) },
        confirmButton = {
            TextButton(onClick = {
                confirmReset = false
                perform {
                    resetting = true
                    try { spaces.reset(bound.workspaceId) } finally { resetting = false }
                    snackbar.showSnackbar(resetDone)
                }
            }) { Text(stringResource(R.string.im_space_reset), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (confirmDelete && bound != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.im_space_delete_confirm_title, name)) },
        text = { Text(stringResource(R.string.im_space_delete_confirm_body, bound.name)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                perform { spaces.delete(bound.workspaceId); nav.popBackStack() }
            }) { Text(stringResource(R.string.im_space_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (confirmDisconnect && bound != null) AlertDialog(onDismissRequest = { confirmDisconnect = false },
        title = { Text(stringResource(R.string.im_computer_disconnect_title, bound.name)) },
        text = { Text(stringResource(R.string.im_computer_disconnect_help)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDisconnect = false
                perform { vm.update { it.withWorkspaceBinding(null) }; nav.popBackStack() }
            }) { Text(stringResource(R.string.im_computer_disconnect_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.common_cancel)) } })
}

private sealed interface SpaceNetwork {
    data object Checking : SpaceNetwork
    data class Known(val check: me.ayuilos.miffan.data.ai.computer.CuaSpaceNetcheck) : SpaceNetwork
    data object Unknown : SpaceNetwork
}

/**
 * The partner's own computer: where it runs and whether it reaches the internet (checked on open). Offline,
 * the fix stays right here, so a setup left halfway can be finished any time.
 */
@Composable
private fun SpaceCard(host: String, hostId: String, workspaceId: String, memoryMb: Int?, spaces: PartnerSpaces) {
    var round by remember { mutableIntStateOf(0) }
    // The last answer shows at once while the page checks again; checking again on request starts blank.
    val known = remember(workspaceId) { spaces.lastNetcheck(workspaceId)?.let { SpaceNetwork.Known(it) } }
    val network by produceState<SpaceNetwork>(known ?: SpaceNetwork.Checking, workspaceId, round) {
        if (round > 0 || known == null) value = SpaceNetwork.Checking
        value = runCatching { spaces.netcheck(workspaceId) }
            .fold({ SpaceNetwork.Known(it) }, { SpaceNetwork.Unknown })
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(HugeIcons.Cube, null) }
                }
                Text(stringResource(R.string.im_space_profile_detail, host), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.im_space_detail_network), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (val state = network) {
                    SpaceNetwork.Checking -> Text(stringResource(R.string.im_space_net_checking), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SpaceNetwork.Unknown -> Text(stringResource(R.string.im_space_net_unknown), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is SpaceNetwork.Known -> Text(stringResource(if (state.check.online) R.string.im_space_net_ok else R.string.im_space_net_off),
                        color = if (state.check.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
            // It is not paused when idle, so what it holds now is worth seeing.
            val usage by produceState<SpaceUsage?>(null, workspaceId) { value = runCatching { spaces.usage(workspaceId) }.getOrNull() }
            usage?.let { now ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.im_space_detail_usage), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val limit = now.memoryLimitMb ?: memoryMb
                    val memory = if (limit != null) stringResource(R.string.im_space_usage_memory_of, spaceMemoryText(now.memoryMb), spaceMemoryText(limit))
                        else spaceMemoryText(now.memoryMb)
                    Text(now.cpuPercent?.let { stringResource(R.string.im_space_usage_value, memory, it) } ?: memory)
                }
            }
            val offline = (network as? SpaceNetwork.Known)?.check?.takeIf { !it.online }
            if (offline != null || network == SpaceNetwork.Unknown) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SpaceNetworkFixPanel(hostId, host, offline, checking = network == SpaceNetwork.Checking,
                    onRecheck = { round++ })
            }
        }
    }
}

/** Memory in the units people read: "486 MB", "4 GB", "1.5 GB". */
internal fun spaceMemoryText(mb: Int): String = when {
    mb < 1024 -> "$mb MB"
    mb % 1024 == 0 -> "${mb / 1024} GB"
    else -> String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1024.0)
}
