package me.ayuilos.miffan.ui.im.computer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.entity.RemoteTaskRunEntity
import me.ayuilos.miffan.data.remotetask.RemoteTaskProbe
import me.ayuilos.miffan.data.remotetask.RemoteTaskRepository
import me.ayuilos.miffan.data.remotetask.TaskKind
import me.ayuilos.miffan.data.remotetask.TaskNotify
import me.ayuilos.miffan.data.remotetask.TaskSchedule
import me.ayuilos.miffan.data.remotetask.nextRun
import me.ayuilos.miffan.data.remotetask.zone
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.ImPageBar
import me.ayuilos.miffan.ui.im.partnerName
import me.ayuilos.miffan.ui.remotetask.dayAndTime
import me.ayuilos.miffan.ui.remotetask.isRunFailure
import me.ayuilos.miffan.ui.remotetask.notifyLabel
import me.ayuilos.miffan.ui.remotetask.permissionLabel
import me.ayuilos.miffan.ui.remotetask.runStatusLabel
import me.ayuilos.miffan.ui.remotetask.scheduleLabel
import me.ayuilos.miffan.ui.remotetask.zoneNote
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.PlayCircle
import org.koin.compose.koinInject
import java.time.ZonedDateTime

/** One scheduled task: whether it runs, when, what it does, who hears about it, and its recent runs. */
@Composable
fun RemoteTaskDetailPage(hostId: String, taskId: String) {
    val nav = LocalNavController.current
    val resources = LocalResources.current
    val repository: RemoteTaskRepository = koinInject()
    val assistants = LocalSettings.current.assistants
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val tasks by remember(hostId) { repository.tasksFlow(hostId) }.collectAsStateWithLifecycle(initialValue = null)
    val host by remember(hostId) { repository.hostFlow(hostId) }.collectAsStateWithLifecycle(initialValue = null)
    val runs by remember(hostId, taskId) { repository.runsFlow(hostId, taskId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var busy by remember { mutableStateOf(false) }
    var choosingNotify by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var openRun by remember { mutableStateOf<RemoteTaskRunEntity?>(null) }
    val task = tasks?.find { it.taskId == taskId }
    LaunchedEffect(tasks) { if (tasks != null && task == null) nav.popBackStack() }
    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                snackbar.showSnackbar(error.message ?: resources.getString(R.string.remote_task_action_failed))
            } finally { busy = false }
        }
    }
    Scaffold(topBar = { ImPageBar("") }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val current = task ?: return@Scaffold
        val meta = remember(current.metaJson) { repository.decodeMeta(current) }
        val schedule = remember(current.schedule) { TaskSchedule.parseHelper(current.schedule) }
        val partner = meta?.owner?.assistantId?.let { id -> assistants.find { it.id == id } }
        val zone = host?.let { RemoteTaskProbe(it.scheduler, timezone = it.timezone, utcOffset = it.utcOffset).zone() }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("title") {
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(meta?.name ?: taskId, style = MaterialTheme.typography.headlineSmall)
                    val kind = meta?.agent?.let { listOfNotNull(it.toolName, permissionLabel(it.permission)).joinToString(" · ") }
                        ?: stringResource(R.string.remote_task_kind_command)
                    Text(kind, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item("settings") {
                SetupCard(padding = 0.dp, spacing = 0.dp) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.remote_task_enabled), Modifier.weight(1f))
                        Switch(checked = current.enabled, enabled = !busy, onCheckedChange = { on ->
                            perform { repository.setEnabled(hostId, taskId, on) }
                        })
                    }
                    if (current.enabled && !current.timerOk) Text(stringResource(R.string.remote_task_timer_missing_help),
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.remote_task_field_time), Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(scheduleLabel(current.schedule))
                            if (schedule != null) {
                                val next = zone?.let { schedule.nextRun(ZonedDateTime.now(it)) }
                                Text(listOfNotNull(zoneNote(schedule, zone), next?.let {
                                    stringResource(R.string.remote_task_next_run, dayAndTime(it))
                                }).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    Surface(onClick = { choosingNotify = true }, enabled = !busy && meta != null && schedule != null,
                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.remote_task_field_result), Modifier.weight(1f))
                            Text(meta?.notify?.let { notifyLabel(it, partner?.let { p -> partnerName(p) }) }.orEmpty(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(HugeIcons.ArrowRight01, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item("what") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (meta?.kind == TaskKind.AGENT) R.string.remote_task_what else R.string.remote_task_field_command),
                        Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SetupCard {
                        Text(meta?.prompt ?: meta?.command.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                            fontFamily = if (meta?.kind == TaskKind.COMMAND) FontFamily.Monospace else null)
                    }
                }
            }
            item("runs_title") {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Text(stringResource(R.string.remote_task_recent_runs), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.remote_task_runs_kept), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item("runs") {
                if (runs.isEmpty()) SetupCard { Text(stringResource(R.string.remote_task_no_runs), style = MaterialTheme.typography.bodyMedium) }
                else SetupCard(padding = 0.dp, spacing = 0.dp) {
                    runs.forEachIndexed { index, run ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 36.dp))
                        RunRow(run) { openRun = run }
                    }
                }
            }
            item("actions") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = {
                        perform {
                            val started = System.currentTimeMillis()
                            repository.runNow(hostId, taskId)
                            snackbar.showSnackbar(resources.getString(R.string.remote_task_run_started))
                            // Watch for the run for a minute; later ones arrive with the next sync.
                            repeat(12) {
                                delay(5_000)
                                if (runCatching { repository.sync(hostId) }.getOrNull()
                                        ?.any { it.taskId == taskId && it.startedAt >= started - 60_000 } == true) return@perform
                            }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                        Icon(HugeIcons.PlayCircle, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.remote_task_run_now))
                    }
                    Surface(onClick = { deleting = true }, enabled = !busy, color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.large) {
                        Text(stringResource(R.string.remote_task_delete), Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.error)
                    }
                    partner?.let {
                        Text(stringResource(R.string.remote_task_arranged_by, partnerName(it)), Modifier.padding(horizontal = 6.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (choosingNotify && meta != null) AlertDialog(onDismissRequest = { choosingNotify = false },
            title = { Text(stringResource(R.string.remote_task_field_result)) },
            text = {
                Column {
                    TaskNotify.entries.forEach { option ->
                        Surface(onClick = {
                            choosingNotify = false
                            if (option != meta.notify) perform {
                                val draft = requireNotNull(repository.draftOf(current)) { resources.getString(R.string.remote_task_action_failed) }
                                repository.save(hostId, draft.copy(notify = option))
                            }
                        }, color = androidx.compose.ui.graphics.Color.Transparent, shape = MaterialTheme.shapes.medium) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = option == meta.notify, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(notifyLabel(option, partner?.let { partnerName(it) }))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingNotify = false }) { Text(stringResource(R.string.common_cancel)) } })
        if (deleting) AlertDialog(onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.remote_task_delete_title, meta?.name.orEmpty())) },
            text = { Text(stringResource(R.string.remote_task_delete_body)) },
            confirmButton = {
                TextButton(onClick = { deleting = false; perform { repository.remove(hostId, taskId) } }) {
                    Text(stringResource(R.string.remote_task_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.common_cancel)) } })
        openRun?.let { run -> RunSheet(hostId, meta?.name.orEmpty(), meta?.agent?.toolName, run, repository) { openRun = null } }
    }
}

@Composable
private fun RunRow(run: RemoteTaskRunEntity, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = if (isRunFailure(run.status)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.size(8.dp)) {}
            Column(Modifier.weight(1f)) {
                Text("${dayAndTime(run.startedAt)} · ${runStatusLabel(run.status)}", style = MaterialTheme.typography.bodyLarge)
                val summary = run.resultError ?: run.resultText ?: run.excerpt.lineSequence().lastOrNull { it.isNotBlank() }
                Text(listOfNotNull(summary?.lineSequence()?.firstOrNull { it.isNotBlank() },
                    stringResource(R.string.remote_task_synced_at, dayAndTime(run.syncedAt))).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (run.resultError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(HugeIcons.ArrowRight01, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One run: when it ran and when it reached the phone, the agent's answer or why it failed, and the full log on demand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunSheet(hostId: String, taskName: String, agentName: String?, run: RemoteTaskRunEntity, repository: RemoteTaskRepository,
                     onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var log by remember(run.runId) { mutableStateOf<String?>(null) }
    var loading by remember(run.runId) { mutableStateOf(false) }
    var logError by remember(run.runId) { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(taskName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(dayAndTime(run.startedAt), style = MaterialTheme.typography.headlineSmall)
                val failure = isRunFailure(run.status)
                TaskBadge(runStatusLabel(run.status),
                    if (failure) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                    if (failure) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
            }
            val seconds = ((run.endedAt - run.startedAt) / 1000).coerceAtLeast(0)
            Text(stringResource(R.string.remote_task_run_times, dayAndTime(run.endedAt), seconds / 60, seconds % 60, dayAndTime(run.syncedAt)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                run.resultError != null -> SetupCard {
                    Text(run.resultError, color = MaterialTheme.colorScheme.error)
                }
                run.resultText != null -> {
                    Text(stringResource(R.string.remote_task_agent_reply, agentName.orEmpty()), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SetupCard { SelectionContainer { Text(run.resultText) } }
                }
                run.excerpt.isNotBlank() -> SetupCard {
                    SelectionContainer {
                        Text(run.excerpt.takeLast(4_000), Modifier.horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            when {
                log != null -> SetupCard {
                    SelectionContainer {
                        Text(log!!, Modifier.horizontalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                run.logBytes > 0 -> OutlinedButton(onClick = {
                    loading = true
                    scope.launch {
                        runCatching { repository.log(hostId, run.taskId, run.runId) }
                            .onSuccess { log = it }.onFailure { logError = it.message }
                        loading = false
                    }
                }, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(if (loading) R.string.remote_task_loading_log else R.string.remote_task_full_output))
                }
            }
            logError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (log == null) Text(stringResource(R.string.remote_task_full_output_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
