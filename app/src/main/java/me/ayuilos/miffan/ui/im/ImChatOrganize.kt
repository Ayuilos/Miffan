package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.ChatGroup
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Tick01
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

/** Group names are cut to this length by [me.ayuilos.miffan.data.model.ChatOrganization]. */
private const val GROUP_NAME_MAX = 24

/**
 * Long press anywhere in the element, including over children that handle their own taps (the
 * mascot avatar). Watches the initial pass so children cannot hide the press, and swallows the
 * rest of the gesture once it fires so the release is not also a tap.
 */
internal fun Modifier.imLongPress(onLongPress: (Offset) -> Unit): Modifier = pointerInput(onLongPress) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed || change.isConsumed ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
        }
        if (ended != null) return@awaitEachGesture
        onLongPress(down.position)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** Remembers where a long press happened so its menu opens under the finger. */
internal class PressMenuState {
    var at by mutableStateOf<Offset?>(null)
        private set

    fun open(position: Offset) { at = position }

    fun close() { at = null }
}

@Composable
internal fun rememberPressMenu(): PressMenuState = remember { PressMenuState() }

/** A menu anchored where the long press happened; place it inside the pressed element's [Box]. */
@Composable
internal fun BoxScope.ImPressMenu(state: PressMenuState, content: @Composable ColumnScope.() -> Unit) {
    val at = state.at
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(at != null) { if (at != null) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    Box(Modifier.align(Alignment.TopStart).offset { IntOffset(at?.x?.roundToInt() ?: 0, at?.y?.roundToInt() ?: 0) }) {
        DropdownMenu(expanded = at != null, onDismissRequest = state::close, content = content)
    }
}

@Composable
internal fun ImPressMenuItem(icon: ImageVector, label: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(label, color = if (enabled) color else color.copy(alpha = .38f)) },
        leadingIcon = { Icon(icon, null, tint = if (enabled) color else color.copy(alpha = .38f)) },
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
internal fun ImUnreadBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = Color(0xFFD63B47), modifier = modifier) {
        Text(if (count > 99) "99+" else count.toString(),
            Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall,
            color = Color.White)
    }
}

/** Picks the group a chat moves to; creating a group from here also moves the chat into it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImGroupPickerSheet(
    groups: List<ChatGroup>,
    current: Uuid?,
    onPick: (Uuid?) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        ImGroupNameDialog(R.string.im_chats_new_group, initial = "", onConfirm = { onCreate(it) }, onDismiss = onDismiss)
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(stringResource(R.string.im_chats_move_to_group), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            groups.forEach { group ->
                ImGroupPickerRow(HugeIcons.Folder01, group.name, selected = group.id == current) { onPick(group.id) }
            }
            if (current != null) ImGroupPickerRow(null, stringResource(R.string.im_chats_no_group), selected = false) { onPick(null) }
            ImGroupPickerRow(HugeIcons.Add01, stringResource(R.string.im_chats_new_group), selected = false,
                accent = true) { naming = true }
        }
    }
}

@Composable
private fun ImGroupPickerRow(icon: ImageVector?, label: String, selected: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            if (icon != null) Icon(icon, null, tint = tint) else Box(Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (selected) Icon(HugeIcons.Tick01, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun ImGroupNameDialog(title: Int, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(GROUP_NAME_MAX) },
                placeholder = { Text(stringResource(R.string.im_chats_group_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(name.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(name.trim()) }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
