package me.ayuilos.miffan.ui.im.computer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import me.rerere.hugeicons.stroke.Cube
import me.rerere.hugeicons.stroke.Exchange01
import me.rerere.hugeicons.stroke.View
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import me.ayuilos.miffan.data.ai.computer.PartnerSpaces
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
            item("space") { SpaceCard(bound.name, bound.workspaceId, spaces) }
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
                    ImListRow(null, stringResource(if (busy) R.string.im_space_resetting else R.string.im_space_reset), { confirmReset = true },
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
    if (confirmReset && bound != null) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text(stringResource(R.string.im_space_reset_confirm_title, name)) },
        text = { Text(stringResource(R.string.im_space_reset_confirm_body)) },
        confirmButton = {
            TextButton(onClick = {
                confirmReset = false
                perform { spaces.reset(bound.workspaceId); snackbar.showSnackbar(resetDone) }
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
    data class Known(val online: Boolean) : SpaceNetwork
    data object Unknown : SpaceNetwork
}

/** The partner's own computer: where it runs and whether it reaches the internet (checked on open). */
@Composable
private fun SpaceCard(host: String, workspaceId: String, spaces: PartnerSpaces) {
    val network by produceState<SpaceNetwork>(SpaceNetwork.Checking, workspaceId) {
        value = runCatching { spaces.netcheck(workspaceId) }
            .fold({ SpaceNetwork.Known(it.online) }, { SpaceNetwork.Unknown })
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
                    is SpaceNetwork.Known -> Text(stringResource(if (state.online) R.string.im_space_net_ok else R.string.im_space_net_off),
                        color = if (state.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
