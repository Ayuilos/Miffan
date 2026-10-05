package me.ayuilos.miffan.ui.im

import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clipToBounds
import me.ayuilos.miffan.data.thread.PreviewKind
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Search01
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.utils.toLocalString
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
    val chats by vm.chats.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("im_ui_hints", Context.MODE_PRIVATE) }
    val showHint = rememberSaveable { !prefs.getBoolean("chats_seen", false) }
    LaunchedEffect(Unit) { prefs.edit { putBoolean("chats_seen", true) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = innerPadding,
    ) {
        item("title") { ImTabTitle(R.string.im_tab_chats) }
        item("search") {
            ImSearchField(onClick = { navController.navigate(Screen.ImSearch) })
        }
        val items = chats
        if (items != null && items.isEmpty()) {
            item("empty") { ImChatsEmpty(onFindPartner) }
        }
        items(items.orEmpty(), key = { it.assistant.id.toString() }) { chat ->
            ImChatRow(
                chat = chat,
                onPin = { vm.setPinned(chat.assistant, !chat.pinned) },
                onHide = { vm.hide(chat.assistant) },
                onClick = { navController.navigate(Screen.Thread(chat.assistant.id.toString())) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 88.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
        if (showHint) item("hint") {
            Text(stringResource(R.string.im_p5_hide_hint), Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

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

@Composable
private fun ImChatRow(chat: ImChatItem, onClick: () -> Unit, onPin: () -> Unit, onHide: () -> Unit) {
    val actionWidth = 160.dp
    val width = with(LocalDensity.current) { actionWidth.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxWidth().clipToBounds()) {
        Row(Modifier.align(Alignment.CenterEnd).width(actionWidth)) {
            Surface(onClick = { offset = 0f; onPin() }, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)) {
                Text(stringResource(if (chat.pinned) R.string.im_p5_unpin else R.string.im_p5_pin),
                    Modifier.padding(horizontal = 8.dp, vertical = 28.dp), style = MaterialTheme.typography.labelLarge)
            }
            Surface(onClick = onHide, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.im_p5_hide), Modifier.padding(horizontal = 8.dp, vertical = 28.dp),
                    style = MaterialTheme.typography.labelLarge)
            }
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(offset.roundToInt(), 0) }
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(width) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount -> change.consume(); offset = (offset + amount).coerceIn(-width, 0f) },
                    onDragEnd = { offset = if (offset < -width / 3) -width else 0f },
                    onDragCancel = { offset = 0f },
                )
            }
            .clickable { if (offset != 0f) offset = 0f else onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The mascot consumes taps for its own reactions, so it forwards the row action.
        AssistantAvatar(
            name = chat.assistant.name,
            value = chat.assistant.avatar,
            onClick = onClick,
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
            if (chat.pinned) Text(stringResource(R.string.im_p5_pin), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            if (chat.unread > 0) Surface(shape = CircleShape, color = androidx.compose.ui.graphics.Color(0xFFD63B47)) {
                Text(if (chat.unread > 99) "99+" else chat.unread.toString(),
                    Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White)
            }
            }
        }
    }
    }
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
