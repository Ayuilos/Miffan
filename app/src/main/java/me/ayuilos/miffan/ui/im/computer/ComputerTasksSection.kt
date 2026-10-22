package me.ayuilos.miffan.ui.im.computer

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.db.entity.RemoteTaskEntity
import me.ayuilos.miffan.data.db.entity.RemoteTaskRunEntity
import me.ayuilos.miffan.data.remotetask.RemoteTaskRepository
import me.ayuilos.miffan.data.remotetask.TaskKind
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.partnerName
import me.ayuilos.miffan.ui.im.thread.ThreadAvatar
import me.ayuilos.miffan.ui.remotetask.clock
import me.ayuilos.miffan.ui.remotetask.isRunFailure
import me.ayuilos.miffan.ui.remotetask.runStatusLabel
import me.ayuilos.miffan.ui.remotetask.scheduleLabel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.UserLock01
import me.rerere.hugeicons.stroke.WifiDisconnected01
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * A computer's scheduled tasks on its detail page. Opening the page syncs once: the app never pulls
 * in the background, so everything here says when it was last brought over and what that means.
 */
@Composable
internal fun ComputerTasksSection(hostId: String, windows: Boolean, username: String) {
    val nav = LocalNavController.current
    val repository: RemoteTaskRepository = koinInject()
    val scope = rememberCoroutineScope()
    val tasks by remember(hostId) {
        repository.tasksFlow(hostId).map { list -> list.sortedBy { repository.decodeMeta(it)?.createdAt } }
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val host by remember(hostId) { repository.hostFlow(hostId) }.collectAsStateWithLifecycle(initialValue = null)
    val latest by remember(hostId) { repository.latestRunsFlow(hostId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var syncing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var lingerCommand by rememberSaveable { mutableStateOf(false) }
    fun sync() {
        if (syncing || windows) return
        syncing = true
        scope.launch {
            failed = runCatching { repository.sync(hostId) }.isFailure
            syncing = false
        }
    }
    LaunchedEffect(hostId) { sync() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.remote_task_section), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val synced = host?.lastSyncedAt
            if (!windows) Text(when {
                syncing -> stringResource(R.string.remote_task_syncing)
                synced != null -> stringResource(R.string.remote_task_synced_at, timeOf(synced))
                else -> ""
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            windows -> InfoCard(HugeIcons.Clock01, stringResource(R.string.remote_task_windows_title), stringResource(R.string.remote_task_windows_body))
            host?.scheduler == "none" -> InfoCard(HugeIcons.Clock01, stringResource(R.string.remote_task_no_scheduler_title),
                stringResource(R.string.remote_task_no_scheduler_body))
            else -> {
                if (failed && !syncing) InfoCard(HugeIcons.WifiDisconnected01, stringResource(R.string.remote_task_offline_title),
                    host?.lastSyncedAt?.let { stringResource(R.string.remote_task_offline_body_since, dayLabel(it)) }
                        ?: stringResource(R.string.remote_task_offline_body),
                    action = stringResource(R.string.remote_task_retry) to { sync() })
                if (host?.scheduler == "systemd" && host?.linger == false) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(HugeIcons.UserLock01, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(stringResource(R.string.remote_task_linger_title), style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                                    Text(stringResource(R.string.remote_task_linger_body), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                                }
                            }
                            FilledTonalButton(onClick = {
                                scope.launch { lingerCommand = runCatching { !repository.enableLinger(hostId) }.getOrDefault(true) }
                            }) { Text(stringResource(R.string.remote_task_linger_enable)) }
                        }
                    }
                    if (lingerCommand) LingerCommandCard(username) { lingerCommand = false; sync() }
                }
                if (tasks.isEmpty()) SetupCard {
                    Text(stringResource(R.string.remote_task_empty), style = MaterialTheme.typography.bodyMedium)
                } else SetupCard(padding = 0.dp, spacing = 0.dp) {
                    tasks.forEachIndexed { index, task ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 70.dp))
                        TaskRow(task, latest.find { it.taskId == task.taskId }, repository) {
                            nav.navigate(Screen.RemoteTaskDetail(hostId, task.taskId))
                        }
                    }
                }
                Hint(HugeIcons.Refresh, stringResource(R.string.remote_task_hint_sync))
                if (host?.scheduler == "launchd") Hint(HugeIcons.Moon02, stringResource(R.string.remote_task_hint_mac))
                Hint(HugeIcons.Message01, stringResource(R.string.remote_task_hint_ask_partner))
            }
        }
    }
}

/** Tasks on [hostId] that this partner set up, as last synced; the computer's page lists everyone's. */
@Composable
internal fun rememberPartnerTasks(assistantId: Uuid, hostId: String?): State<List<RemoteTaskEntity>> {
    val repository: RemoteTaskRepository = koinInject()
    return remember(repository, assistantId, hostId) {
        hostId?.let { repository.tasksFlow(it) }?.map { tasks ->
            tasks.map { it to repository.decodeMeta(it) }.filter { (_, meta) -> meta?.owner?.assistantId == assistantId }
                .sortedBy { (_, meta) -> meta?.createdAt }.map { it.first }
        }
            ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
}

/**
 * The partner profile's view of [rememberPartnerTasks]: the first few, then a way to the computer's
 * page. It does not sync; results come over when the app opens. Callers leave it out when empty.
 */
@Composable
internal fun PartnerTasksSection(hostId: String, tasks: List<RemoteTaskEntity>, partnerName: String) {
    val nav = LocalNavController.current
    val repository: RemoteTaskRepository = koinInject()
    val host by remember(hostId) { repository.hostFlow(hostId) }.collectAsStateWithLifecycle(initialValue = null)
    val latest by remember(hostId) { repository.latestRunsFlow(hostId) }.collectAsStateWithLifecycle(initialValue = emptyList())

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.remote_task_partner_section), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            host?.lastSyncedAt?.let {
                Text(stringResource(R.string.remote_task_synced_at, timeOf(it)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SetupCard(padding = 0.dp, spacing = 0.dp) {
            // Problems first, so the folded list never hides a failing task behind "see all".
            val ordered = tasks.sortedByDescending { task ->
                task.enabled && (!task.timerOk || latest.find { it.taskId == task.taskId }?.let { isRunFailure(it.status) } == true)
            }
            ordered.take(PARTNER_TASKS_SHOWN).forEachIndexed { index, task ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 70.dp))
                TaskRow(task, latest.find { it.taskId == task.taskId }, repository, showOwner = false) {
                    nav.navigate(Screen.RemoteTaskDetail(hostId, task.taskId))
                }
            }
            if (tasks.size > PARTNER_TASKS_SHOWN) {
                HorizontalDivider(Modifier.padding(start = 16.dp))
                Surface(onClick = { nav.navigate(Screen.ComputerDetail(hostId)) }, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.remote_task_partner_see_all, tasks.size), Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.primary)
                        Icon(HugeIcons.ArrowRight01, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text(stringResource(R.string.remote_task_partner_hint, partnerName), Modifier.padding(horizontal = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val PARTNER_TASKS_SHOWN = 3

/**
 * [showOwner] puts the partner that set the task up in front, with the task kind as a corner badge, so
 * a computer's list tells partners apart; a partner's own list leaves it out. Tasks whose partner is
 * gone keep the plain kind icon.
 */
@Composable
private fun TaskRow(task: RemoteTaskEntity, last: RemoteTaskRunEntity?, repository: RemoteTaskRepository, showOwner: Boolean = true,
    onClick: () -> Unit) {
    val meta = remember(task.metaJson) { repository.decodeMeta(task) }
    val owner = meta?.owner?.assistantId?.takeIf { showOwner }?.let { id -> LocalSettings.current.assistants.find { it.id == id } }
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val agent = meta?.kind == TaskKind.AGENT
            val kindIcon = if (agent) HugeIcons.AiMagic else HugeIcons.ComputerTerminal01
            val kindColor = if (agent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
            if (owner != null) {
                val arrangedBy = stringResource(R.string.remote_task_arranged_by, partnerName(owner))
                Box(Modifier.size(40.dp).semantics { contentDescription = arrangedBy }) {
                    ThreadAvatar(owner, modifier = Modifier.fillMaxSize())
                    // Ringed in the row's colour so the badge reads as separate from the avatar.
                    Surface(color = kindColor, shape = CircleShape,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.size(19.dp).align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(kindIcon, null, Modifier.size(11.dp)) }
                    }
                }
            } else Surface(color = kindColor, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(kindIcon, null, Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(meta?.name ?: task.taskId, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (task.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                // The name too: partners that kept the default avatar look the same.
                Text(listOfNotNull(scheduleLabel(task.schedule), meta?.agent?.toolName ?: stringResource(R.string.remote_task_kind_command),
                    owner?.let { partnerName(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                !task.enabled -> TaskBadge(stringResource(R.string.remote_task_paused), MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurfaceVariant)
                !task.timerOk -> TaskBadge(stringResource(R.string.remote_task_timer_missing), MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer)
                last != null -> Text(runStatusLabel(last.status), style = MaterialTheme.typography.bodySmall,
                    color = if (isRunFailure(last.status)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Icon(HugeIcons.ArrowRight01, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LingerCommandCard(username: String, onRecheck: () -> Unit) {
    val context = LocalContext.current
    val command = "sudo loginctl enable-linger $username"
    SetupCard {
        Text(stringResource(R.string.remote_task_linger_admin), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { CommandPreview(command) }
            FilledTonalButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Miffan", command))
            }) { Text(stringResource(R.string.im_computer_copy)) }
        }
        TextButton(onClick = onRecheck) { Text(stringResource(R.string.remote_task_recheck)) }
    }
}

@Composable
internal fun InfoCard(icon: ImageVector, title: String, body: String, action: Pair<String, () -> Unit>? = null) {
    SetupCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action?.let { (label, run) -> FilledTonalButton(onClick = run) { Text(label) } }
        }
    }
}

@Composable
private fun Hint(icon: ImageVector, text: String) {
    Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun TaskBadge(text: String, background: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Surface(color = background, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = content)
    }
}

private fun timeOf(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).let { clock(it.hour, it.minute) }

@Composable
private fun dayLabel(epochMillis: Long): String = me.ayuilos.miffan.ui.remotetask.dayAndTime(epochMillis)
