package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.ayuilos.miffan.ui.im.computer.rememberPartnerComputer
import me.ayuilos.miffan.ui.im.computer.rememberPartnerUsesPhone
import me.ayuilos.miffan.data.model.withWorkspaceBinding
import me.ayuilos.miffan.ui.im.computer.PartnerComputerPermissionRow
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.im.thread.threadAssistantName
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.revision.RevisionSubject
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun ImPartnerProfilePage(assistantId: String, vm: ImPartnerVM = koinViewModel(key = assistantId, parameters = { parametersOf(Uuid.parse(assistantId)) })) {
    val nav = LocalNavController.current
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val computer by rememberPartnerComputer(vm.assistantId)
    val usesPhone by rememberPartnerUsesPhone(vm.assistantId)
    val memories by vm.memories.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editing by rememberSaveable { mutableStateOf(false) }
    var deleteConfirm by rememberSaveable { mutableStateOf(false) }
    var deletePartnerConfirm by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var personality by rememberSaveable { mutableStateOf("") }
    val failure = stringResource(R.string.im_p5_failed)
    val saved = stringResource(R.string.im_p5_saved)
    val changedTopic = stringResource(R.string.im_p5_topic_changed)
    val deleted = stringResource(R.string.im_p5_chats_deleted)
    fun perform(message: String? = null, block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try { block(); message?.let { snackbar.showSnackbar(it) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { snackbar.showSnackbar(failure) }
            finally { busy = false }
        }
    }
    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_p5_profile)) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val partner = assistant
        if (partner == null) ImEmpty(stringResource(R.string.im_p5_missing_partner))
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("identity") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistantAvatar(partner.name, partner.avatar, Modifier.size(128.dp), onUpdate = { avatar -> perform { vm.update { it.copy(avatar = avatar) } } })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(partner.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }, style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { name = partner.name; personality = partner.systemPrompt; editing = true }) { Text(stringResource(R.string.im_p5_edit)) }
                    }
                    Text(partner.systemPrompt.ifBlank { stringResource(R.string.im_thread_intro, threadAssistantName(partner)) }, textAlign = TextAlign.Center,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = {
                        // Opened from this partner's thread: return to it instead of stacking a second copy.
                        nav.navigate(Screen.Thread(assistantId)) { popUpTo(Screen.Thread(assistantId)); launchSingleTop = true }
                    }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.im_p5_send_message)) }
                }
            }
            item("memory") { ImSettingRow(stringResource(R.string.im_p5_memory_count, memories.size), { nav.navigate(Screen.ImMemory(partner.memoryOwnerId())) }) }
            item("capabilities") { Text(stringResource(R.string.im_p5_capabilities), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item("web") { ImCapabilityRow(stringResource(R.string.im_p5_web), partner.enableWebSearch, !busy) { enabled -> perform { vm.update { it.copy(enableWebSearch = enabled) } } } }
            item("computer") {
                PartnerComputerPermissionRow(computer, usesPhone, partner.computerUse, busy,
                    onMode = { mode -> perform { vm.update { it.copy(computerUse = mode) } } },
                    onSetup = { nav.navigate(Screen.ComputerSetup(assistantId)) },
                    onScreen = { nav.navigate(Screen.PartnerScreen(assistantId)) },
                    onDisconnect = { perform { vm.update { it.withWorkspaceBinding(null) } } },
                    onOpenComputer = { computer?.hostId?.let { nav.navigate(Screen.ComputerDetail(it)) } })
            }
            item("remember") { ImCapabilityRow(stringResource(R.string.im_p5_remember), partner.enableMemory, !busy) { enabled -> perform { vm.update { it.copy(enableMemory = enabled) } } } }
            item("history") { ImSettingRow(stringResource(R.string.im_p5_settings_history), { nav.navigate(Screen.RevisionHistory(RevisionSubject.ASSISTANT.name, assistantId)) }) }
            item("approvals") { ImSettingRow(stringResource(R.string.im_audit_title), { nav.navigate(Screen.ApprovalHistory(assistantId)) }) }
            item("background") { ImSettingRow(stringResource(R.string.im_p5_background), { nav.navigate(Screen.ImChatBackground(assistantId)) }) }
            item("separator") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item("topic") { ImSettingRow(stringResource(R.string.im_thread_new_topic), { perform(changedTopic) { vm.changeTopic() } }) }
            item("delete") { ImSettingRow(stringResource(R.string.im_p5_delete_chats), { deleteConfirm = true }, destructive = true) }
            if (vm.canDeletePartner) item("delete_partner") {
                ImSettingRow(stringResource(R.string.im_partner_delete), { deletePartnerConfirm = true }, destructive = true)
            }
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.im_p5_edit)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.im_p5_name)) }, singleLine = true)
            OutlinedTextField(personality, { personality = it }, label = { Text(stringResource(R.string.im_p5_personality)) }, minLines = 3, maxLines = 7)
        }
    }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = {
        perform(saved) { vm.update { it.copy(name = name.trim(), systemPrompt = personality) }; editing = false }
    }) { Text(stringResource(R.string.chat_page_save)) } }, dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.chat_page_cancel)) } })
    if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text(stringResource(R.string.im_p5_delete_chats)) },
        text = { Text(stringResource(R.string.im_p5_delete_chats_confirm)) }, confirmButton = {
            TextButton(enabled = !busy, onClick = { perform(deleted) { vm.deleteChats(); deleteConfirm = false } }) { Text(stringResource(R.string.im_p5_delete_chats), color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text(stringResource(R.string.chat_page_cancel)) } })
    if (deletePartnerConfirm) AlertDialog(onDismissRequest = { deletePartnerConfirm = false }, title = { Text(stringResource(R.string.im_partner_delete)) },
        text = { Text(stringResource(R.string.im_partner_delete_confirm)) }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                perform {
                    vm.deletePartner()
                    deletePartnerConfirm = false
                    // Its thread and profile no longer have anything to show.
                    nav.removeAll(Screen.Home) { screen ->
                        screen == Screen.PartnerProfile(assistantId) || (screen is Screen.Thread && screen.assistantId == assistantId)
                    }
                }
            }) { Text(stringResource(R.string.im_partner_delete), color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { deletePartnerConfirm = false }) { Text(stringResource(R.string.chat_page_cancel)) } })
}

@Composable
private fun ImCapabilityRow(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Switch(checked, onChange, enabled = enabled)
        }
    }
}
