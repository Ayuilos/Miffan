package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.repository.RemoteHostDesktop
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.cuaspace.SpaceWindow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.AppWindow
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.Layers01
import me.rerere.hugeicons.stroke.LockComputer
import me.rerere.hugeicons.stroke.LockKeyhole
import me.rerere.hugeicons.stroke.Logout01
import me.rerere.hugeicons.stroke.Minimize01
import me.rerere.hugeicons.stroke.PackageAdd
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Tick02

// Experimental single-window view (prototype: https://claude.ai/artifact/NLejm5DSEHRWXoP2FT7QaA).
// Copy is not localized yet.

/** What is shown and the way to change it, right above the picture. */
@Composable
internal fun RemoteWindowBar(vm: RemoteScreenVM, onPick: () -> Unit, onFit: () -> Unit, modifier: Modifier = Modifier) {
    val target by vm.target.collectAsStateWithLifecycle()
    val fitted by vm.fitted.collectAsStateWithLifecycle()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(onClick = onPick, modifier = Modifier.weight(1f).height(40.dp), shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(start = 6.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val window = target
                if (window != null) AppBadge(window.app, Modifier.size(28.dp))
                else Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center) { Icon(HugeIcons.Computer, null, Modifier.size(16.dp)) }
                Text(window?.let { listOf(it.app, it.title).filter(String::isNotBlank).joinToString(" · ") } ?: stringResource(R.string.workspace_screen_window_whole_screen),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(HugeIcons.ArrowDown01, stringResource(R.string.workspace_screen_window_switch), Modifier.size(16.dp))
            }
        }
        if (target != null) {
            Surface(onClick = onFit, modifier = Modifier.height(40.dp), shape = CircleShape,
                color = if (fitted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (fitted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(HugeIcons.SmartPhone01, null, Modifier.size(16.dp))
                    Text(if (fitted) stringResource(R.string.workspace_screen_window_fit_restore) else stringResource(R.string.workspace_screen_window_fit_phone), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** The bottom sheet that picks the whole screen or one window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteWindowPicker(vm: RemoteScreenVM, onDismiss: () -> Unit) {
    val target by vm.target.collectAsStateWithLifecycle()
    val windows by vm.windows.collectAsStateWithLifecycle()
    val platform by vm.platform.collectAsStateWithLifecycle()
    // Live while open: windows opened or closed on the computer appear without reopening the sheet.
    DisposableEffect(Unit) {
        vm.watchWindows()
        onDispose { vm.stopWindows() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp)) {
            Text(stringResource(R.string.workspace_screen_window_picker_title), Modifier.padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.titleMedium)
            PickerRow(selected = target == null, title = stringResource(R.string.workspace_screen_window_whole_screen), subtitle = stringResource(R.string.workspace_screen_window_whole_screen_subtitle),
                thumbnail = { Icon(HugeIcons.Computer, null, Modifier.size(22.dp)) }) { vm.showWindow(null); onDismiss() }
            val list = windows
            val opening by vm.opening.collectAsStateWithLifecycle()
            opening?.let { app ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.workspace_screen_window_opening, app), style = MaterialTheme.typography.bodyMedium)
                }
            }
            when {
                list == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                list.setup != null -> WindowSetupCard(list.setup, onInstall = vm::installWindowExtension,
                    onWholeScreen = { vm.showWindow(null); onDismiss() })
                list.choices.isEmpty() -> Text(stringResource(R.string.workspace_screen_window_none), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                val resources = LocalResources.current
                val other = stringResource(R.string.workspace_screen_window_other_group)
                val untitled = stringResource(R.string.workspace_screen_window_untitled)
                val minimizedHint = stringResource(R.string.workspace_screen_window_minimized_hint)
                val backgroundHint = stringResource(R.string.workspace_screen_window_background_hint)
                val backgroundBadge = stringResource(R.string.workspace_screen_window_background_badge)
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    // The desktop in front first, then the others as Mission Control orders them.
                    val groups = remoteWindowGroups(list).map { (desktop, members) ->
                        Triple(remoteWindowHeaderKey(desktop), desktop?.let { remoteHostDesktopLabel(resources, it) } ?: other, members)
                    }
                    groups.forEach { (key, label, members) ->
                    // Keyed by desktop, not by name: two full-screen windows of one app share a name.
                    item(key = key) {
                        Text(label, Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Windows on screen first; background ones last in their group.
                    items(members.sortedBy { it.background }, key = { it.window.id }) { choice ->
                        val window = choice.window
                        PickerRow(
                            selected = target?.id == window.id,
                            title = window.app.ifBlank { untitled },
                            subtitle = when {
                                window.state == SpaceWindow.State.MINIMIZED -> minimizedHint
                                choice.background -> backgroundHint
                                else -> window.title.ifBlank { " " }
                            },
                            badge = if (choice.background) backgroundBadge else null,
                            dimmed = choice.background,
                            thumbnail = {
                                val image = choice.thumbnail
                                if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else if (window.state == SpaceWindow.State.MINIMIZED) Icon(HugeIcons.Minimize01, null, Modifier.size(20.dp))
                                else AppBadge(window.app, Modifier.size(28.dp))
                            },
                        ) { vm.pickWindow(choice, onShown = onDismiss) }
                    }
                    }
                }
                }
            }
            // A Linux computer redraws only windows on its screen, so it brings the chosen one there.
            if (list?.setup == null) Text(stringResource(if (platform == RemoteScreenPlatform.LINUX) R.string.workspace_screen_window_footer_linux
                else R.string.workspace_screen_window_footer),
                Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PickerRow(selected: Boolean, title: String, subtitle: String, thumbnail: @Composable () -> Unit,
    badge: String? = null, dimmed: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(76.dp).height(50.dp).alpha(if (dimmed) 0.6f else 1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center) { thumbnail() }
        Column(Modifier.weight(1f).alpha(if (dimmed) 0.7f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                badge?.let {
                    Text(it, Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(HugeIcons.Tick02, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/** What a GNOME computer needs before its windows can be listed, and the step to take. */
@Composable
private fun WindowSetupCard(setup: me.ayuilos.miffan.data.repository.RemoteWindowSetup, onInstall: () -> Unit, onWholeScreen: () -> Unit) {
    val install = setup == me.ayuilos.miffan.data.repository.RemoteWindowSetup.INSTALL_EXTENSION
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(if (install) HugeIcons.PackageAdd else HugeIcons.Logout01, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(if (install) R.string.workspace_screen_window_gnome_install else R.string.workspace_screen_window_gnome_relogin),
            style = MaterialTheme.typography.titleSmall)
        Text(stringResource(if (install) R.string.workspace_screen_window_gnome_install_detail else R.string.workspace_screen_window_gnome_relogin_detail),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (install) Button(onClick = onInstall, Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.workspace_screen_window_gnome_install_action)) }
        else FilledTonalButton(onClick = onWholeScreen, Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.workspace_screen_window_view_whole)) }
    }
}

/** A colored square with the app's first letter, stable per app name. */
@Composable
private fun AppBadge(app: String, modifier: Modifier = Modifier) {
    val palette = listOf(Color(0xFF2B6A4F), Color(0xFF2F4F86), Color(0xFF7A4B2A), Color(0xFF5E3D7A), Color(0xFF2E6670), Color(0xFF7A3346))
    val color = palette[(app.hashCode() and Int.MAX_VALUE) % palette.size]
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(color), contentAlignment = Alignment.Center) {
        Text(app.trim().take(1).ifEmpty { "?" }, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

/** Why the chosen window shows no picture, and the way out; covers the picture. */
@Composable
internal fun RemoteWindowIssueContent(vm: RemoteScreenVM, issue: RemoteWindowIssue, onPick: () -> Unit, modifier: Modifier = Modifier) {
    val target by vm.target.collectAsStateWithLifecycle()
    val app = target?.app?.ifBlank { null } ?: stringResource(R.string.workspace_screen_window_this_window)
    // The card takes every touch its buttons leave: one falling through would click the computer under it.
    Column(modifier.fillMaxSize().pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false).consume()
            do {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val (icon, title, detail) = when (issue) {
            RemoteWindowIssue.MINIMIZED -> Triple(HugeIcons.Minimize01, stringResource(R.string.workspace_screen_window_issue_minimized, app), stringResource(R.string.workspace_screen_window_issue_minimized_detail))
            RemoteWindowIssue.CLOSED -> Triple(HugeIcons.Cancel01, stringResource(R.string.workspace_screen_window_issue_closed), null)
            RemoteWindowIssue.PERMISSION -> Triple(HugeIcons.Alert02, stringResource(R.string.workspace_screen_window_issue_permission), stringResource(R.string.workspace_screen_window_issue_permission_detail))
            RemoteWindowIssue.OTHER_DESKTOP -> Triple(HugeIcons.Layers01, stringResource(R.string.workspace_screen_window_issue_other_desktop, app), stringResource(R.string.workspace_screen_window_issue_other_desktop_detail))
            RemoteWindowIssue.LOCKED -> Triple(HugeIcons.LockComputer, stringResource(R.string.workspace_screen_window_issue_locked), stringResource(R.string.workspace_screen_window_issue_locked_detail))
            RemoteWindowIssue.NOT_AUTHORIZED -> Triple(HugeIcons.LockKeyhole, stringResource(R.string.workspace_screen_window_issue_authorize), stringResource(R.string.workspace_screen_window_issue_authorize_detail))
            RemoteWindowIssue.PAUSED -> Triple(HugeIcons.Alert02, stringResource(R.string.workspace_screen_window_issue_paused), stringResource(R.string.workspace_screen_window_issue_paused_detail))
        }
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp)) }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        detail?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (issue) {
                RemoteWindowIssue.MINIMIZED -> Button(onClick = vm::restoreWindow) { Text(stringResource(R.string.workspace_screen_window_restore)) }
                RemoteWindowIssue.CLOSED -> {
                    Button(onClick = onPick) { Text(stringResource(R.string.workspace_screen_window_pick_another)) }
                    FilledTonalButton(onClick = { vm.showWindow(null) }) { Text(stringResource(R.string.workspace_screen_window_view_whole)) }
                }
                RemoteWindowIssue.PERMISSION -> Button(onClick = { vm.showWindow(null) }) { Text(stringResource(R.string.workspace_screen_window_permission_action)) }
                RemoteWindowIssue.OTHER_DESKTOP -> Button(onClick = vm::activateWindow) { Text(stringResource(R.string.workspace_screen_window_switch_desktop)) }
                RemoteWindowIssue.LOCKED -> Button(onClick = { vm.showWindow(null) }) { Text(stringResource(R.string.workspace_screen_window_unlock_action)) }
                RemoteWindowIssue.NOT_AUTHORIZED -> {
                    Button(onClick = vm::authorizeWindows) { Text(stringResource(R.string.workspace_screen_window_authorize_action)) }
                    FilledTonalButton(onClick = { vm.showWindow(null) }) { Text(stringResource(R.string.workspace_screen_window_view_whole)) }
                }
                RemoteWindowIssue.PAUSED -> {
                    Button(onClick = onPick) { Text(stringResource(R.string.workspace_screen_window_pick_another)) }
                    FilledTonalButton(onClick = { vm.showWindow(null) }) { Text(stringResource(R.string.workspace_screen_window_view_whole)) }
                }
            }
        }
    }
}

/**
 * The picker's groups: each desktop holding windows (the one in front first, as listed), then the windows
 * on none of them (null). A desktop listed twice counts once, so no window shows twice.
 */
internal fun remoteWindowGroups(list: RemoteWindowList): List<Pair<RemoteHostDesktop?, List<RemoteWindowChoice>>> {
    val desktops = list.desktops.distinctBy { it.id }
    return buildList {
        desktops.forEach { desktop -> add(desktop to list.choices.filter { it.desktop == desktop.id }) }
        add(null to list.choices.filter { choice -> desktops.none { it.id == choice.desktop } })
    }.filter { it.second.isNotEmpty() }
}

/** A group header's key in the picker's list: the desktop's id, which unlike its name is unique. */
internal fun remoteWindowHeaderKey(desktop: RemoteHostDesktop?): String = desktop?.let { "header-${it.id}" } ?: "header-other"

/** A desktop's name in the picker: "Desktop 2", "Full screen · App", with the display when there are several. */
internal fun remoteHostDesktopLabel(resources: android.content.res.Resources, desktop: RemoteHostDesktop): String {
    val name = when {
        desktop.fullscreen -> resources.getString(R.string.workspace_screen_desktop_fullscreen,
            desktop.app ?: resources.getString(R.string.workspace_screen_desktop_app))
        desktop.number != null -> resources.getString(R.string.workspace_screen_desktop, desktop.number)
        else -> return resources.getString(R.string.workspace_screen_desktop_current)
    }
    val placed = desktop.display?.let { resources.getString(R.string.workspace_screen_desktop_display, it, name) } ?: name
    return if (desktop.current) resources.getString(R.string.workspace_screen_desktop_now, placed) else placed
}

/** The landscape side button that opens the picker. */
@Composable
internal fun RemoteWindowSideIcon() = Icon(HugeIcons.AppWindow, stringResource(R.string.workspace_screen_window_side))
