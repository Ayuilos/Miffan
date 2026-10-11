package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.PartnerChatKind
import me.ayuilos.miffan.data.model.PartnerGroupLimits
import me.ayuilos.miffan.data.partner.PartnerChatService
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.UserRemove01
import me.rerere.hugeicons.stroke.Crown
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** "发起群聊": an optional name and 2–6 partners; the first one picked hosts the group. */
@Composable
internal fun GroupCreatePage() {
    val nav = LocalNavController.current
    val service: PartnerChatService = koinInject()
    val scope = rememberCoroutineScope()
    val partners = LocalSettings.current.assistants
    var name by rememberSaveable { mutableStateOf("") }
    val picked = remember { mutableStateListOf<Uuid>() }
    var creating by remember { mutableStateOf(false) }
    val valid = picked.size in PartnerGroupLimits.MIN_PARTNERS..PartnerGroupLimits.MAX_PARTNERS
    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_gc_create)) }, bottomBar = {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            Button(
                enabled = valid && !creating,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                onClick = {
                    creating = true
                    scope.launch {
                        try {
                            val id = service.createGroup(picked.toList(), name)
                            nav.replace(Screen.GroupCreate, Screen.PartnerChat(id.toString()), Screen.Home)
                        } finally { creating = false }
                    }
                },
            ) { Text(stringResource(R.string.im_gc_create_action, picked.size)) }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            item("name") {
                OutlinedTextField(name, { name = it.take(24) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.im_gc_name_hint)) }, singleLine = true)
            }
            item("hint") {
                Text(stringResource(R.string.im_gc_pick_hint), Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(partners, key = { it.id.toString() }) { partner ->
                val index = picked.indexOf(partner.id)
                GroupPartnerPickRow(partner, checked = index >= 0, host = index == 0,
                    enabled = index >= 0 || picked.size < PartnerGroupLimits.MAX_PARTNERS) {
                    if (index >= 0) picked.remove(partner.id) else picked.add(partner.id)
                }
            }
        }
    }
}

@Composable
private fun GroupPartnerPickRow(partner: Assistant, checked: Boolean, host: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // The mascot consumes taps for its own reactions, so it forwards the row action.
        AssistantAvatar(partner.name, partner.avatar, Modifier.size(44.dp), onClick = { if (enabled) onToggle() })
        Text(partnerName(partner), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (host) GroupHostLabel()
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
    }
}

@Composable
private fun GroupHostLabel() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(stringResource(R.string.im_gc_host), Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}

/** Group info: name, members with the host marked, adding and removing members, deleting the group. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupInfoPage(chatId: Uuid) {
    val nav = LocalNavController.current
    val service: PartnerChatService = koinInject()
    val scope = rememberCoroutineScope()
    val chat by remember(chatId) { service.observeChat(chatId) }.collectAsStateWithLifecycle(null)
    val settings = LocalSettings.current
    val current = chat ?: run {
        Scaffold(topBar = { ImPageBar(stringResource(R.string.im_gc_info)) }) {}
        return
    }
    val members = current.memberIds.mapNotNull { id -> settings.assistants.firstOrNull { it.id == id } }
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val canRemove = members.size > PartnerGroupLimits.MIN_PARTNERS
    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_gc_info)) }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item("name") {
                ImSettingRow(stringResource(R.string.im_gc_name), { renaming = true }, supporting = current.title.ifBlank { null })
            }
            item("members") {
                Text(stringResource(R.string.im_gc_members, members.size), Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(members, key = { it.id.toString() }) { partner ->
                val menu = rememberPressMenu()
                val host = partner.id == current.hostId
                Box {
                    Row(Modifier.fillMaxWidth().imLongPress(menu::open)
                        .clickable { nav.navigate(Screen.PartnerProfile(partner.id.toString())) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AssistantAvatar(partner.name, partner.avatar, Modifier.size(44.dp),
                            onClick = { nav.navigate(Screen.PartnerProfile(partner.id.toString())) })
                        Text(partnerName(partner), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (host) GroupHostLabel()
                    }
                    ImPressMenu(menu) {
                        if (!host) ImPressMenuItem(HugeIcons.Crown, stringResource(R.string.im_gc_make_host)) {
                            menu.close(); scope.launch { service.setHost(chatId, partner.id) }
                        }
                        ImPressMenuItem(HugeIcons.UserRemove01, stringResource(R.string.im_gc_remove), enabled = canRemove, destructive = true) {
                            menu.close(); scope.launch { service.setMembers(chatId, current.memberIds - partner.id) }
                        }
                    }
                }
            }
            if (members.size < PartnerGroupLimits.MAX_PARTNERS) item("add") {
                Row(Modifier.fillMaxWidth().clickable { adding = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(HugeIcons.Add01, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(stringResource(R.string.im_gc_add_members), color = MaterialTheme.colorScheme.primary)
                }
            }
            item("delete") {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp).clickable { deleting = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(HugeIcons.Delete01, null, tint = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.im_gc_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (renaming) ImGroupNameDialog(R.string.im_gc_name, initial = current.title,
        onConfirm = { title -> renaming = false; scope.launch { service.renameGroup(chatId, title) } }, onDismiss = { renaming = false })
    if (adding) {
        val others = settings.assistants.filter { it.id !in current.memberIds }
        val picked = remember { mutableStateListOf<Uuid>() }
        val room = PartnerGroupLimits.MAX_PARTNERS - members.size
        ModalBottomSheet(onDismissRequest = { adding = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(stringResource(R.string.im_gc_add_members), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                others.forEach { partner ->
                    val checked = partner.id in picked
                    GroupPartnerPickRow(partner, checked, host = false, enabled = checked || picked.size < room) {
                        if (checked) picked.remove(partner.id) else picked.add(partner.id)
                    }
                }
                Button(enabled = picked.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    onClick = {
                        adding = false
                        scope.launch { service.setMembers(chatId, current.memberIds + picked) }
                    }) { Text(stringResource(R.string.im_gc_add_action, picked.size)) }
            }
        }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.im_gc_delete)) },
        text = { Text(stringResource(R.string.im_gc_delete_desc)) },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                scope.launch {
                    service.deleteGroup(chatId)
                    nav.removeAll(Screen.Home) { it == Screen.GroupInfo(chatId.toString()) || it == Screen.PartnerChat(chatId.toString()) }
                }
            }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Whether a chat is a group; DIRECT chats between two partners have no info page. */
internal val me.ayuilos.miffan.data.model.PartnerChat.isGroup: Boolean get() = kind == PartnerChatKind.GROUP
