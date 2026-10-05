package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.revision.RestoreResult
import me.ayuilos.miffan.data.revision.RevisionSubject
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.utils.toLocalString
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.Instant
import java.time.ZoneId

@Composable
fun ImMemoryPage(ownerId: String, vm: ImMemoryVM = koinViewModel(key = ownerId, parameters = { parametersOf(ownerId) })) {
    val nav = LocalNavController.current
    val rows by vm.rows.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deleted = stringResource(R.string.im_p5_memory_deleted)
    val undo = stringResource(R.string.im_thread_undo)
    val conflict = stringResource(R.string.im_p5_refresh_conflict)
    val failure = stringResource(R.string.im_p5_failed)
    val missing = stringResource(R.string.im_p5_source_missing)
    var deleting by remember { mutableStateOf(setOf<Int>()) }
    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_me_memory)) {
        TextButton(onClick = { nav.navigate(Screen.RevisionHistory(RevisionSubject.MEMORY.name, ownerId)) }) { Text(stringResource(R.string.im_p5_history)) }
    } }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item("explanation") { Text(stringResource(R.string.im_p5_memory_info), Modifier.padding(bottom = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (rows?.isEmpty() == true) item("empty") { ImEmpty(stringResource(R.string.im_p5_memory_empty)) }
            items(rows.orEmpty(), key = { it.memory.id }) { row ->
                val memory = row.memory
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable {
                        if (row.sourceAssistantId != null && memory.sourceMessageId.isNotBlank()) nav.navigate(Screen.Thread(row.sourceAssistantId, memory.sourceMessageId))
                        else scope.launch { snackbar.showSnackbar(missing) }
                    }.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(memory.content, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.partner?.let { AssistantAvatar(it.name, it.avatar, Modifier.size(28.dp)) }
                            val name = row.partner?.name?.ifBlank { stringResource(R.string.assistant_page_default_assistant) } ?: stringResource(R.string.im_p5_unknown_source)
                            val date = if (memory.createdAt > 0) Instant.ofEpochMilli(memory.createdAt).atZone(ZoneId.systemDefault()).toLocalDate().toLocalString(includeYear = true)
                                else stringResource(R.string.im_p5_unknown_date)
                            Text(stringResource(R.string.im_p5_memory_by, name, date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(enabled = memory.id !in deleting, onClick = {
                        deleting = deleting + memory.id
                        scope.launch {
                            try {
                                val revisionId = vm.delete(memory)
                                deleting = deleting - memory.id
                                val result = snackbar.showSnackbar(deleted, actionLabel = if (revisionId != null) undo else null, duration = SnackbarDuration.Long)
                                if (result == SnackbarResult.ActionPerformed && revisionId != null && vm.undo(revisionId) != RestoreResult.Restored) snackbar.showSnackbar(conflict)
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { snackbar.showSnackbar(failure) }
                            finally { deleting = deleting - memory.id }
                        }
                    }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            }
        }
    }
}
