package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import me.rerere.workspace.screen.RfbKeys

// Keeping one invisible character lets IMEs report deleteSurroundingText as a value change.
private const val INPUT_ANCHOR = "\u200B"
private fun emptyInput() = TextFieldValue(INPUT_ANCHOR, TextRange(1))

@Composable
internal fun RemoteScreenKeyboard(vm: RemoteScreenVM, macOS: Boolean) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember { mutableStateOf(emptyInput()) }
    /** Text already delivered to the remote side, excluding the anchor. */
    var sent by remember { mutableStateOf("") }
    var modifiers by remember { mutableStateOf(emptySet<RemoteModifier>()) }
    fun sendKey(keysym: Int) { vm.key(keysym, modifiers); modifiers = emptySet() }
    fun sendText(text: String) {
        val points = text.codePoints().toArray()
        var plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { vm.typeText(plain.toString()); plain = StringBuilder() } }
        points.forEach { point ->
            when {
                point == '\n'.code -> { flush(); sendKey(RfbKeys.RETURN) }
                point == '\t'.code -> { flush(); sendKey(RfbKeys.TAB) }
                modifiers.isNotEmpty() -> { flush(); sendKey(point) }
                else -> plain.appendCodePoint(point)
            }
        }
        flush()
    }
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    Box {
        BasicTextField(
            value = value,
            onValueChange = { updated ->
                value = updated
                // No events leave the phone while an IME is composing (e.g. uncommitted pinyin).
                if (updated.composition != null) return@BasicTextField
                // The field keeps what was typed instead of being cleared after each send: an IME
                // that still holds the old text would otherwise commit it a second time. Only the
                // difference to what was already sent goes to the remote side.
                if (!updated.text.startsWith(INPUT_ANCHOR)) {
                    repeat(sent.length + 1) { sendKey(RfbKeys.BACKSPACE) }
                    sent = ""
                    value = emptyInput()
                    return@BasicTextField
                }
                val current = updated.text.removePrefix(INPUT_ANCHOR)
                val common = current.commonPrefixWith(sent).length
                repeat(sent.length - common) { sendKey(RfbKeys.BACKSPACE) }
                val added = current.substring(common)
                if (added.isNotEmpty()) sendText(added)
                sent = current
            },
            modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus).onPreviewKeyEvent { event ->
                val keysym = when (event.key) {
                    Key.Backspace -> RfbKeys.BACKSPACE
                    Key.Enter, Key.NumPadEnter -> RfbKeys.RETURN
                    else -> null
                }
                if (keysym != null && value.composition == null) {
                    if (event.type == KeyEventType.KeyDown) sendKey(keysym)
                    true
                } else false
            },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.None),
            keyboardActions = KeyboardActions(onDone = { sendKey(RfbKeys.RETURN) }),
        )
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            fun toggle(modifier: RemoteModifier) {
                modifiers = if (modifier in modifiers) modifiers - modifier else modifiers + modifier
            }
            ScreenKey("Esc") { sendKey(RfbKeys.ESCAPE) }
            ScreenKey("Tab") { sendKey(RfbKeys.TAB) }
            ScreenKey("Ctrl", RemoteModifier.CONTROL in modifiers) { toggle(RemoteModifier.CONTROL) }
            ScreenKey("Alt", RemoteModifier.ALT in modifiers) { toggle(RemoteModifier.ALT) }
            ScreenKey(if (macOS) "⌘" else "Super", RemoteModifier.SUPER in modifiers) { toggle(RemoteModifier.SUPER) }
            ScreenKey("Shift", RemoteModifier.SHIFT in modifiers) { toggle(RemoteModifier.SHIFT) }
            listOf("←" to RfbKeys.LEFT, "↑" to RfbKeys.UP, "↓" to RfbKeys.DOWN, "→" to RfbKeys.RIGHT,
                "Home" to RfbKeys.HOME, "End" to RfbKeys.END, "PgUp" to RfbKeys.PAGE_UP, "PgDn" to RfbKeys.PAGE_DOWN,
            ).forEach { (label, code) -> ScreenKey(label) { sendKey(code) } }
            (1..12).forEach { n -> ScreenKey("F$n") { sendKey(RfbKeys.function(n)) } }
            ScreenKey("Delete") { sendKey(RfbKeys.DELETE) }
        }
    }
}

@Composable
private fun ScreenKey(label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick, Modifier.heightIn(min = 48.dp).then(
        if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small) else Modifier,
    ), enabled = enabled) { Text(label) }
}
