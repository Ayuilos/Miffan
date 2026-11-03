package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.DashboardSquare01
import me.rerere.hugeicons.stroke.Keyboard
import me.rerere.workspace.screen.RfbKeys

private val KEY_HEIGHT = 42.dp
private val KEY_SHAPE = RoundedCornerShape(10.dp)
private val PANEL_FALLBACK_HEIGHT = 280.dp

/**
 * The keys a phone keyboard lacks. The top row holds keys that do something on their own; modifier
 * keys and shortcuts live in a panel that takes the input method's place, like a chat app's emoji
 * panel, so the row never jumps when the two swap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteScreenKeyBar(
    platform: RemoteScreenPlatform,
    modifiers: Set<RemoteModifier>,
    onKey: (Int) -> Unit,
    onShortcut: (Int, Set<RemoteModifier>) -> Unit,
    onModifier: (RemoteModifier) -> Unit,
    onClearModifiers: () -> Unit,
    onImeWanted: () -> Unit,
    onImeUnwanted: () -> Unit,
    compact: Boolean = false,
) {
    val density = LocalDensity.current
    val orientation = LocalConfiguration.current.orientation
    // The input method's height, learned while it is open, so the panel can fill exactly its place.
    var imeHeight by remember(orientation) { mutableIntStateOf(0) }
    val imeNow = (WindowInsets.ime.getBottom(density) - WindowInsets.navigationBars.getBottom(density)).coerceAtLeast(0)
    LaunchedEffect(imeNow) { if (imeNow > imeHeight) imeHeight = imeNow }
    var panel by remember { mutableStateOf(false) }
    // While the input method slides back in, the panel's space shrinks with it instead of vanishing.
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(closing, imeNow) {
        if (closing && (imeHeight == 0 || imeNow >= imeHeight)) closing = false
    }
    LaunchedEffect(closing) { if (closing) { delay(800); closing = false } }
    fun openPanel() { panel = true; closing = false; onImeUnwanted() }
    // In landscape the input method already leaves little room for the desktop, so the panel is one
    // scrolling strip instead of taking the input method's place; the desktop gains the difference.
    fun closePanel() { panel = false; closing = !compact; onImeWanted() }
    val pickModifier: (RemoteModifier) -> Unit = { onModifier(it); if (it !in modifiers) closePanel() }

    Column(Modifier.fillMaxWidth()) {
        // Floats above the row instead of pushing it down, so the keys stay where the thumb expects them.
        if (modifiers.isNotEmpty()) Box(Modifier.fillMaxWidth().zIndex(1f).layout { measurable, constraints ->
            val pill = measurable.measure(constraints.copy(minHeight = 0))
            layout(constraints.maxWidth, 0) { pill.place(0, -pill.height) }
        }) { PendingCombo(platform, modifiers, onClearModifiers) }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (panel) {
                    KeyCap(stringResource(R.string.workspace_screen_key_back_to_ime), selected = true, onClick = ::closePanel) {
                        Icon(HugeIcons.Keyboard, null, Modifier.size(20.dp))
                    }
                } else {
                    KeyCap(stringResource(R.string.workspace_screen_key_panel), onClick = ::openPanel) {
                        Icon(HugeIcons.DashboardSquare01, null, Modifier.size(20.dp))
                    }
                }
                KeyCap(onClick = { onKey(RfbKeys.ESCAPE) }) { KeyLabel("Esc") }
                KeyCap(onClick = { onKey(RfbKeys.TAB) }) { KeyLabel("Tab") }
                ArrowKey(HugeIcons.ArrowLeft01, R.string.workspace_screen_key_left) { onKey(RfbKeys.LEFT) }
                ArrowKey(HugeIcons.ArrowUp01, R.string.workspace_screen_key_up) { onKey(RfbKeys.UP) }
                ArrowKey(HugeIcons.ArrowDown01, R.string.workspace_screen_key_down) { onKey(RfbKeys.DOWN) }
                ArrowKey(HugeIcons.ArrowRight01, R.string.workspace_screen_key_right) { onKey(RfbKeys.RIGHT) }
                KeyCap("Enter", emphasis = true, onClick = { onKey(RfbKeys.RETURN) }) {
                    Icon(HugeIcons.ArrowTurnBackward, null, Modifier.size(20.dp))
                }
            }
        }
        if (compact) {
            if (panel) Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                ShortcutStrip(platform, modifiers, onKey, onShortcut, pickModifier)
            }
        } else if (panel || closing) {
            val height = when {
                imeHeight == 0 -> PANEL_FALLBACK_HEIGHT
                else -> with(density) { (imeHeight - imeNow).coerceAtLeast(0).toDp() }
            }
            Surface(Modifier.fillMaxWidth().height(height), color = MaterialTheme.colorScheme.surfaceContainer) {
                if (panel) ShortcutPanel(
                    platform = platform,
                    modifiers = modifiers,
                    onKey = onKey,
                    onShortcut = onShortcut,
                    // A modifier waits for the next key, which is usually typed: bring the input method back.
                    onModifier = pickModifier,
                )
            }
        }
    }
}

private data class Shortcut(val name: Int, val key: Char, val held: Set<RemoteModifier>) {
    val keysym get() = if (key == '\t') RfbKeys.TAB else key.code
}

private fun shortcuts(platform: RemoteScreenPlatform): List<Shortcut> {
    val command = setOf(RemoteModifier.COMMAND)
    return listOf(
        Shortcut(R.string.workspace_screen_shortcut_copy, 'c', command),
        Shortcut(R.string.workspace_screen_shortcut_paste, 'v', command),
        Shortcut(R.string.workspace_screen_shortcut_cut, 'x', command),
        Shortcut(R.string.workspace_screen_shortcut_undo, 'z', command),
        Shortcut(R.string.workspace_screen_shortcut_select_all, 'a', command),
        Shortcut(R.string.workspace_screen_shortcut_save, 's', command),
        Shortcut(R.string.workspace_screen_shortcut_find, 'f', command),
        Shortcut(R.string.workspace_screen_shortcut_switch_window, '\t',
            if (platform == RemoteScreenPlatform.MACOS) command else setOf(RemoteModifier.ALT)),
    )
}

@Composable
private fun moreKeys(platform: RemoteScreenPlatform): List<Pair<String, Int>> {
    val superAlone = stringResource(
        if (platform == RemoteScreenPlatform.WINDOWS) R.string.workspace_screen_key_start_menu else R.string.workspace_screen_key_overview,
    )
    return buildList {
        add("Home" to RfbKeys.HOME); add("End" to RfbKeys.END)
        add("PgUp" to RfbKeys.PAGE_UP); add("PgDn" to RfbKeys.PAGE_DOWN); add("Del" to RfbKeys.DELETE)
        // Win or Super alone opens the Start menu or the activities overview.
        if (platform != RemoteScreenPlatform.MACOS) add(superAlone to RfbKeys.SUPER_L)
        (1..12).forEach { add("F$it" to RfbKeys.function(it)) }
    }
}

/** The landscape panel: everything in one horizontally scrolling row of keys. */
@Composable
private fun ShortcutStrip(
    platform: RemoteScreenPlatform,
    modifiers: Set<RemoteModifier>,
    onKey: (Int) -> Unit,
    onShortcut: (Int, Set<RemoteModifier>) -> Unit,
    onModifier: (RemoteModifier) -> Unit,
) {
    val macOS = platform == RemoteScreenPlatform.MACOS
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        shortcuts(platform).forEach { shortcut ->
            KeyCap(fill = false, onClick = { onShortcut(shortcut.keysym, shortcut.held) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyLabel(stringResource(shortcut.name))
                    Text(shortcutLabel(macOS, shortcut.held, shortcut.key), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        StripDivider()
        modifierKeys(platform).forEach { (modifier, label) ->
            KeyCap(fill = false, selected = modifier in modifiers, onClick = { onModifier(modifier) }) { KeyLabel(label) }
        }
        StripDivider()
        moreKeys(platform).forEach { (label, keysym) ->
            KeyCap(fill = false, onClick = { onKey(keysym) }) { KeyLabel(label, small = true) }
        }
    }
}

@Composable
private fun StripDivider() {
    Box(Modifier.padding(horizontal = 3.dp).width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun ShortcutPanel(
    platform: RemoteScreenPlatform,
    modifiers: Set<RemoteModifier>,
    onKey: (Int) -> Unit,
    onShortcut: (Int, Set<RemoteModifier>) -> Unit,
    onModifier: (RemoteModifier) -> Unit,
) {
    val macOS = platform == RemoteScreenPlatform.MACOS
    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(R.string.workspace_screen_key_section_common))
        shortcuts(platform).chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { shortcut ->
                    ShortcutChip(stringResource(shortcut.name), shortcutLabel(macOS, shortcut.held, shortcut.key)) {
                        onShortcut(shortcut.keysym, shortcut.held)
                    }
                }
            }
        }
        SectionLabel(stringResource(R.string.workspace_screen_key_section_modifiers))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            modifierKeys(platform).forEach { (modifier, label) ->
                KeyCap(selected = modifier in modifiers, onClick = { onModifier(modifier) }) { KeyLabel(label) }
            }
        }
        SectionLabel(stringResource(R.string.workspace_screen_key_section_more))
        val more = moreKeys(platform)
        more.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (label, keysym) -> KeyCap(onClick = { onKey(keysym) }) { KeyLabel(label, small = true) } }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun modifierKeys(platform: RemoteScreenPlatform): List<Pair<RemoteModifier, String>> = when (platform) {
    RemoteScreenPlatform.MACOS -> listOf(RemoteModifier.SUPER to "⌘", RemoteModifier.ALT to "⌥", RemoteModifier.CONTROL to "⌃", RemoteModifier.SHIFT to "⇧")
    RemoteScreenPlatform.WINDOWS -> listOf(RemoteModifier.CONTROL to "Ctrl", RemoteModifier.ALT to "Alt", RemoteModifier.SHIFT to "Shift", RemoteModifier.SUPER to "Win")
    else -> listOf(RemoteModifier.CONTROL to "Ctrl", RemoteModifier.ALT to "Alt", RemoteModifier.SHIFT to "Shift", RemoteModifier.SUPER to "Super")
}

private fun modifierName(platform: RemoteScreenPlatform, modifier: RemoteModifier): String =
    modifierKeys(platform).firstOrNull { it.first == modifier }?.second ?: when (modifier) {
        RemoteModifier.COMMAND -> if (platform == RemoteScreenPlatform.MACOS) "⌘" else "Ctrl"
        else -> modifier.name
    }

private fun shortcutLabel(macOS: Boolean, held: Set<RemoteModifier>, key: Char): String {
    val keyName = if (key == '\t') "Tab" else key.uppercase()
    val prefix = held.joinToString(if (macOS) "" else "+") {
        when (it) {
            RemoteModifier.COMMAND, RemoteModifier.SUPER -> if (macOS) "⌘" else "Ctrl"
            RemoteModifier.ALT -> if (macOS) "⌥" else "Alt"
            RemoteModifier.CONTROL -> if (macOS) "⌃" else "Ctrl"
            RemoteModifier.SHIFT -> if (macOS) "⇧" else "Shift"
        }
    }
    return if (macOS) "$prefix$keyName" else "$prefix+$keyName"
}

/** Held modifiers wait above the row until the next key, so it is clear why typing will combine. */
@Composable
private fun PendingCombo(platform: RemoteScreenPlatform, modifiers: Set<RemoteModifier>, onClear: () -> Unit) {
    val names = modifierKeys(platform).map { it.first }.filter { it in modifiers }.map { modifierName(platform, it) }
    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
            Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(names.joinToString(" + ") + " +", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.workspace_screen_key_waiting), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f))
                IconButton(onClick = onClear, Modifier.size(36.dp)) {
                    Icon(HugeIcons.Cancel01, stringResource(R.string.workspace_screen_key_cancel_modifiers), Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(top = 6.dp, start = 2.dp), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RowScope.ShortcutChip(name: String, keys: String, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Surface(onClick = { haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap); onClick() },
        modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(keys, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun KeyLabel(text: String, small: Boolean = false) {
    Text(text, fontSize = if (small) 13.sp else 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
}

/** Arrow keys repeat while held, like on a real keyboard, so the caret can travel. */
@Composable
private fun RowScope.ArrowKey(icon: androidx.compose.ui.graphics.vector.ImageVector, description: Int, onPress: () -> Unit) {
    KeyCap(stringResource(description), repeat = true, onClick = onPress) { Icon(icon, null, Modifier.size(20.dp)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.KeyCap(
    contentDescription: String? = null,
    selected: Boolean = false,
    emphasis: Boolean = false,
    repeat: Boolean = false,
    height: Dp = KEY_HEIGHT,
    fill: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val clickPressed by interaction.collectIsPressedAsState()
    var holdPressed by remember { mutableStateOf(false) }
    val pressed = clickPressed || holdPressed
    val (container, contentColor) = when {
        selected -> scheme.primary to scheme.onPrimary
        emphasis -> scheme.inverseSurface to scheme.inverseOnSurface
        pressed -> scheme.surfaceContainerHighest to scheme.onSurface
        else -> scheme.surfaceContainerLowest to scheme.onSurface
    }
    val press by rememberUpdatedState(onClick)
    val scope = rememberCoroutineScope()
    val input = if (repeat) {
        Modifier
            .semantics {
                role = Role.Button
                contentDescription?.let { this.contentDescription = it }
                onClick { press(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    holdPressed = true
                    haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    press()
                    val repeater = scope.launch {
                        delay(400)
                        while (true) { press(); delay(60) }
                    }
                    waitForUpOrCancellation()
                    repeater.cancel()
                    holdPressed = false
                }
            }
    } else {
        Modifier
            .semantics { contentDescription?.let { this.contentDescription = it } }
            .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap); onClick() })
    }
    Surface(
        modifier = (if (fill) Modifier.weight(1f) else Modifier.widthIn(min = 48.dp)).height(height),
        shape = KEY_SHAPE,
        color = container,
        contentColor = contentColor,
        border = if (selected || emphasis) null else BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = if (pressed) 1f else 0.7f)),
        shadowElevation = if (pressed || selected) 0.dp else 1.dp,
    ) {
        Box(Modifier.fillMaxSize().then(input).padding(horizontal = if (fill) 0.dp else 12.dp),
            contentAlignment = Alignment.Center) { content() }
    }
}
