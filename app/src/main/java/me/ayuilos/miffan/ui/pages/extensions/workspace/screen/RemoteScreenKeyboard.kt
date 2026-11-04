package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
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
internal fun RemoteScreenKeyboard(vm: RemoteScreenVM, platform: RemoteScreenPlatform, compact: Boolean = false) {
    val macOS = platform == RemoteScreenPlatform.MACOS
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
        RemoteScreenKeyBar(
            platform = platform,
            modifiers = modifiers,
            onKey = ::sendKey,
            onShortcut = { keysym, held -> vm.key(keysym, held); modifiers = emptySet() },
            onModifier = { modifier ->
                modifiers = if (modifier in modifiers) modifiers - modifier else modifiers + modifier
            },
            onClearModifiers = { modifiers = emptySet() },
            onImeWanted = { focus.requestFocus(); keyboard?.show() },
            onImeUnwanted = { keyboard?.hide() },
            compact = compact,
        )
    }
}
