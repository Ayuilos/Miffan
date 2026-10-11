package me.ayuilos.miffan.ui.im

import android.content.Context
import androidx.core.content.edit
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.model.ChatGroup
import me.ayuilos.miffan.data.thread.PreviewKind
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.utils.toLocalString
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.ViewOff
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ImChatsTab(
    vm: ImHomeVM,
    innerPadding: PaddingValues,
    onFindPartner: () -> Unit,
) {
    val navController = LocalNavController.current
    val sections by vm.sections.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("im_ui_hints", Context.MODE_PRIVATE) }
    val showHint = rememberSaveable { !prefs.getBoolean(LONG_PRESS_HINT_SEEN, false) }
    LaunchedEffect(Unit) { prefs.edit { putBoolean(LONG_PRESS_HINT_SEEN, true) } }

    // The chat whose group is being chosen, and the group being renamed or deleted.
    var moving by remember { mutableStateOf<ImChatItem?>(null) }
    var renaming by remember { mutableStateOf<ChatGroup?>(null) }
    var deleting by remember { mutableStateOf<ChatGroup?>(null) }
    val open: (ImChatItem) -> Unit = { navController.navigate(Screen.Thread(it.chatId.toString())) }
    val actions = ImChatActions(
        open = open,
        setPinned = { chat, pinned -> vm.setPinned(chat.chatId, pinned) },
        move = { moving = it },
        hide = { vm.hide(it.chatId) },
    )

    val current = sections
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = innerPadding,
    ) {
        item("title") { ImTabTitle(R.string.im_tab_chats) }
        item("search") {
            ImSearchField(onClick = { navController.navigate(Screen.ImSearch) })
        }
        if (current != null && current.pinned.isEmpty() && current.ungrouped.isEmpty() && current.groups.isEmpty()) {
            item("empty") { ImChatsEmpty(onFindPartner) }
        }
        if (current != null) {
            if (current.pinned.isNotEmpty()) item("pinned") { ImPinnedChats(current.pinned, actions) }
            items(current.ungrouped, key = { it.chatId.toString() }) { chat -> ImChatRow(chat, actions) }
            val order = current.allGroups.map { it.id }
            current.groups.forEach { section ->
                val position = order.indexOf(section.group.id)
                item("group:${section.group.id}") {
                    ImChatGroupHeader(
                        section = section,
                        canMoveUp = position > 0,
                        canMoveDown = position < order.lastIndex,
                        onToggle = { vm.setGroupCollapsed(section.group.id, !section.collapsed) },
                        onRename = { renaming = section.group },
                        onMove = { vm.moveGroup(section.group.id, it) },
                        onDelete = { deleting = section.group },
                    )
                }
                if (!section.collapsed) items(section.chats, key = { it.chatId.toString() }) { chat -> ImChatRow(chat, actions) }
            }
        }
        if (showHint) item("hint") {
            Text(stringResource(R.string.im_chats_long_press_hint), Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    moving?.let { chat ->
        ImGroupPickerSheet(
            groups = current?.allGroups.orEmpty(),
            current = current?.allGroups?.firstOrNull { chat.chatId in it.chatIds }?.id,
            onPick = { vm.moveToGroup(chat.chatId, it); moving = null },
            onCreate = { vm.createGroup(it, chat.chatId); moving = null },
            onDismiss = { moving = null },
        )
    }
    renaming?.let { group ->
        ImGroupNameDialog(R.string.im_chats_rename_group, initial = group.name,
            onConfirm = { vm.renameGroup(group.id, it); renaming = null }, onDismiss = { renaming = null })
    }
    deleting?.let { group ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.im_chats_delete_group_title, group.name)) },
            text = { Text(stringResource(R.string.im_chats_delete_group_desc)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteGroup(group.id); deleting = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private const val LONG_PRESS_HINT_SEEN = "chats_long_press_seen"

private class ImChatActions(
    val open: (ImChatItem) -> Unit,
    val setPinned: (ImChatItem, Boolean) -> Unit,
    val move: (ImChatItem) -> Unit,
    val hide: (ImChatItem) -> Unit,
)

@Composable
private fun ImSearchField(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(HugeIcons.Search01, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = stringResource(R.string.im_chats_search),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Pinned chats as large avatars: one centered, two or three spread out, more in a scrolling row. */
@Composable
private fun ImPinnedChats(chats: List<ImChatItem>, actions: ImChatActions) {
    val modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)
    when {
        chats.size == 1 -> Box(modifier, contentAlignment = Alignment.Center) {
            ImPinnedChat(chats.single(), 96.dp, MaterialTheme.typography.titleMedium, actions)
        }
        chats.size <= 3 -> Row(modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            chats.forEach { ImPinnedChat(it, 76.dp, MaterialTheme.typography.bodyLarge, actions) }
        }
        else -> LazyRow(modifier, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(chats, key = { it.chatId.toString() }) { ImPinnedChat(it, 64.dp, MaterialTheme.typography.bodyMedium, actions) }
        }
    }
}

@Composable
private fun ImPinnedChat(chat: ImChatItem, avatarSize: Dp, nameStyle: TextStyle, actions: ImChatActions) {
    val menu = rememberPressMenu()
    Box {
        Column(
            modifier = Modifier
                .width(avatarSize + 28.dp)
                .clip(RoundedCornerShape(20.dp))
                .imLongPress(menu::open)
                .clickable { actions.open(chat) }
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                // The mascot consumes taps for its own reactions, so it forwards the open action.
                AssistantAvatar(
                    name = chat.assistant.name,
                    value = chat.assistant.avatar,
                    loading = chat.typing,
                    onClick = { actions.open(chat) },
                    modifier = Modifier.size(avatarSize),
                )
                if (chat.unread > 0) ImUnreadBadge(chat.unread, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp))
            }
            Text(
                text = chat.assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                style = nameStyle,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ImPressMenu(menu) {
            ImPressMenuItem(HugeIcons.PinOff, stringResource(R.string.im_p5_unpin)) { menu.close(); actions.setPinned(chat, false) }
            ImPressMenuItem(HugeIcons.Folder01, stringResource(R.string.im_chats_move_to_group)) { menu.close(); actions.move(chat) }
        }
    }
}

@Composable
private fun ImChatGroupHeader(
    section: ImChatGroupSection,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val menu = rememberPressMenu()
    val arrow by animateFloatAsState(if (section.collapsed) -90f else 0f, label = "group arrow")
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imLongPress(menu::open)
                .clickable(onClick = onToggle)
                .padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(section.group.name, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Icon(HugeIcons.ArrowDown01, null, Modifier.size(18.dp).rotate(arrow), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (section.collapsed && section.unread > 0) ImUnreadBadge(section.unread)
        }
        ImPressMenu(menu) {
            ImPressMenuItem(HugeIcons.Edit01, stringResource(R.string.im_chats_rename_group)) { menu.close(); onRename() }
            ImPressMenuItem(HugeIcons.ArrowUp01, stringResource(R.string.im_chats_move_up), enabled = canMoveUp) { menu.close(); onMove(-1) }
            ImPressMenuItem(HugeIcons.ArrowDown01, stringResource(R.string.im_chats_move_down), enabled = canMoveDown) { menu.close(); onMove(1) }
            ImPressMenuItem(HugeIcons.Delete01, stringResource(R.string.im_chats_delete_group), destructive = true) { menu.close(); onDelete() }
        }
    }
}

@Composable
private fun ImChatRow(chat: ImChatItem, actions: ImChatActions) {
    val menu = rememberPressMenu()
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imLongPress(menu::open)
                .clickable { actions.open(chat) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The mascot consumes taps for its own reactions, so it forwards the row action.
            AssistantAvatar(
                name = chat.assistant.name,
                value = chat.assistant.avatar,
                onClick = { actions.open(chat) },
                modifier = Modifier.size(56.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chat.assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = imChatTime(chat.lastActivity),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val preview = when (chat.previewKind) {
                    PreviewKind.IMAGE -> stringResource(R.string.im_p5_image)
                    PreviewKind.FILE -> stringResource(R.string.im_p5_file)
                    else -> chat.preview
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = if (chat.typing) stringResource(R.string.im_chats_typing)
                            else if (chat.previewFromUser) stringResource(R.string.im_p5_you_prefix, preview) else preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (chat.typing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chat.unread > 0) ImUnreadBadge(chat.unread)
                }
            }
        }
        ImPressMenu(menu) {
            ImPressMenuItem(HugeIcons.Pin, stringResource(R.string.im_p5_pin)) { menu.close(); actions.setPinned(chat, true) }
            ImPressMenuItem(HugeIcons.Folder01, stringResource(R.string.im_chats_move_to_group)) { menu.close(); actions.move(chat) }
            ImPressMenuItem(HugeIcons.ViewOff, stringResource(R.string.im_p5_hide)) { menu.close(); actions.hide(chat) }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 88.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun ImChatsEmpty(onFindPartner: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.im_chats_empty_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.im_chats_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onFindPartner) { Text(stringResource(R.string.im_chats_empty_action)) }
        }
    }
}

/** IM list time: time of day today, "Yesterday", month and day this year, otherwise a full date. */
@Composable
private fun imChatTime(instant: Instant): String {
    val date = instant.atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val locale = LocalConfiguration.current.locales[0]
    return when (date.toLocalDate()) {
        today -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(date)
        today.minusDays(1) -> stringResource(R.string.im_time_yesterday)
        else -> date.toLocalDate().toLocalString(includeYear = date.year != today.year)
    }
}
