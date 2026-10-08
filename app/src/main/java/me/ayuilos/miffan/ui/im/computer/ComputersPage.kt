package me.ayuilos.miffan.ui.im.computer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.ai.computer.ComputerHasWorkspacesException
import me.ayuilos.miffan.data.ai.computer.KnownComputer
import me.ayuilos.miffan.data.ai.computer.PartnerComputers
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.ComputerUseMode
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.ImPageBar
import me.ayuilos.miffan.ui.im.thread.threadAssistantName
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenScaffold
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenArgs
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenVM
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Apple
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.ComputerScreenShare
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
internal fun rememberKnownComputers(): State<List<KnownComputer>?> {
    val computers: PartnerComputers = koinInject()
    return remember(computers) { computers.observeAll() }.collectAsStateWithLifecycle(initialValue = null)
}

@Composable
private fun rememberKnownComputer(hostId: String): State<KnownComputer?> {
    val computers: PartnerComputers = koinInject()
    return remember(computers, hostId) { computers.observeComputer(hostId) }.collectAsStateWithLifecycle(initialValue = null)
}

/** Easy chat's list of computers, reached from the Me tab. */
@Composable
fun ComputersPage() {
    val nav = LocalNavController.current
    val computers by rememberKnownComputers()
    val assistants = LocalSettings.current.assistants
    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_computers_title)) }) { padding ->
        val list = computers ?: return@Scaffold
        if (list.isEmpty()) {
            ComputersEmpty(Modifier.padding(padding)) { nav.navigate(Screen.ComputerSetup()) }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("computers") {
                SetupCard(padding = 0.dp, spacing = 0.dp) {
                    list.forEachIndexed { index, computer ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 72.dp))
                        ComputerListRow(computer, computer.partnerIds.mapNotNull { id -> assistants.find { it.id == id } }) {
                            nav.navigate(Screen.ComputerDetail(computer.hostId))
                        }
                    }
                }
            }
            item("add") {
                Surface(onClick = { nav.navigate(Screen.ComputerSetup()) }, color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(HugeIcons.Add01, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(stringResource(R.string.im_computers_connect_new), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComputersEmpty(modifier: Modifier, onConnect: () -> Unit) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(HugeIcons.Computer, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(stringResource(R.string.im_computers_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.im_computers_empty_help), textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onConnect, modifier = Modifier.padding(top = 12.dp).heightIn(min = 52.dp)) {
            Text(stringResource(R.string.im_computers_connect_first))
        }
    }
}

@Composable
private fun ComputerListRow(computer: KnownComputer, partners: List<Assistant>, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ComputerBadge(computer.platform, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(computer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(computerSummary(computer), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (partners.isNotEmpty()) PartnerStack(partners.take(3))
                    Text(partnersUsing(partners), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Small overlapping avatars of the partners using a computer. */
@Composable
private fun PartnerStack(partners: List<Assistant>) {
    val ring = MaterialTheme.colorScheme.surfaceContainerLow
    Box(Modifier.width(20.dp + 14.dp * (partners.size - 1)).height(20.dp)) {
        partners.forEachIndexed { index, partner ->
            AssistantAvatar(partner.name, partner.avatar,
                Modifier.offset(x = 14.dp * index).size(20.dp).border(1.5.dp, ring, CircleShape))
        }
    }
}

@Composable
private fun partnersUsing(partners: List<Assistant>): String =
    if (partners.isEmpty()) stringResource(R.string.im_computers_unused)
    else stringResource(R.string.im_computers_used_by,
        partners.map { threadAssistantName(it) }.joinToString(stringResource(R.string.im_computers_name_separator)))

@Composable
private fun computerSummary(computer: KnownComputer): String = listOfNotNull(
    computer.platform.label(),
    stringResource(if (computer.screenEnabled) R.string.im_computers_screen_ready else R.string.im_computers_screen_missing),
).joinToString(" · ")

private fun RemoteScreenPlatform.label(): String? = when (this) {
    RemoteScreenPlatform.MACOS -> "macOS"
    RemoteScreenPlatform.LINUX -> "Linux"
    RemoteScreenPlatform.UNKNOWN -> null
}

private fun RemoteScreenPlatform.icon(): ImageVector = if (this == RemoteScreenPlatform.MACOS) HugeIcons.Apple else HugeIcons.Computer

@Composable
private fun ComputerBadge(platform: RemoteScreenPlatform, size: Dp) {
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Icon(platform.icon(), null, Modifier.size(size * 0.5f), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** One computer: its screen, the partners using it, its checks and connection, and deleting it. */
@Composable
fun ComputerDetailPage(hostId: String) {
    val nav = LocalNavController.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val computers: PartnerComputers = koinInject()
    val workspaces: WorkspaceRepository = koinInject()
    val computer by rememberKnownComputer(hostId)
    val assistants = LocalSettings.current.assistants
    val workspaceList by remember(workspaces) { workspaces.listFlow() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var choosingScreen by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(computer) {
        if (computer != null) loaded = true
        // Deleted, here or in the professional interface.
        else if (loaded) nav.popBackStack()
    }
    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                snackbar.showSnackbar(computerErrorMessage(resources, error))
            } finally { busy = false }
        }
    }
    val current = computer
    Scaffold(topBar = { ImPageBar(current?.name.orEmpty()) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        if (current == null) return@Scaffold
        val partners = current.partnerIds.mapNotNull { id -> assistants.find { it.id == id } }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("identity") {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComputerBadge(current.platform, 72.dp)
                    Text(current.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(current.platform.label(), current.address).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    if (current.screenEnabled) Button(onClick = {
                        when (partners.size) {
                            0 -> nav.navigate(Screen.ComputerScreen(hostId))
                            1 -> nav.navigate(Screen.PartnerScreen(partners.single().id.toString()))
                            else -> choosingScreen = true
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp)) {
                        Icon(HugeIcons.ComputerScreenShare, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.im_computer_detail_view_screen))
                    }
                }
            }
            if (!current.screenEnabled) item("screen_setup") {
                SetupCard(padding = 0.dp, spacing = 0.dp) {
                    DetailRow(stringResource(R.string.im_computer_detail_set_up_screen),
                        stringResource(R.string.im_computer_detail_set_up_screen_help), enabled = !busy) {
                        nav.navigate(Screen.ComputerSetup(hostId = hostId))
                    }
                }
            }
            item("users_title") {
                Text(stringResource(R.string.im_computer_detail_users), Modifier.padding(start = 4.dp, top = 8.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item("users") {
                SetupCard(padding = 0.dp, spacing = 0.dp) {
                    partners.forEach { partner ->
                        PartnerRow(partner, trailing = stringResource(partner.computerUse.label()), enabled = !busy) {
                            nav.navigate(Screen.PartnerProfile(partner.id.toString()))
                        }
                        HorizontalDivider(Modifier.padding(start = 68.dp))
                    }
                    Surface(onClick = { picking = true }, enabled = !busy && assistants.any { it.id !in current.partnerIds },
                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                Icon(HugeIcons.Add01, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Text(stringResource(R.string.im_computer_detail_add_partner), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item("manage") {
                SetupCard(padding = 0.dp, spacing = 0.dp) {
                    DetailRow(stringResource(R.string.im_computer_detail_check), stringResource(R.string.im_computer_detail_check_help),
                        enabled = !busy) { nav.navigate(Screen.ComputerSetup(hostId = hostId)) }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    DetailRow(stringResource(R.string.im_computer_detail_connection), current.address, enabled = !busy) {
                        nav.navigate(Screen.ComputerSetup(hostId = hostId, edit = true))
                    }
                }
            }
            item("delete") {
                Surface(onClick = { deleting = true }, enabled = !busy, color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large) {
                    Text(stringResource(R.string.im_computer_detail_delete), Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (picking) PartnerPickerDialog(
            title = stringResource(R.string.im_computer_pick_partner),
            partners = assistants.filter { it.id !in current.partnerIds },
            supporting = { partner ->
                val bound = partner.workspaceId?.toString()?.let { id -> workspaceList.find { it.id == id } }
                when {
                    bound == null -> null
                    bound.isRemote -> stringResource(R.string.im_computer_replaces_computer)
                    else -> stringResource(R.string.im_computer_replaces_phone)
                }
            },
            footer = stringResource(R.string.im_computer_bind_disclosure),
            onDismiss = { picking = false },
            onPick = { partner -> picking = false; perform { computers.bind(hostId, setOf(partner.id)) } },
        )
        if (choosingScreen) PartnerPickerDialog(
            title = stringResource(R.string.im_computer_pick_screen_partner),
            partners = partners,
            supporting = { null },
            footer = null,
            onDismiss = { choosingScreen = false },
            onPick = { partner -> choosingScreen = false; nav.navigate(Screen.PartnerScreen(partner.id.toString())) },
            extra = stringResource(R.string.im_computer_screen_only) to {
                choosingScreen = false
                nav.navigate(Screen.ComputerScreen(hostId))
            },
        )
        if (deleting) {
            if (current.otherWorkspaceCount > 0) AlertDialog(onDismissRequest = { deleting = false },
                title = { Text(stringResource(R.string.im_computer_delete_blocked_title)) },
                text = { Text(stringResource(R.string.im_computer_delete_blocked, current.otherWorkspaceCount)) },
                confirmButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.common_confirm)) } })
            else AlertDialog(onDismissRequest = { deleting = false },
                title = { Text(stringResource(R.string.im_computer_delete_title, current.name)) },
                text = {
                    val names = partners.map { threadAssistantName(it) }.joinToString(stringResource(R.string.im_computers_name_separator))
                    Text(listOfNotNull(
                        names.takeIf { partners.isNotEmpty() }?.let { stringResource(R.string.im_computer_delete_partners, it) },
                        stringResource(R.string.im_computer_delete_help),
                    ).joinToString("\n\n"))
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleting = false
                        perform {
                            try { computers.delete(hostId) } catch (error: ComputerHasWorkspacesException) {
                                deleting = true // Shows the blocked explanation with the fresh count.
                            }
                        }
                    }) { Text(stringResource(R.string.im_computer_delete_confirm), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.common_cancel)) } })
        }
    }
}

private fun ComputerUseMode.label(): Int = when (this) {
    ComputerUseMode.ASK -> R.string.computer_use_mode_ask
    ComputerUseMode.AUTO -> R.string.computer_use_mode_auto
    ComputerUseMode.OFF -> R.string.computer_use_mode_off
}

@Composable
private fun DetailRow(title: String, supporting: String?, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title)
                supporting?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PartnerRow(partner: Assistant, trailing: String?, enabled: Boolean, supporting: String? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AssistantAvatar(partner.name, partner.avatar, Modifier.size(36.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(threadAssistantName(partner), maxLines = 1, overflow = TextOverflow.Ellipsis)
                supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            trailing?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PartnerPickerDialog(
    title: String,
    partners: List<Assistant>,
    supporting: @Composable (Assistant) -> String?,
    footer: String?,
    onDismiss: () -> Unit,
    onPick: (Assistant) -> Unit,
    extra: Pair<String, () -> Unit>? = null,
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 420.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(partners, key = { it.id.toString() }) { partner ->
                    Surface(onClick = { onPick(partner) }, color = Color.Transparent, shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            AssistantAvatar(partner.name, partner.avatar, Modifier.size(36.dp))
                            Column(Modifier.weight(1f)) {
                                Text(threadAssistantName(partner), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                supporting(partner)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                extra?.let { (label, onClick) ->
                    item("extra") {
                        Surface(onClick = onClick, color = Color.Transparent, shape = MaterialTheme.shapes.medium) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                    Icon(HugeIcons.ComputerScreenShare, null, tint = MaterialTheme.colorScheme.primary)
                                }
                                Text(label)
                            }
                        }
                    }
                }
            }
            footer?.let {
                Text(it, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}

/** A computer's screen opened from "my computers" with no partner: the same screen page, without the partner strip. */
@Composable
fun ComputerScreenPage(hostId: String) {
    val nav = LocalNavController.current
    val resources = LocalResources.current
    val computer by rememberKnownComputer(hostId)
    val current = computer
    val workspaceId = current?.workspaceId?.takeIf { current.screenEnabled }
    if (workspaceId == null) {
        Scaffold(topBar = { ImPageBar(stringResource(R.string.im_computer_screen)) }) { padding ->
            if (current != null) Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.im_computer_setup_needed))
                Button(onClick = { nav.navigate(Screen.ComputerSetup(hostId = hostId)) }) {
                    Text(stringResource(R.string.im_computer_detail_check))
                }
            }
        }
        return
    }
    key(workspaceId) {
        val vm: RemoteScreenVM = koinViewModel(key = workspaceId, parameters = { parametersOf(RemoteScreenArgs(workspaceId)) })
        RemoteScreenScaffold(
            vm = vm,
            title = current.name,
            settingsLabel = stringResource(R.string.im_computer_detail_check),
            onSettings = { vm.handBackToPartner(); nav.navigate(Screen.ComputerSetup(hostId = hostId)) },
            onBack = { vm.handBackToPartner(); nav.popBackStack() },
            failureText = { computerErrorMessage(resources, it) },
        )
    }
}
