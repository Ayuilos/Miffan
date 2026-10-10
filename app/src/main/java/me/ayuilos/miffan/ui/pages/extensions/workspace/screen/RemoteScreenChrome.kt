package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import android.content.pm.ActivityInfo
import android.content.res.Resources
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.repository.RemoteDesktopProtocol
import me.ayuilos.miffan.data.repository.RemoteScreenQuality
import me.ayuilos.miffan.utils.fileSizeToString
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Keyboard
import me.rerere.hugeicons.stroke.MinimizeScreen

/**
 * Full screen for the remote desktop: landscape, no system bars (a swipe from the edge shows
 * them briefly). Everything is restored when [enabled] turns off or the page leaves.
 */
@Composable
internal fun RemoteScreenFullscreenEffect(enabled: Boolean) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    DisposableEffect(enabled, activity, view) {
        if (!enabled) return@DisposableEffect onDispose {}
        val previousOrientation = activity.requestedOrientation
        val controller = WindowCompat.getInsetsController(activity.window, view)
        val previousBehavior = controller.systemBarsBehavior
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
            activity.requestedOrientation = previousOrientation
        }
    }
}

/** The only controls left in full screen: a faint pill so it never hides much of the desktop. */
@Composable
internal fun RemoteScreenFullscreenControls(keyboard: Boolean, onKeyboard: () -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.45f), contentColor = Color.White, shape = CircleShape, modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            IconButton(onClick = onKeyboard) {
                Icon(HugeIcons.Keyboard, stringResource(R.string.workspace_screen_keyboard),
                    tint = if (keyboard) MaterialTheme.colorScheme.primary else Color.White)
            }
            IconButton(onClick = onExit) { Icon(HugeIcons.MinimizeScreen, stringResource(R.string.workspace_screen_fullscreen_exit)) }
        }
    }
}

/** The status line's short name for how the screen is connected. */
internal fun remoteScreenModeLabel(resources: Resources, protocol: RemoteDesktopProtocol): String = when (protocol) {
    RemoteDesktopProtocol.STREAM -> resources.getString(R.string.workspace_screen_mode_stream)
    RemoteDesktopProtocol.RDP -> "RDP"
    RemoteDesktopProtocol.VNC -> "VNC"
}

/** A picture level's name and what it means for the connection in use. */
internal fun remoteScreenQualityLabel(resources: Resources, quality: RemoteScreenQuality): String = resources.getString(when (quality) {
    RemoteScreenQuality.SAVER -> R.string.workspace_screen_quality_saver
    RemoteScreenQuality.BALANCED -> R.string.workspace_screen_quality_balanced
    RemoteScreenQuality.BEST -> R.string.workspace_screen_quality_best
})

internal fun remoteScreenQualityDetail(resources: Resources, quality: RemoteScreenQuality, protocol: RemoteDesktopProtocol?): String =
    if (protocol == RemoteDesktopProtocol.STREAM) when (quality) {
        RemoteScreenQuality.SAVER -> "720p · 30 fps"
        RemoteScreenQuality.BALANCED -> "1080p · 60 fps"
        RemoteScreenQuality.BEST -> "1440p · 60 fps"
    } else resources.getString(R.string.workspace_screen_quality_fps, when (quality) {
        RemoteScreenQuality.SAVER -> 5
        RemoteScreenQuality.BALANCED -> 10
        RemoteScreenQuality.BEST -> 20
    })

/** The most a stream at [quality] can use per hour, in GB; a still desktop uses far less. */
internal fun remoteStreamGigabytesPerHour(quality: RemoteScreenQuality): String = when (quality) {
    RemoteScreenQuality.SAVER -> "1.8"
    RemoteScreenQuality.BALANCED -> "4.5"
    RemoteScreenQuality.BEST -> "9"
}

/** What the status line summarises: how the screen is connected and why. */
@Composable
internal fun RemoteConnectionDetailsDialog(info: RemoteConnectionInfo, bytes: Long, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_screen_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailRow(stringResource(R.string.workspace_screen_details_mode), when (info.protocol) {
                    RemoteDesktopProtocol.STREAM -> stringResource(R.string.workspace_screen_details_stream)
                    RemoteDesktopProtocol.RDP -> stringResource(R.string.workspace_screen_details_rdp)
                    RemoteDesktopProtocol.VNC -> "VNC"
                })
                DetailRow(stringResource(R.string.workspace_screen_details_path),
                    if (info.protocol == RemoteDesktopProtocol.STREAM) stringResource(R.string.workspace_screen_details_path_stream, info.streamAddress ?: "—")
                    else stringResource(R.string.workspace_screen_details_path_ssh))
                if (info.protocol != RemoteDesktopProtocol.STREAM) {
                    DetailRow(stringResource(R.string.workspace_screen_stream), when {
                        info.streamSkipped -> stringResource(R.string.workspace_screen_details_stream_skipped)
                        info.fallback != null -> stringResource(R.string.workspace_screen_details_stream_fallback,
                            streamFallbackReason(resources, info.fallback.reason))
                        else -> stringResource(R.string.workspace_screen_details_stream_off)
                    })
                }
                DetailRow(stringResource(R.string.workspace_screen_details_usage), bytes.fileSizeToString())
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_confirm)) } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
