package me.ayuilos.miffan.ui.im.computer

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.ai.computer.ComputerHasWorkspacesException
import me.ayuilos.miffan.data.ai.computer.ComputerStatusStore
import me.ayuilos.miffan.data.ai.computer.CuaUpdateState
import me.ayuilos.miffan.data.ai.computer.CuaUpdates
import me.ayuilos.miffan.data.ai.computer.ComputerUnreachableException
import me.ayuilos.miffan.data.ai.computer.ComputerWorkFiles
import me.ayuilos.miffan.data.ai.computer.KnownComputer
import me.ayuilos.miffan.data.ai.computer.PartnerComputers
import me.ayuilos.miffan.data.ai.computer.PartnerSpaces
import me.ayuilos.miffan.data.ai.computer.SpacePower
import me.ayuilos.miffan.data.ai.computer.SpaceTier
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.ComputerUseMode
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import me.ayuilos.miffan.data.repository.RemoteScreenRepository
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
import kotlin.uuid.Uuid

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
    val spaces: PartnerSpaces = koinInject()
    Scaffold(topBar = {
        ImPageBar(stringResource(R.string.im_computers_title), actions = {
            IconButton(onClick = { nav.navigate(Screen.ComputerSetup()) }) {
                Icon(HugeIcons.Add01, stringResource(R.string.im_computers_connect_new))
            }
        })
    }) { padding ->
        val list = computers ?: return@Scaffold
        if (list.isEmpty()) {
            ComputersEmpty(Modifier.padding(padding)) { nav.navigate(Screen.ComputerSetup()) }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.hostId }) { computer ->
                val desktop = computer.partnerIds.filter { it !in computer.spacePartnerIds }.mapNotNull { id -> assistants.find { it.id == id } }
                val boxes = computer.spacePartnerIds.mapNotNull { id -> assistants.find { it.id == id } }
                // Only what the app last saw; the list never asks a computer.
                val awake = boxes.filter { spaces.lastPower(it.workspaceId.toString()) == SpacePower.AWAKE }.map { it.id }.toSet()
                ComputerCard(computer, desktop, boxes, awake) { nav.navigate(Screen.ComputerDetail(computer.hostId)) }
            }
            item("add") {
                Surface(onClick = { nav.navigate(Screen.ComputerSetup()) }, color = Color.Transparent, shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Icon(HugeIcons.Add01, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.im_computers_connect_new), style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.im_computers_connect_new_help), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item("agent_tools") {
                Spacer(Modifier.height(4.dp))
                SetupCard(padding = 0.dp, spacing = 0.dp) {
                    DetailRow(stringResource(R.string.agent_tools_title), stringResource(R.string.agent_tools_entry_desc), enabled = true) {
                        nav.navigate(Screen.AgentTools)
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

/** One computer with who uses it, the two ways apart: the desktop itself, and partners' own mini boxes. */
@Composable
private fun ComputerCard(computer: KnownComputer, desktop: List<Assistant>, boxes: List<Assistant>, awake: Set<Uuid>, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, color = colors.surfaceContainerLow, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ComputerBadge(computer.platform, 46.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(computer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(computerSummary(computer, unused = desktop.isEmpty() && boxes.isEmpty()), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(HugeIcons.ArrowRight01, null, tint = colors.onSurfaceVariant)
            }
            if (desktop.isNotEmpty() || boxes.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp), color = colors.outlineVariant.copy(alpha = 0.5f))
                if (desktop.isNotEmpty()) UsageRow(stringResource(R.string.im_computers_row_desktop)) {
                    PartnerStack(desktop.take(3))
                    Text(desktop.map { threadAssistantName(it) }.joinToString(stringResource(R.string.im_computers_name_separator)),
                        Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (boxes.isNotEmpty()) UsageRow(stringResource(R.string.im_computers_row_boxes), Modifier.padding(top = if (desktop.isNotEmpty()) 4.dp else 0.dp)) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        boxes.forEach { BoxChip(it, it.id in awake) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** A partner with a mini box on the computer; a dot when it was awake the last time the app looked. */
@Composable
private fun BoxChip(partner: Assistant, awake: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(color = if (awake) colors.tertiaryContainer else colors.surfaceContainerHigh, shape = RoundedCornerShape(13.dp)) {
        Row(Modifier.padding(start = 3.dp, end = 10.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AssistantAvatar(partner.name, partner.avatar, Modifier.size(20.dp))
            Text(threadAssistantName(partner), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (awake) Box(Modifier.size(6.dp).background(colors.tertiary, CircleShape))
        }
    }
}

/** Small overlapping avatars of the partners using a computer's desktop. */
@Composable
private fun PartnerStack(partners: List<Assistant>) {
    val ring = MaterialTheme.colorScheme.surfaceContainerLow
    Box(Modifier.width(24.dp + 18.dp * (partners.size - 1)).height(24.dp)) {
        partners.forEachIndexed { index, partner ->
            AssistantAvatar(partner.name, partner.avatar, Modifier.offset(x = 18.dp * index).size(24.dp).border(2.dp, ring, CircleShape))
        }
    }
}

@Composable
private fun computerSummary(computer: KnownComputer, unused: Boolean): String = listOfNotNull(
    computer.platform.label(),
    stringResource(if (computer.screenEnabled) R.string.im_computers_screen_ready else R.string.im_computers_screen_missing),
    if (unused) stringResource(R.string.im_computers_unused) else null,
).joinToString(" · ")

private fun RemoteScreenPlatform.label(): String? = when (this) {
    RemoteScreenPlatform.MACOS -> "macOS"
    RemoteScreenPlatform.LINUX -> "Linux"
    RemoteScreenPlatform.WINDOWS -> "Windows"
    RemoteScreenPlatform.UNKNOWN -> null
}

private fun RemoteScreenPlatform.icon(): ImageVector = when (this) {
    RemoteScreenPlatform.MACOS -> AppleLogo
    RemoteScreenPlatform.LINUX -> LinuxLogo
    RemoteScreenPlatform.WINDOWS -> WindowsLogo
    RemoteScreenPlatform.UNKNOWN -> HugeIcons.Computer
}

@Composable
private fun ComputerBadge(platform: RemoteScreenPlatform, size: Dp) {
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(size * 0.3f)), contentAlignment = Alignment.Center) {
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
    val screens: RemoteScreenRepository = koinInject()
    val computer by rememberKnownComputer(hostId)
    val assistants = LocalSettings.current.assistants
    val workspaceList by remember(workspaces) { workspaces.listFlow() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var pickingBox by rememberSaveable { mutableStateOf(false) }
    var tasksOpen by rememberSaveable { mutableStateOf(false) }
    var choosingScreen by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    // Set when the computer could not be reached to remove Miffan's key: what to run on it later.
    var offlineCommand by rememberSaveable { mutableStateOf<String?>(null) }
    // Whether that computer also still trusts this phone for high-quality mode.
    var offlinePaired by rememberSaveable { mutableStateOf(false) }
    val hostList by remember(workspaces) { workspaces.listHostsFlow() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val remoteTasks: me.ayuilos.miffan.data.remotetask.RemoteTaskRepository = koinInject()
    val hostTasks by remember(hostId) { remoteTasks.tasksFlow(hostId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val taskCount = hostTasks.size
    val spaces: PartnerSpaces = koinInject()
    val boxWorkspaces = remember(workspaceList, hostId) { workspaceList.filter { it.isSpace && it.remoteHostId == hostId } }
    val boxLive by rememberMiniBoxLive(hostId, remember(boxWorkspaces) { boxWorkspaces.map { it.id } }, spaces)
    // Forgetting Miffan removes only the tasks this phone's key made; other phones' tasks keep running.
    val keyFingerprint by produceState<String?>(null, hostId) { value = remoteTasks.keyFingerprint(hostId) }
    val ownTaskCount = keyFingerprint?.let { fp -> hostTasks.count { remoteTasks.removedWithKey(it, fp) } } ?: taskCount
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
    // The name heads the page itself.
    Scaffold(topBar = { ImPageBar("") }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        if (current == null) return@Scaffold
        val partners = current.partnerIds.mapNotNull { id -> assistants.find { it.id == id } }
        // Partners with their own mini box here work in that, not on this computer's desktop.
        val desktop = partners.filter { it.id !in current.spacePartnerIds }
        val boxes = boxWorkspaces.mapNotNull { workspace -> assistants.find { it.workspaceId?.toString() == workspace.id }?.let { workspace to it } }
        val replaces: @Composable (Assistant) -> String? = { partner ->
            val bound = partner.workspaceId?.toString()?.let { id -> workspaceList.find { it.id == id } }
            when {
                bound == null -> null
                bound.isRemote -> stringResource(R.string.im_computer_replaces_computer)
                else -> stringResource(R.string.im_computer_replaces_phone)
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("identity") {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ComputerBadge(current.platform, 64.dp)
                    Text(current.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(current.platform.label(), current.address).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    if (current.screenEnabled) Button(onClick = {
                        when (desktop.size) {
                            0 -> nav.navigate(Screen.ComputerScreen(hostId))
                            1 -> nav.navigate(Screen.PartnerScreen(desktop.single().id.toString()))
                            else -> choosingScreen = true
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp)) {
                        Icon(HugeIcons.ComputerScreenShare, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.im_computer_detail_view_screen))
                    }
                }
            }
            item("screen") {
                SetupCard(padding = 0.dp, spacing = 0.dp) {
                    // On goes through the check first; off keeps the saved screen settings for later.
                    ComputerScreenSwitch(current.screenEnabled, enabled = !busy, onCheckedChange = { on ->
                        if (on) nav.navigate(Screen.ComputerSetup(hostId = hostId, screen = true))
                        else perform { screens.setScreenEnabled(hostId, false) }
                    }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp))
                    if (current.screenEnabled) {
                        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ComputerStatusRow(hostId, enabled = !busy) { nav.navigate(Screen.ComputerSetup(hostId = hostId, status = true)) }
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    val windows = current.platform == RemoteScreenPlatform.WINDOWS
                    DetailRow(stringResource(R.string.remote_task_section), when {
                        windows -> stringResource(R.string.remote_task_windows_title)
                        taskCount == 0 -> stringResource(R.string.im_computer_detail_tasks_empty)
                        else -> pluralStringResource(R.plurals.im_computer_detail_tasks_count, taskCount, taskCount)
                    }, enabled = true, expanded = tasksOpen) { tasksOpen = !tasksOpen }
                }
            }
            // Opened from the row above: syncing with the computer only starts when the user asks for the tasks.
            if (tasksOpen) item("tasks") {
                ComputerTasksSection(hostId, windows = current.platform == RemoteScreenPlatform.WINDOWS,
                    username = hostList.find { it.id == hostId }?.username.orEmpty(), title = false)
            }
            item("users_title") {
                SectionHeader(stringResource(R.string.im_computer_detail_users), stringResource(R.string.im_computer_detail_users_help))
            }
            item("users") {
                SetupCard(padding = 0.dp, spacing = 0.dp) {
                    desktop.forEach { partner ->
                        PartnerRow(partner, partner.computerUse, enabled = !busy) { nav.navigate(Screen.PartnerComputer(partner.id.toString())) }
                        HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    AddRow(stringResource(R.string.im_computer_detail_add_partner), enabled = !busy && assistants.any { it.id !in current.partnerIds }) { picking = true }
                }
            }
            // Windows has no mini boxes yet; one that was made before still shows.
            if (current.platform != RemoteScreenPlatform.WINDOWS || boxWorkspaces.isNotEmpty()) {
                item("boxes_title") {
                    SectionHeader(stringResource(R.string.im_computer_detail_boxes), stringResource(R.string.im_computer_detail_boxes_help))
                }
                item("boxes") {
                    SetupCard(padding = 0.dp, spacing = 0.dp) {
                        boxes.forEach { (workspace, partner) ->
                            MiniBoxRow(partner, workspace, boxLive.readings[workspace.id]) { nav.navigate(Screen.PartnerComputer(partner.id.toString())) }
                            HorizontalDivider(Modifier.padding(start = 92.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                        if (boxWorkspaces.isNotEmpty()) {
                            MiniBoxMemoryBar(MiniBoxMemory(
                                limitMb = boxWorkspaces.sumOf { it.spaceMemoryMb ?: SpaceTier.DEFAULT.memoryMb },
                                usedMb = miniBoxesUsedMb(boxWorkspaces.map { boxLive.readings[it.id] }),
                                totalMb = boxLive.hostMemoryMb,
                            ))
                            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                        AddRow(stringResource(R.string.im_computer_detail_add_box), enabled = !busy && assistants.any { it.id !in current.partnerIds }) { pickingBox = true }
                    }
                }
            }
            item("manage") {
                Spacer(Modifier.height(4.dp))
                SetupCard(padding = 0.dp, spacing = 0.dp) {
                    DetailRow(stringResource(R.string.im_computer_detail_connection), current.address, enabled = !busy) {
                        nav.navigate(Screen.ComputerSetup(hostId = hostId, edit = true))
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Surface(onClick = { deleting = true }, enabled = !busy, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Text(stringResource(R.string.im_computer_detail_delete), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (picking) PartnerPickerDialog(
            title = stringResource(R.string.im_computer_pick_partner),
            partners = assistants.filter { it.id !in current.partnerIds },
            supporting = replaces,
            footer = stringResource(if (current.screenEnabled) R.string.im_computer_bind_disclosure else R.string.im_computer_bind_disclosure_no_screen),
            onDismiss = { picking = false },
            onPick = { partner -> picking = false; perform { computers.bind(hostId, setOf(partner.id)) } },
        )
        // The setup flow takes it from here: it checks the computer, then offers the mini box to the partner.
        if (pickingBox) PartnerPickerDialog(
            title = stringResource(R.string.im_computer_pick_box_partner),
            partners = assistants.filter { it.id !in current.partnerIds },
            supporting = replaces,
            footer = null,
            onDismiss = { pickingBox = false },
            onPick = { partner -> pickingBox = false; nav.navigate(Screen.ComputerSetup(assistantId = partner.id.toString(), hostId = hostId, space = true)) },
        )
        if (choosingScreen) PartnerPickerDialog(
            title = stringResource(R.string.im_computer_pick_screen_partner),
            partners = desktop,
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
            else {
                val host = hostList.find { it.id == hostId }
                // The same machine and account saved again keeps working only if its key stays.
                val sibling = host?.let { h ->
                    hostList.firstOrNull { it.id != h.id && h.trustedHostKeySha256 != null && it.trustedHostKeySha256 == h.trustedHostKeySha256 &&
                        it.username == h.username && it.sshKeyId == h.sshKeyId }
                }
                val canForget = host?.sshKeyId != null && sibling == null
                var forget by rememberSaveable { mutableStateOf(true) }
                // The user's data: kept unless they choose otherwise, but shown with its size so it is not forgotten there.
                var deleteWorkFiles by rememberSaveable { mutableStateOf(false) }
                var workFiles by remember { mutableStateOf<ComputerWorkFiles?>(null) }
                if (canForget) LaunchedEffect(hostId) { workFiles = computers.workFiles(hostId) }
                AlertDialog(onDismissRequest = { deleting = false },
                    title = { Text(stringResource(R.string.im_computer_delete_title, current.name)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            val names = partners.map { threadAssistantName(it) }.joinToString(stringResource(R.string.im_computers_name_separator))
                            if (partners.isNotEmpty()) Text(stringResource(R.string.im_computer_delete_partners, names))
                            // Their own computers live on this one and go with it.
                            val spaceOwners = partners.filter { it.id in current.spacePartnerIds }
                            if (spaceOwners.isNotEmpty()) Text(stringResource(R.string.im_computer_delete_spaces,
                                spaceOwners.map { threadAssistantName(it) }.joinToString(stringResource(R.string.im_computers_name_separator))))
                            when {
                                canForget -> {
                                    DeleteOption(forget, { forget = it }, stringResource(R.string.im_computer_delete_forget),
                                        stringResource(R.string.im_computer_delete_forget_help))
                                    val files = workFiles
                                    if (forget && files != null) DeleteOption(deleteWorkFiles, { deleteWorkFiles = it },
                                        stringResource(R.string.im_computer_delete_work_files),
                                        files.bytes?.let { stringResource(R.string.im_computer_delete_work_files_help_size,
                                            Formatter.formatShortFileSize(LocalContext.current, it), files.path) }
                                            ?: stringResource(R.string.im_computer_delete_work_files_help, files.path))
                                }
                                sibling != null -> Text(stringResource(R.string.im_computer_delete_shared, sibling.name))
                                else -> Text(stringResource(R.string.im_computer_delete_help))
                            }
                            // Tasks go with Miffan's access; kept, they keep running and come back when the computer is added again.
                            if (canForget && forget) {
                                if (ownTaskCount > 0) Text(stringResource(R.string.remote_task_delete_removed, ownTaskCount),
                                    style = MaterialTheme.typography.bodyMedium)
                                if (taskCount > ownTaskCount) Text(stringResource(R.string.remote_task_delete_others_kept, taskCount - ownTaskCount),
                                    style = MaterialTheme.typography.bodyMedium)
                            } else if (taskCount > 0) Text(stringResource(R.string.remote_task_delete_kept, taskCount),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            deleting = false
                            val revoke = canForget && forget
                            forgetting = revoke
                            perform {
                                try { computers.delete(hostId, forgetOnComputer = revoke, deleteWorkFiles = revoke && deleteWorkFiles) }
                                catch (error: ComputerHasWorkspacesException) {
                                    deleting = true // Shows the blocked explanation with the fresh count.
                                } catch (error: ComputerUnreachableException) {
                                    offlineCommand = error.manualCommand
                                    offlinePaired = host?.streamCertificateSha256 != null
                                } finally { forgetting = false }
                            }
                        }) { Text(stringResource(R.string.im_computer_delete_confirm), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.common_cancel)) } })
            }
        }
        if (forgetting) AlertDialog(onDismissRequest = {}, confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text(stringResource(R.string.im_computer_forgetting))
                }
            })
        offlineCommand?.let { command ->
            AlertDialog(onDismissRequest = { offlineCommand = null },
                title = { Text(stringResource(R.string.im_computer_delete_offline_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.im_computer_delete_offline))
                        val context = LocalContext.current
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { CommandPreview(command) }
                            FilledTonalButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText("Miffan", command))
                            }) { Text(stringResource(R.string.im_computer_copy)) }
                        }
                        if (offlinePaired) Text(stringResource(R.string.im_computer_delete_offline_sunshine))
                        Text(stringResource(R.string.im_computer_delete_offline_later), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        offlineCommand = null
                        perform { computers.delete(hostId, forgetOnComputer = false) }
                    }) { Text(stringResource(R.string.im_computer_delete_phone_only), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { offlineCommand = null }) { Text(stringResource(R.string.common_cancel)) } })
        }
    }
}

@Composable
private fun DeleteOption(checked: Boolean, onCheckedChange: (Boolean) -> Unit, title: String, help: String) {
    Surface(onClick = { onCheckedChange(!checked) }, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun ComputerUseMode.chipLabel(): Int = when (this) {
    ComputerUseMode.ASK -> R.string.computer_use_mode_ask
    ComputerUseMode.AUTO -> R.string.im_computer_detail_mode_auto
    ComputerUseMode.OFF -> R.string.computer_use_mode_off
}

/**
 * "Computer status" on a computer's page: what its last check found and any cua-driver update, read
 * from this phone without connecting; opens the status page.
 */
@Composable
private fun ComputerStatusRow(hostId: String, enabled: Boolean, onClick: () -> Unit) {
    val statuses: ComputerStatusStore = koinInject()
    val updates: CuaUpdates = koinInject()
    val record = statuses.records.collectAsStateWithLifecycle().value[hostId]
    val update = updates.states.collectAsStateWithLifecycle().value[hostId]
    val error = MaterialTheme.colorScheme.error
    val primary = MaterialTheme.colorScheme.primary
    val (line, color) = when {
        update is CuaUpdateState.Running -> stringResource(R.string.im_computer_status_row_updating) to primary
        update is CuaUpdateState.Waiting -> stringResource(R.string.im_computer_status_row_waiting,
            update.partner.ifBlank { stringResource(R.string.im_computer_update_partner) }) to primary
        update is CuaUpdateState.Failed && !update.stillWorks -> stringResource(R.string.im_computer_status_row_update_failed) to error
        record == null -> null to null
        record.pending > 0 -> pluralStringResource(R.plurals.im_computer_status_row_pending, record.pending, record.pending) to error
        record.updateTo != null && update !is CuaUpdateState.Updated -> stringResource(R.string.im_computer_status_row_update) to primary
        else -> stringResource(R.string.im_computer_status_row_fine) to null
    }
    val checked = record?.let { checkedText(it.checkedAt) }
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.im_computer_detail_check))
                when {
                    line == null -> Text(stringResource(R.string.im_computer_detail_check_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    color == null -> Text(listOfNotNull(line, checked).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).background(color, CircleShape))
                            Text(line, style = MaterialTheme.typography.bodyMedium, color = color)
                        }
                        checked?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Checked just now" / "Checked 10 minutes ago" / "Checked yesterday". */
@Composable
private fun checkedText(at: Long): String {
    val now = System.currentTimeMillis()
    return if (now - at < android.text.format.DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.im_computer_status_checked_now)
    else stringResource(R.string.im_computer_status_checked_at,
        android.text.format.DateUtils.getRelativeTimeSpanString(at, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString())
}

@Composable
private fun DetailRow(title: String, supporting: String?, enabled: Boolean, expanded: Boolean? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title)
                supporting?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(HugeIcons.ArrowRight01, null, Modifier.rotate(if (expanded == true) 90f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A section's name with one line on what it is for. */
@Composable
private fun SectionHeader(title: String, help: String) {
    Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(HugeIcons.Add01, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A partner using the desktop, with how far it may go on its own. */
@Composable
private fun PartnerRow(partner: Assistant, mode: ComputerUseMode, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AssistantAvatar(partner.name, partner.avatar, Modifier.size(36.dp))
            Text(threadAssistantName(partner), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Going ahead on its own is what stands out; asking first and being off are the quiet ones.
            Surface(color = if (mode == ComputerUseMode.AUTO) colors.tertiaryContainer else colors.surfaceContainerHighest,
                contentColor = if (mode == ComputerUseMode.AUTO) colors.onTertiaryContainer else colors.onSurfaceVariant, shape = RoundedCornerShape(11.dp)) {
                Text(stringResource(mode.chipLabel()), Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
            }
            Icon(HugeIcons.ArrowRight01, null, tint = colors.onSurfaceVariant)
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
            onSettings = { vm.handBackToPartner(); nav.navigate(Screen.ComputerSetup(hostId = hostId, status = true)) },
            onBack = { vm.handBackToPartner(); nav.popBackStack() },
            failureText = { computerErrorMessage(resources, it) },
        )
    }
}
