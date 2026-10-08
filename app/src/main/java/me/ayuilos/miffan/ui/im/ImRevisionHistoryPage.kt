package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.revision.*
import me.ayuilos.miffan.ui.context.LocalNavController
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ImRevisionHistoryPage(subject: String, subjectId: String,
    vm: ImRevisionVM = koinViewModel(key = "$subject:$subjectId", parameters = { parametersOf(RevisionSubject.valueOf(subject), subjectId) })) {
    val nav = LocalNavController.current
    val history by vm.history.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    val restored = stringResource(R.string.im_p5_restored)
    val conflict = stringResource(R.string.im_p5_refresh_conflict)
    val missing = stringResource(R.string.im_p5_not_found)
    val sourceMissing = stringResource(R.string.im_p5_source_missing)
    val failure = stringResource(R.string.im_p5_failed)
    Scaffold(topBar = { ImPageBar(stringResource(if (subject == RevisionSubject.MEMORY.name) R.string.im_p5_history else R.string.im_p5_settings_history)) },
        snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp)) {
            if (history?.isEmpty() == true) item("empty") { ImEmpty(stringResource(R.string.im_p5_history_empty)) }
            itemsIndexed(history.orEmpty(), key = { _, revision -> revision.id }) { index, revision ->
                val parent = history?.find { it.id == revision.parentId }
                ImRevisionEntry(revision, parent, current = index == 0, busy = busy,
                    onSource = {
                        revision.trigger?.let { trigger -> scope.launch {
                            try {
                                val id = vm.sourceAssistantId(trigger.conversationId)
                                if (id != null) nav.navigate(Screen.Thread(id, trigger.messageId.toString())) else snackbar.showSnackbar(sourceMissing)
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { snackbar.showSnackbar(failure) }
                        } }
                    }, onRestore = {
                        val expectedHead = history?.firstOrNull()?.id ?: return@ImRevisionEntry
                        scope.launch {
                            busy = true
                            try {
                                snackbar.showSnackbar(when (vm.restore(revision, expectedHead)) {
                                    RestoreResult.Restored -> restored
                                    RestoreResult.Conflict -> conflict
                                    RestoreResult.NotFound -> missing
                                })
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { snackbar.showSnackbar(failure) }
                            finally { busy = false }
                        }
                    })
            }
        }
    }
}

@Composable
private fun ImRevisionEntry(revision: Revision, parent: Revision?, current: Boolean, busy: Boolean, onSource: () -> Unit, onRestore: () -> Unit) {
    var expanded by rememberSaveable(revision.id) { mutableStateOf(false) }
    var raw by rememberSaveable(revision.id) { mutableStateOf(false) }
    val changes = remember(revision, parent) { runCatching { imRevisionChanges(revision, parent) }.getOrDefault(emptyList()) }
    val differences = remember(revision, parent) { runCatching { imRevisionDiff(revision, parent) }.getOrDefault(emptyList()) }
    val locale = LocalConfiguration.current.locales[0]
    val date = revision.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))
    val origin = when (revision.author) {
        RevisionAuthor.AGENT -> R.string.im_p5_origin_agent
        RevisionAuthor.USER -> R.string.im_p5_origin_user
        RevisionAuthor.RESTORE -> R.string.im_p5_origin_restore
        RevisionAuthor.BACKUP -> R.string.im_p5_origin_backup
        RevisionAuthor.BASELINE -> R.string.im_p5_origin_baseline
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(20.dp).fillMaxHeight()) {
            Box(Modifier.width(2.dp).fillMaxHeight().align(Alignment.TopCenter).background(MaterialTheme.colorScheme.outlineVariant))
            Surface(shape = CircleShape, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp).size(16.dp).align(Alignment.TopCenter)) {}
        }
        Column(Modifier.weight(1f).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(date, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (current) Text(stringResource(R.string.im_p5_current), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Text(stringResource(origin), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val labels = mutableListOf<String>()
                for (change in changes) labels += change.text(standalone = false)
                val text = if (labels.isNotEmpty()) labels.joinToString("、") else revision.summary.ifBlank { stringResource(origin) }
                Text(text, style = MaterialTheme.typography.titleMedium)
                Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (revision.trigger != null) ImSettingRow(stringResource(R.string.im_p5_source), onSource)
            if (expanded) {
                Row {
                    TextButton(onClick = { raw = false }) { Text(stringResource(R.string.im_p5_summary), color = if (!raw) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { raw = true }) { Text(stringResource(R.string.im_p5_diff), color = if (raw) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (raw) {
                    differences.forEach { line ->
                        val tint = if (line.added) Color(0xFF217348) else MaterialTheme.colorScheme.error
                        Surface(color = tint.copy(alpha = .10f), shape = MaterialTheme.shapes.medium) {
                            Text("${if (line.added) "+" else "−"} ${line.text}", Modifier.fillMaxWidth().padding(12.dp), color = if (line.added) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        }
                    }
                    if (differences.isEmpty()) Text(stringResource(R.string.im_p5_diff_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else changes.forEach { change ->
                    Text(change.text(standalone = true),
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (!current) {
                    OutlinedButton(enabled = !busy, onClick = onRestore) { Text(stringResource(R.string.im_p5_restore)) }
                    Text(stringResource(R.string.im_p5_restore_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * A plain label reads "Changed: X" on its own line ([standalone]); labels with an argument and
 * whole-sentence labels read the same everywhere.
 */
@Composable
private fun ImRevisionChange.text(standalone: Boolean): String = when {
    detailRes != null -> stringResource(label, stringResource(detailRes))
    detail != null -> stringResource(label, detail)
    standalone && label !in sentenceLabels -> stringResource(R.string.im_p5_changed, stringResource(label))
    else -> stringResource(label)
}

private val sentenceLabels = setOf(
    R.string.im_revision_workspace_removed, R.string.im_revision_workspace_bound,
    R.string.im_revision_shell_always, R.string.im_revision_shell_always_removed,
)
