package me.ayuilos.miffan.ui.im.computer

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateUtils
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.ai.computer.ComputerSnapshot
import me.ayuilos.miffan.data.ai.computer.ComputerSnapshots
import me.ayuilos.miffan.data.ai.computer.SpaceFileDirection
import me.ayuilos.miffan.data.ai.computer.SpaceFileNote
import me.ayuilos.miffan.data.ai.computer.SpacePower
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.ComputerUseMode
import me.ayuilos.miffan.data.model.withWorkspaceBinding
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.im.ImCardDivider
import me.ayuilos.miffan.ui.im.ImCardGroup
import me.ayuilos.miffan.ui.im.ImEmpty
import me.ayuilos.miffan.ui.im.ImListRow
import me.ayuilos.miffan.ui.im.ImPageBar
import me.ayuilos.miffan.ui.im.ImPartnerVM
import me.ayuilos.miffan.ui.im.ImRowValue
import me.ayuilos.miffan.ui.im.ImSectionLabel
import me.ayuilos.miffan.ui.im.thread.threadAssistantName
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Exchange01
import me.rerere.hugeicons.stroke.Moon02
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
    val snapshots: ComputerSnapshots = koinInject()
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
    Scaffold(topBar = { ImPageBar(stringResource(if (computer?.space == true) R.string.im_space_screen_title else R.string.im_partner_computer_title, name)) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val bound = computer
        val owner = partner
        if (owner == null) ImEmpty(stringResource(R.string.im_p5_missing_partner))
        else if (bound == null) Column(Modifier.padding(padding).padding(16.dp)) {
            ImCardGroup {
                ImListRow(HugeIcons.Computer, stringResource(R.string.im_computer_control), { nav.navigate(Screen.ComputerSetup(assistantId.toString())) },
                    supporting = stringResource(R.string.im_computer_connect_help))
            }
        }
        else if (bound.space) {
            val live = rememberSpaceLive(bound.workspaceId, spaces)
            val snapshot by remember(snapshots, bound.workspaceId) { snapshots.latest(bound.workspaceId) }
                .collectAsStateWithLifecycle(initialValue = remember(bound.workspaceId) { snapshots.find(bound.workspaceId) })
            val notes by remember(spaces, bound.workspaceId) { spaces.fileNotes(bound.workspaceId) }.collectAsStateWithLifecycle(emptyList())
            val files = { nav.navigate(Screen.PartnerSpaceFiles(assistantId.toString())) }
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (bound.spaceHandoff == null) item("handoff-backfill") {
                    LaunchedEffect(bound.workspaceId) { runCatching { spaces.handoffFolder(bound.workspaceId, owner.name) } }
                }
                item("hero") {
                    SpaceHeroCard(name, owner.avatar, bound.name, snapshot, live.power,
                        onScreen = { nav.navigate(Screen.PartnerScreen(assistantId.toString())) }, onFiles = files)
                }
                item("tiles") { SpaceTiles(live, bound.spaceTier?.memoryMb, bound.spaceTier?.cpus) }
                val network = live.network
                val offline = (network as? SpaceNetwork.Known)?.check?.takeIf { !it.online }
                if (live.power == SpacePower.AWAKE && (offline != null || network == SpaceNetwork.Unknown)) item("network-fix") {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.padding(16.dp)) {
                            SpaceNetworkFixPanel(bound.hostId, bound.name, offline, checking = false, onRecheck = live.recheck)
                        }
                    }
                }
                item("size") {
                    ImCardGroup {
                        ImListRow(null, stringResource(R.string.im_space_tier_row), { resizing = true }, enabled = !busy,
                            supporting = bound.spaceTier?.summary(), trailing = { ImRowValue(stringResource(R.string.im_space_tier_adjust)) })
                    }
                }
                item("recent") { SpaceRecentFiles(name, notes, onAll = files) }
                item("mode") { SpaceModeCard(name, owner.computerUse, enabled = !busy, onMode = { mode -> perform { vm.update { it.copy(computerUse = mode) } } }) }
                item("manage") {
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
                perform { spaces.delete(bound.workspaceId); snapshots.clear(bound.workspaceId); nav.popBackStack() }
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

/** What the page has read of the Space; each field keeps its last answer while the next one is fetched. */
private data class SpaceReading(val power: SpacePower?, val usage: SpaceUsage?, val network: SpaceNetwork?)

private class SpaceLive(reading: SpaceReading, val recheck: () -> Unit) {
    val power = reading.power
    val usage = reading.usage
    val network = reading.network
}

/**
 * Reads the Space on open and again when the page comes back (from the screen or the files, where it may have
 * woken up or gone to sleep). Cached answers show first. Anything run inside a sleeping Space wakes it, so usage
 * and the network are read only once the host says it is awake.
 */
@Composable
private fun rememberSpaceLive(workspaceId: String, spaces: PartnerSpaces): SpaceLive {
    var round by remember { mutableIntStateOf(0) }
    var recheck by remember { mutableIntStateOf(0) }
    var handled by remember { mutableIntStateOf(0) }
    var seen by remember { mutableStateOf(false) }
    LifecycleResumeEffect(workspaceId) {
        if (seen) round++
        seen = true
        onPauseOrDispose { }
    }
    val reading by produceState(
        SpaceReading(spaces.lastPower(workspaceId), spaces.lastUsage(workspaceId), spaces.lastNetcheck(workspaceId)?.let { SpaceNetwork.Known(it) }),
        workspaceId, round, recheck,
    ) {
        val forced = recheck != handled
        handled = recheck
        val power = runCatching { spaces.power(workspaceId) }.getOrDefault(SpacePower.UNREACHABLE)
        value = value.copy(power = power)
        if (power != SpacePower.AWAKE) return@produceState
        runCatching { spaces.usage(workspaceId) }.getOrNull()?.let { value = value.copy(usage = it) }
        // The last answer stays while the page checks again; checking again on request starts blank.
        if (forced || value.network == null) value = value.copy(network = SpaceNetwork.Checking)
        value = value.copy(network = runCatching { spaces.netcheck(workspaceId) }.fold({ SpaceNetwork.Known(it) }, { SpaceNetwork.Unknown }))
    }
    return SpaceLive(reading) { recheck++ }
}

private val HeroShade = Color.Black.copy(alpha = 0.66f)

/** The mini box's last picture with whether it is awake, who it belongs to, and the two things to do with it. */
@Composable
private fun SpaceHeroCard(
    partner: String,
    avatar: Avatar,
    host: String,
    snapshot: ComputerSnapshot?,
    power: SpacePower?,
    onScreen: () -> Unit,
    onFiles: () -> Unit,
) {
    val asleep = power == SpacePower.ASLEEP
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 14.dp)) {
            Box(
                Modifier.fillMaxWidth().height(216.dp).clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHighest)
                    .clickable(onClickLabel = stringResource(R.string.im_space_screen_description, partner), onClick = onScreen),
            ) {
                if (snapshot != null) AsyncImage(snapshot.url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    colorFilter = if (asleep) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.3f) }) else null)
                else if (!asleep) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(HugeIcons.Computer, null, Modifier.size(30.dp), tint = colors.onSurfaceVariant)
                    Text(stringResource(R.string.im_space_shot_none_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.im_space_shot_none_hint), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                if (asleep) Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                    Icon(HugeIcons.Moon02, null, Modifier.size(30.dp), tint = Color.White)
                    Text(stringResource(R.string.im_space_state_asleep), style = MaterialTheme.typography.titleSmall, color = Color.White)
                    Text(stringResource(R.string.im_space_asleep_hint, partner), style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD5D8DE), textAlign = TextAlign.Center)
                }
                val breath by rememberInfiniteTransition(label = "awake").animateFloat(1f, 0.35f,
                    infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breath")
                Row(Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(12.dp)).background(HeroShade)
                    .padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).alpha(if (power == SpacePower.AWAKE) breath else 1f).background(when (power) {
                        SpacePower.AWAKE -> colors.tertiary
                        SpacePower.UNREACHABLE -> colors.error
                        else -> Color(0xFF9AA1AC)
                    }, CircleShape))
                    Text(stringResource(when (power) {
                        SpacePower.AWAKE -> R.string.im_space_state_awake
                        SpacePower.ASLEEP -> R.string.im_space_state_asleep
                        SpacePower.UNKNOWN -> R.string.im_space_state_unknown
                        SpacePower.UNREACHABLE -> R.string.im_space_state_unreachable
                        null -> R.string.im_space_state_checking
                    }), style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
                if (snapshot != null) {
                    val fresh = System.currentTimeMillis() - snapshot.at < DateUtils.MINUTE_IN_MILLIS
                    Text(if (fresh) stringResource(R.string.im_space_shot_now) else stringResource(R.string.im_space_shot_age, spaceTimeAgo(snapshot.at)),
                        Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(10.dp)).background(HeroShade)
                            .padding(horizontal = 9.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFFE9EBEF))
                }
            }
            Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AssistantAvatar(partner, avatar, Modifier.size(36.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.im_space_owner_title, partner), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.im_space_owner_detail, host), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScreen, Modifier.weight(1f).height(46.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(HugeIcons.View, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.im_profile_view_screen), Modifier.padding(start = 7.dp), maxLines = 1)
                }
                FilledTonalButton(onClick = onFiles, Modifier.weight(1f).height(46.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(HugeIcons.Exchange01, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.im_space_send_files), Modifier.padding(start = 7.dp), maxLines = 1)
                }
            }
        }
    }
}

/** Memory, CPU and network; a dash where the Space is not awake to ask. */
@Composable
private fun SpaceTiles(live: SpaceLive, memoryMb: Int?, cpus: Int?) {
    val awake = live.power == SpacePower.AWAKE
    val usage = live.usage.takeIf { awake }
    val limit = usage?.let { it.memoryLimitMb ?: memoryMb }
    val cpu = usage?.cpuPercent
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpaceTile(Modifier.weight(1f).fillMaxHeight(), stringResource(R.string.im_space_tile_memory), usage?.let { spaceMemoryText(it.memoryMb) },
            limit?.let { stringResource(R.string.im_space_tile_of, spaceMemoryText(it)) },
            usage?.let { now -> limit?.let { (now.memoryMb.toFloat() / it).coerceIn(0f, 1f) } })
        SpaceTile(Modifier.weight(1f).fillMaxHeight(), stringResource(R.string.im_space_tile_cpu), cpu?.let { "$it%" },
            cpus?.takeIf { cpu != null }?.let { stringResource(R.string.im_space_tile_cores, it) }, cpu?.let { it / 100f })
        val network = live.network ?: SpaceNetwork.Checking
        val check = (network as? SpaceNetwork.Known)?.check
        SpaceTile(Modifier.weight(1f).fillMaxHeight(), stringResource(R.string.im_space_detail_network),
            if (!awake) null else stringResource(when (network) {
                SpaceNetwork.Checking -> R.string.im_space_net_checking
                SpaceNetwork.Unknown -> R.string.im_space_net_unknown
                is SpaceNetwork.Known -> if (network.check.online) R.string.im_space_net_ok else R.string.im_space_net_off
            }),
            if (awake && check?.online == true && check.via == "share") stringResource(R.string.im_space_tile_via_share) else null,
            progress = null, bar = false,
            valueColor = when {
                !awake -> Color.Unspecified
                check == null -> MaterialTheme.colorScheme.onSurfaceVariant
                check.online -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.error
            },
            mark = if (awake && check?.online == true) HugeIcons.CheckmarkCircle02 else null)
    }
}

@Composable
private fun SpaceTile(
    modifier: Modifier,
    label: String,
    value: String?,
    sub: String?,
    progress: Float?,
    bar: Boolean = true,
    valueColor: Color = Color.Unspecified,
    mark: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, color = colors.surfaceContainerLow, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                mark?.let { Icon(it, null, Modifier.size(16.dp), tint = valueColor.takeOrElse { colors.primary }) }
                Text(value ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2,
                    color = if (value == null) colors.onSurfaceVariant else valueColor.takeOrElse { colors.onSurface })
            }
            Text(sub ?: "", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 2)
            Spacer(Modifier.weight(1f))
            if (bar) Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp)
                .clip(RoundedCornerShape(2.dp)).background(colors.surfaceContainerHighest)) {
                if (progress != null) Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(colors.primary))
            }
        }
    }
}

/** The last few files that went between the owner and the mini box, from the app's own notes (reading the folder would wake it). */
@Composable
private fun SpaceRecentFiles(partner: String, notes: List<SpaceFileNote>, onAll: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)) {
            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.im_space_recent_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = onAll) {
                    Text(stringResource(R.string.im_space_recent_all))
                    Icon(HugeIcons.ArrowRight01, null, Modifier.size(16.dp))
                }
            }
            if (notes.isEmpty()) Text(stringResource(R.string.im_space_recent_empty, partner), Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else notes.take(3).forEach { note ->
                val name = note.path.substringAfterLast('/')
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onAll).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SpaceFileTile(name, directory = false)
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(spaceFileCaption(note.direction, partner, spaceTimeAgo(note.at)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Ask first / go ahead / not allowed as one segmented choice, with what the chosen mode means. Going ahead confirms first. */
@Composable
private fun SpaceModeCard(partner: String, mode: ComputerUseMode, enabled: Boolean, onMode: (ComputerUseMode) -> Unit) {
    var confirmAuto by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.im_space_mode_title, partner), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = 10.dp).fillMaxWidth().alpha(if (enabled) 1f else 0.6f).clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceContainerHighest).padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(ComputerUseMode.ASK to R.string.im_space_mode_pick_ask, ComputerUseMode.AUTO to R.string.im_space_mode_pick_auto,
                    ComputerUseMode.OFF to R.string.im_space_mode_pick_off).forEach { (value, label) ->
                    val selected = mode == value
                    val shape = RoundedCornerShape(12.dp)
                    Box(Modifier.weight(1f).height(40.dp).then(if (selected) Modifier.shadow(1.dp, shape) else Modifier).clip(shape)
                        .background(if (selected) colors.surface else Color.Transparent)
                        .selectable(selected, enabled = enabled, role = Role.RadioButton,
                            onClick = { if (value == ComputerUseMode.AUTO && !selected) confirmAuto = true else onMode(value) }),
                        contentAlignment = Alignment.Center) {
                        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) colors.onSurface else colors.onSurfaceVariant)
                    }
                }
            }
            Text(when (mode) {
                ComputerUseMode.ASK -> stringResource(R.string.im_space_mode_ask_detail)
                ComputerUseMode.AUTO -> stringResource(R.string.im_space_mode_auto_detail)
                ComputerUseMode.OFF -> stringResource(R.string.im_space_mode_off_detail, partner)
            }, Modifier.padding(start = 2.dp, end = 2.dp, top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
    if (confirmAuto) AlertDialog(onDismissRequest = { confirmAuto = false },
        title = { Text(stringResource(R.string.computer_use_confirm_auto)) },
        text = { Text(stringResource(R.string.computer_use_auto_disclosure_no_screen)) },
        confirmButton = { TextButton(onClick = { confirmAuto = false; onMode(ComputerUseMode.AUTO) }) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmAuto = false }) { Text(stringResource(R.string.common_cancel)) } })
}

/** Memory in the units people read: "486 MB", "4 GB", "1.5 GB". */
internal fun spaceMemoryText(mb: Int): String = when {
    mb < 1024 -> "$mb MB"
    mb % 1024 == 0 -> "${mb / 1024} GB"
    else -> String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1024.0)
}
