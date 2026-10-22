package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.findModelById
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.ComputerUseMode
import me.ayuilos.miffan.data.revision.RevisionSubject
import me.ayuilos.miffan.ui.components.ai.ModelListSheet
import me.ayuilos.miffan.ui.components.ai.rememberModelListState
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.computer.rememberPartnerComputer
import me.ayuilos.miffan.ui.im.computer.rememberPartnerTasks
import me.ayuilos.miffan.ui.im.computer.rememberPartnerUsesPhone
import me.ayuilos.miffan.ui.im.thread.threadAssistantName
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Globe02
import me.rerere.hugeicons.stroke.Image01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.UserGroup
import me.rerere.hugeicons.stroke.View
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * A partner's page, like a contact's in an IM: who it is and quick actions on top, then what it can do,
 * what it shares with the owner, the rarely needed settings, and finally the destructive actions.
 */
@Composable
fun ImPartnerProfilePage(assistantId: String, vm: ImPartnerVM = koinViewModel(key = assistantId, parameters = { parametersOf(Uuid.parse(assistantId)) })) {
    val nav = LocalNavController.current
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val computer by rememberPartnerComputer(vm.assistantId)
    val usesPhone by rememberPartnerUsesPhone(vm.assistantId)
    val computerTasks by rememberPartnerTasks(vm.assistantId, computer?.hostId)
    val memories by vm.memories.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val partnerChats: me.ayuilos.miffan.data.partner.PartnerChatService = org.koin.compose.koinInject()
    val currentSettings = LocalSettings.current
    val snackbar = remember { SnackbarHostState() }
    var deleteConfirm by rememberSaveable { mutableStateOf(false) }
    var deletePartnerConfirm by rememberSaveable { mutableStateOf(false) }
    var showContacts by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val failure = stringResource(R.string.im_p5_failed)
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
    val contacts = currentSettings.partnerContactGrants.mapNotNull { pair ->
        val other = when (vm.assistantId) { pair.first -> pair.second; pair.second -> pair.first; else -> null }
        other?.let { id -> currentSettings.assistants.firstOrNull { it.id == id } }
    }
    Scaffold(topBar = { ImPageBar("") }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val partner = assistant
        if (partner == null) ImEmpty(stringResource(R.string.im_p5_missing_partner))
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("identity") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AssistantAvatar(partner.name, partner.avatar, Modifier.size(112.dp), onClick = { nav.navigate(Screen.PartnerEdit(assistantId, look = true)) })
                    Text(partnerName(partner), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            item("actions") {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ImQuickAction(HugeIcons.Message01, stringResource(R.string.im_p5_send_message), primary = true) {
                        // Opened from this partner's thread: return to it instead of stacking a second copy.
                        nav.navigate(Screen.Thread(assistantId)) { popUpTo(Screen.Thread(assistantId)); launchSingleTop = true }
                    }
                    if (computer?.showsEntry == true) ImQuickAction(HugeIcons.View, stringResource(R.string.im_profile_view_screen)) {
                        nav.navigate(Screen.PartnerScreen(assistantId))
                    }
                    ImQuickAction(HugeIcons.Edit01, stringResource(R.string.im_profile_edit)) { nav.navigate(Screen.PartnerEdit(assistantId)) }
                }
            }
            item("personality") {
                ImPersonalityCard(partner.systemPrompt.ifBlank { stringResource(R.string.im_thread_intro, threadAssistantName(partner)) })
            }
            item("help") {
                ImSectionLabel(stringResource(R.string.im_p5_capabilities))
                Spacer(Modifier.height(8.dp))
                ImCardGroup {
                    ImListRow(HugeIcons.Globe02, stringResource(R.string.im_p5_web), enabled = !busy, tone = ImIconTone.PRIMARY,
                        supporting = stringResource(R.string.im_profile_web_desc),
                        onClick = { perform { vm.update { it.copy(enableWebSearch = !it.enableWebSearch) } } },
                        trailing = { Switch(partner.enableWebSearch, { enabled -> perform { vm.update { it.copy(enableWebSearch = enabled) } } }, enabled = !busy) })
                    ImCardDivider()
                    val bound = computer
                    when {
                        bound != null -> {
                            val mode = stringResource(when (partner.computerUse) {
                                ComputerUseMode.ASK -> R.string.computer_use_mode_ask
                                ComputerUseMode.AUTO -> R.string.computer_use_mode_auto
                                ComputerUseMode.OFF -> R.string.computer_use_mode_off
                            })
                            val tasks = computerTasks.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.im_profile_tasks, it, it) }
                            ImListRow(HugeIcons.Computer, stringResource(R.string.im_profile_computer), tone = ImIconTone.TERTIARY,
                                supporting = listOfNotNull(bound.name, mode, tasks).joinToString(" · "),
                                onClick = { nav.navigate(Screen.PartnerComputer(assistantId)) })
                        }
                        usesPhone -> ImListRow(HugeIcons.SmartPhone01, stringResource(R.string.im_computer_phone_title), tone = ImIconTone.TERTIARY,
                            supporting = stringResource(R.string.im_computer_phone_switch),
                            onClick = { nav.navigate(Screen.ComputerSetup(assistantId)) })
                        else -> ImListRow(HugeIcons.Computer, stringResource(R.string.im_profile_computer), tone = ImIconTone.TERTIARY,
                            supporting = stringResource(R.string.im_computer_connect_help),
                            onClick = { nav.navigate(Screen.ComputerSetup(assistantId)) })
                    }
                }
            }
            item("you") {
                ImSectionLabel(stringResource(R.string.im_profile_section_you))
                Spacer(Modifier.height(8.dp))
                ImCardGroup {
                    val count = memories.size
                    ImListRow(HugeIcons.Brain02, stringResource(R.string.im_profile_memory), tone = ImIconTone.SECONDARY,
                        supporting = when {
                            partner.enableMemory -> pluralStringResource(R.plurals.im_profile_memory_on, count, count)
                            count > 0 -> pluralStringResource(R.plurals.im_profile_memory_paused, count, count)
                            else -> stringResource(R.string.im_profile_memory_off)
                        },
                        onClick = { nav.navigate(Screen.ImMemory(partner.memoryOwnerId(), assistantId)) })
                    // Only once the owner allowed a contact: the approval card said this is where it can be taken back.
                    if (contacts.isNotEmpty()) {
                        ImCardDivider()
                        ImListRow(HugeIcons.UserGroup, stringResource(R.string.im_partner_contacts), tone = ImIconTone.SECONDARY,
                            supporting = contacts.map { partnerName(it) }.joinToString(stringResource(R.string.im_list_separator)),
                            onClick = { showContacts = true })
                    }
                }
            }
            item("more") {
                ImSectionLabel(stringResource(R.string.im_profile_section_more))
                Spacer(Modifier.height(8.dp))
                ImCardGroup {
                    PartnerModelRow(partner, currentSettings, !busy) { id -> perform { vm.update { it.copy(chatModelId = id) } } }
                    ImCardDivider()
                    ImListRow(HugeIcons.Shield01, stringResource(R.string.im_audit_title), { nav.navigate(Screen.ApprovalHistory(assistantId)) })
                    ImCardDivider()
                    ImListRow(HugeIcons.Clock01, stringResource(R.string.im_profile_history),
                        { nav.navigate(Screen.RevisionHistory(RevisionSubject.ASSISTANT.name, assistantId)) })
                    ImCardDivider()
                    ImListRow(HugeIcons.Image01, stringResource(R.string.im_p5_background), { nav.navigate(Screen.ImChatBackground(assistantId)) })
                }
            }
            item("danger") {
                Spacer(Modifier.height(16.dp))
                ImCardGroup {
                    ImListRow(null, stringResource(R.string.im_p5_delete_chats), { deleteConfirm = true },
                        titleColor = MaterialTheme.colorScheme.error, trailing = null)
                    if (vm.canDeletePartner) {
                        ImCardDivider(start = 16.dp)
                        ImListRow(null, stringResource(R.string.im_partner_delete), { deletePartnerConfirm = true },
                            titleColor = MaterialTheme.colorScheme.error, trailing = null)
                    }
                }
            }
        }
    }
    if (showContacts) ImContactsSheet(contacts, busy, onRevoke = { other -> scope.launch { partnerChats.revokeContact(vm.assistantId, other.id) } },
        onDismiss = { showContacts = false })
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
                    // Its thread and pages no longer have anything to show.
                    nav.removeAll(Screen.Home) { screen ->
                        screen == Screen.PartnerProfile(assistantId) || (screen is Screen.PartnerEdit && screen.assistantId == assistantId) ||
                            screen == Screen.PartnerComputer(assistantId) || (screen is Screen.Thread && screen.assistantId == assistantId)
                    }
                }
            }) { Text(stringResource(R.string.im_partner_delete), color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { deletePartnerConfirm = false }) { Text(stringResource(R.string.chat_page_cancel)) } })
}

@Composable
private fun RowScope.ImQuickAction(icon: ImageVector, label: String, primary: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).heightIn(min = 68.dp),
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The personality, three lines until expanded; the edit page is where it changes. */
@Composable
private fun ImPersonalityCard(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (overflows || expanded) 4.dp else 16.dp)) {
            Text(stringResource(R.string.im_p5_personality), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
            if (overflows || expanded) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(if (expanded) R.string.im_profile_collapse else R.string.im_profile_expand))
            }
        }
    }
}

/** Partners this one may message; taking one back is the owner's call. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImContactsSheet(contacts: List<Assistant>, busy: Boolean, onRevoke: (Assistant) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.im_partner_contacts), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            contacts.forEach { other ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AssistantAvatar(other.name, other.avatar, Modifier.size(40.dp))
                    Text(partnerName(other), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(enabled = !busy, onClick = { onRevoke(other) }) { Text(stringResource(R.string.im_partner_contacts_revoke)) }
                }
            }
            if (contacts.isEmpty()) LaunchedEffect(Unit) { onDismiss() }
        }
    }
}

/**
 * The model behind the partner. Without its own choice it follows the default chat model, and the
 * row says so with that model's name; a choice whose model was removed is flagged, since replies fail.
 */
@Composable
private fun PartnerModelRow(partner: Assistant, settings: Settings, enabled: Boolean, onSelect: (Uuid?) -> Unit) {
    val own = partner.chatModelId?.let { settings.providers.findModelById(it) }
    val fallback = settings.findModelById(settings.chatModelId)
    val missing = partner.chatModelId != null && own == null
    val state = rememberModelListState(own?.id ?: fallback?.id, settings.providers, ModelType.CHAT, textOnly = true)
    val supporting = when {
        missing -> stringResource(R.string.im_p5_model_missing)
        own != null -> own.displayName
        fallback != null -> stringResource(R.string.im_p5_model_default, fallback.displayName)
        else -> stringResource(R.string.im_p5_model_no_default)
    }
    if (partner.chatModelId != null) ImListRow(HugeIcons.Cpu, stringResource(R.string.im_p5_model), state::open, supporting, enabled = enabled) {
        TextButton(onClick = { onSelect(null) }, enabled = enabled) { Text(stringResource(R.string.im_p5_model_use_default)) }
    } else ImListRow(HugeIcons.Cpu, stringResource(R.string.im_p5_model), state::open, supporting, enabled = enabled)
    ModelListSheet(state) { model -> onSelect(model.id) }
}
