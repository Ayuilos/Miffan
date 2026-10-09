package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.CarouselHorizontal
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.DashboardSquare01
import me.rerere.hugeicons.stroke.MinusSign
import me.rerere.hugeicons.stroke.PlusSign

/** The Mac's four-finger trackpad gestures, sent as their Control-arrow shortcuts. */
internal enum class MacShortcut { DESKTOP_LEFT, MISSION_CONTROL, APP_WINDOWS, DESKTOP_RIGHT }

/**
 * The one floating control on the screen: enlarging the picture on the phone, and on a Mac the
 * desktop button that unfolds what a four-finger swipe would do on its trackpad.
 */
@Composable
internal fun RemoteScreenControls(
    canZoomOut: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    /** Null when the computer is not a Mac. */
    onMacShortcut: ((MacShortcut) -> Unit)?,
    macExpanded: Boolean,
    onMacExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onMacShortcut != null) AnimatedVisibility(
            visible = macExpanded,
            enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
        ) {
            Surface(color = container, shape = RoundedCornerShape(50), shadowElevation = 3.dp) {
                Row(Modifier.padding(horizontal = 6.dp)) {
                    MacShortcutButton(HugeIcons.ArrowLeft01, R.string.workspace_screen_desktop_short, R.string.workspace_screen_desktop_left) {
                        onMacShortcut(MacShortcut.DESKTOP_LEFT)
                    }
                    MacShortcutButton(HugeIcons.DashboardSquare01, R.string.workspace_screen_help_mission_control, R.string.workspace_screen_help_mission_control) {
                        onMacShortcut(MacShortcut.MISSION_CONTROL)
                    }
                    MacShortcutButton(HugeIcons.Copy01, R.string.workspace_screen_app_windows_short, R.string.workspace_screen_help_app_windows) {
                        onMacShortcut(MacShortcut.APP_WINDOWS)
                    }
                    MacShortcutButton(HugeIcons.ArrowRight01, R.string.workspace_screen_desktop_short, R.string.workspace_screen_desktop_right) {
                        onMacShortcut(MacShortcut.DESKTOP_RIGHT)
                    }
                }
            }
        }
        Surface(color = container, shape = RoundedCornerShape(50), shadowElevation = 3.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onZoomIn, modifier = Modifier.size(44.dp)) {
                    Icon(HugeIcons.PlusSign, stringResource(R.string.workspace_screen_view_zoom_in), Modifier.size(20.dp))
                }
                IconButton(onClick = onZoomOut, enabled = canZoomOut, modifier = Modifier.size(44.dp)) {
                    Icon(HugeIcons.MinusSign, stringResource(R.string.workspace_screen_view_zoom_out), Modifier.size(20.dp))
                }
                if (onMacShortcut != null) {
                    HorizontalDivider(Modifier.width(20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    IconButton(
                        onClick = { onMacExpandedChange(!macExpanded) },
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = if (macExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Icon(HugeIcons.CarouselHorizontal, stringResource(R.string.workspace_screen_mac_controls), Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MacShortcutButton(icon: ImageVector, @StringRes label: Int, @StringRes description: Int, onClick: () -> Unit) {
    val text = stringResource(description)
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(50),
        modifier = Modifier.widthIn(min = 64.dp).semantics { contentDescription = text },
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
