package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import me.rerere.hugeicons.stroke.Minimize01
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
                Text(window?.let { listOf(it.app, it.title).filter(String::isNotBlank).joinToString(" · ") } ?: "整个屏幕",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(HugeIcons.ArrowDown01, "换一个窗口或看整个屏幕", Modifier.size(16.dp))
            }
        }
        if (target != null) {
            Surface(onClick = onFit, modifier = Modifier.height(40.dp), shape = CircleShape,
                color = if (fitted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (fitted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(HugeIcons.SmartPhone01, null, Modifier.size(16.dp))
                    Text(if (fitted) "还原大小" else "适配手机", style = MaterialTheme.typography.labelLarge)
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
    // Live while open: windows opened or closed on the computer appear without reopening the sheet.
    DisposableEffect(Unit) {
        vm.watchWindows()
        onDispose { vm.stopWindows() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp)) {
            Text("看什么", Modifier.padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.titleMedium)
            PickerRow(selected = target == null, title = "整个屏幕", subtitle = "所有窗口和桌面",
                thumbnail = { Icon(HugeIcons.Computer, null, Modifier.size(22.dp)) }) { vm.showWindow(null); onDismiss() }
            val list = windows
            val opening by vm.opening.collectAsStateWithLifecycle()
            opening?.let { app ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在 Mac 上打开 $app…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            when {
                list == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                list.choices.isEmpty() -> Text("没有找到可以单独看的窗口", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    // The desktop in front first, then the others as Mission Control orders them.
                    val groups = buildList {
                        list.desktops.forEach { desktop -> add(desktop.label to list.choices.filter { it.desktop == desktop.id }) }
                        add("其他" to list.choices.filter { choice -> list.desktops.none { it.id == choice.desktop } })
                    }.filter { it.second.isNotEmpty() }
                    groups.forEach { (label, members) ->
                    item(key = "header-$label") {
                        Text(label, Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Windows on screen first; background ones last in their group.
                    items(members.sortedBy { it.background }, key = { it.window.id }) { choice ->
                        val window = choice.window
                        PickerRow(
                            selected = target?.id == window.id,
                            title = window.app.ifBlank { "窗口" },
                            subtitle = when {
                                window.state == SpaceWindow.State.MINIMIZED -> "已最小化，选它会先还原"
                                choice.background -> "在后台运行，选它会在 Mac 上打开"
                                else -> window.title.ifBlank { " " }
                            },
                            badge = if (choice.background) "后台" else null,
                            dimmed = choice.background,
                            thumbnail = {
                                val image = choice.thumbnail
                                if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else if (window.state == SpaceWindow.State.MINIMIZED) Icon(HugeIcons.Minimize01, null, Modifier.size(20.dp))
                                else AppBadge(window.app, Modifier.size(28.dp))
                            },
                        ) { if (opening == null) vm.pickWindow(choice, onShown = onDismiss) }
                    }
                    }
                }
            }
            Text("被别的窗口挡住也能看。你一操作，Mac 会把这个窗口放到最前面，它在别的桌面时会先切过去。",
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
    val app = target?.app?.ifBlank { null } ?: "这个窗口"
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val (icon, title, detail) = when (issue) {
            RemoteWindowIssue.MINIMIZED -> Triple(HugeIcons.Minimize01, "$app 缩到程序坞里了", "最小化的窗口看不到画面")
            RemoteWindowIssue.CLOSED -> Triple(HugeIcons.Cancel01, "这个窗口已经关了", null)
            RemoteWindowIssue.PERMISSION -> Triple(HugeIcons.Alert02, "Mac 弹出了一个确认框", "点了「允许」才能单独看窗口。苹果会隔一段时间要求确认一次。")
            RemoteWindowIssue.OTHER_DESKTOP -> Triple(HugeIcons.Layers01, "$app 在 Mac 的另一个桌面上", "切过去才能看到它")
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
                RemoteWindowIssue.MINIMIZED -> Button(onClick = vm::restoreWindow) { Text("在 Mac 上还原它") }
                RemoteWindowIssue.CLOSED -> {
                    Button(onClick = onPick) { Text("换一个窗口") }
                    FilledTonalButton(onClick = { vm.showWindow(null) }) { Text("看整个屏幕") }
                }
                RemoteWindowIssue.PERMISSION -> Button(onClick = { vm.showWindow(null) }) { Text("去整个屏幕点「允许」") }
                RemoteWindowIssue.OTHER_DESKTOP -> Button(onClick = vm::activateWindow) { Text("切到那个桌面") }
            }
        }
    }
}

/** The landscape side button that opens the picker. */
@Composable
internal fun RemoteWindowSideIcon() = Icon(HugeIcons.AppWindow, "换窗口")
