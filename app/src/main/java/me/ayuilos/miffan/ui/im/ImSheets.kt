package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar

/**
 * Easy mode's way to type one short value (a nickname, a group name): a sheet rising with the keyboard, the field
 * beside its confirm button, instead of a centered dialog. Destructive confirmations stay dialogs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImTextInputSheet(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    subtitle: String? = null,
    placeholder: String? = null,
    maxLength: Int = Int.MAX_VALUE,
    allowBlank: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = allowBlank || field.text.isNotBlank()
    val confirm = { if (valid) onConfirm(field.text.trim()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                leading?.invoke()
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                        BasicTextField(
                            value = field,
                            onValueChange = { if (it.text.length <= maxLength) field = it },
                            singleLine = true,
                            textStyle = textStyle,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { confirm() }),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp).focusRequester(focus),
                            decorationBox = { inner ->
                                if (field.text.isEmpty() && placeholder != null) {
                                    Text(placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                inner()
                            },
                        )
                    }
                }
                Button(onClick = confirm, enabled = valid, modifier = Modifier.heightIn(min = 52.dp)) { Text(confirmLabel) }
            }
        }
    }
}

/** Picking one partner for something, as a sheet of avatar rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImPartnerPickSheet(
    title: String,
    partners: List<Assistant>,
    onDismiss: () -> Unit,
    onPick: (Assistant) -> Unit,
    supporting: @Composable (Assistant) -> String? = { null },
    footer: (@Composable () -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(partners, key = { it.id.toString() }) { partner ->
                    Surface(onClick = { onPick(partner) }, color = Color.Transparent, shape = MaterialTheme.shapes.large) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            AssistantAvatar(partner.name, partner.avatar, Modifier.size(40.dp), onClick = { onPick(partner) })
                            Column(Modifier.weight(1f)) {
                                Text(partnerName(partner), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                supporting(partner)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            footer?.invoke()
        }
    }
}
