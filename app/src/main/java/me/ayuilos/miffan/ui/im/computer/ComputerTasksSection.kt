package me.ayuilos.miffan.ui.im.computer

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.db.entity.RemoteTaskEntity
import me.ayuilos.miffan.data.db.entity.RemoteTaskRunEntity
import me.ayuilos.miffan.data.remotetask.RemoteTaskRepository
import me.ayuilos.miffan.data.remotetask.TaskKind
import me.ayuilos.miffan.ui.context.LocalNavController
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

/**
 * A computer's scheduled tasks on its detail page. Opening the page syncs once: the app never pulls
 * in the background, so everything here says when it was last brought over and what that means.
 */
@Composable
internal fun ComputerTasksSection(hostId: String, windows: Boolean, username: String) {
    val nav = LocalNavController.current
    val repository: RemoteTaskRepository = koinInject()
    val scope = rememberCoroutineScope()
    val tasks by remember(hostId) { repository.tasksFlow(hostId) }.collectAsStateWithLifecycle(initialValue = emptyList())
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

@Composable
private fun TaskRow(task: RemoteTaskEntity, last: RemoteTaskRunEntity?, repository: RemoteTaskRepository, onClick: () -> Unit) {
    val meta = remember(task.metaJson) { repository.decodeMeta(task) }
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val agent = meta?.kind == TaskKind.AGENT
            Surface(color = if (agent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (agent) HugeIcons.AiMagic else HugeIcons.ComputerTerminal01, null, Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(meta?.name ?: task.taskId, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (task.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(listOf(scheduleLabel(task.schedule), meta?.agent?.toolName ?: stringResource(R.string.remote_task_kind_command))
                    .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
