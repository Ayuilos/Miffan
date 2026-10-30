package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import android.content.pm.ActivityInfo
import android.content.res.Resources
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import me.ayuilos.miffan.data.repository.RemoteStreamCustom
import me.ayuilos.miffan.data.repository.streamConfig
import kotlin.math.roundToInt
import me.ayuilos.miffan.utils.fileSizeToString

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

/** The status line's short name for how the screen is connected. */
internal fun remoteScreenModeLabel(resources: Resources, protocol: RemoteDesktopProtocol): String = when (protocol) {
    RemoteDesktopProtocol.STREAM -> resources.getString(R.string.workspace_screen_mode_stream)
    RemoteDesktopProtocol.RDP -> "RDP"
    RemoteDesktopProtocol.VNC -> "VNC"
    RemoteDesktopProtocol.SPACE -> resources.getString(R.string.im_computer_detail_user_space)
}

/** A picture level's name and what it means for the connection in use. */
internal fun remoteScreenQualityLabel(resources: Resources, quality: RemoteScreenQuality): String = resources.getString(when (quality) {
    RemoteScreenQuality.SAVER -> R.string.workspace_screen_quality_saver
    RemoteScreenQuality.BALANCED -> R.string.workspace_screen_quality_balanced
    RemoteScreenQuality.BEST -> R.string.workspace_screen_quality_best
    RemoteScreenQuality.CUSTOM -> R.string.workspace_screen_quality_custom
})

internal fun remoteScreenQualityDetail(resources: Resources, quality: RemoteScreenQuality, protocol: RemoteDesktopProtocol?,
    custom: RemoteStreamCustom): String =
    if (protocol == RemoteDesktopProtocol.STREAM) when (quality) {
        RemoteScreenQuality.SAVER -> "720p · 30 fps"
        RemoteScreenQuality.BALANCED -> "1080p · 60 fps"
        RemoteScreenQuality.BEST -> "1440p · 60 fps"
        RemoteScreenQuality.CUSTOM -> remoteStreamCustomLabel(custom)
    } else resources.getString(R.string.workspace_screen_quality_fps, when (quality) {
        RemoteScreenQuality.SAVER -> 5
        RemoteScreenQuality.BALANCED, RemoteScreenQuality.CUSTOM -> 10
        RemoteScreenQuality.BEST -> 20
    })

internal fun remoteStreamCustomLabel(custom: RemoteStreamCustom) = "${remoteStreamHeightLabel(custom.height)} · ${custom.fps} fps"

internal fun remoteStreamHeightLabel(height: Int) = if (height == 2160) "4K" else "${height}p"

/** The most a stream at [quality] can use per hour, in GB; a still desktop uses far less. */
internal fun remoteStreamGigabytesPerHour(quality: RemoteScreenQuality, custom: RemoteStreamCustom): String {
    val gigabytes = quality.streamConfig(custom = custom).bitrateKbps * 3600 / 8 / 1_000_000.0
    return if (gigabytes >= 10) gigabytes.roundToInt().toString()
    else (gigabytes * 10).roundToInt().let { tenths -> if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}" }
}

/** Picks the size and frame rate for [RemoteScreenQuality.CUSTOM]. */
@Composable
internal fun RemoteStreamCustomDialog(initial: RemoteStreamCustom, onConfirm: (RemoteStreamCustom) -> Unit, onDismiss: () -> Unit) {
    var height by remember { mutableIntStateOf(initial.height) }
    var fps by remember { mutableIntStateOf(initial.fps) }
    val picked = RemoteStreamCustom(height, fps)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_screen_quality_custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.workspace_screen_quality_custom_resolution), style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    RemoteStreamCustom.HEIGHTS.forEachIndexed { index, option ->
                        SegmentedButton(selected = height == option, onClick = { height = option },
                            shape = SegmentedButtonDefaults.itemShape(index, RemoteStreamCustom.HEIGHTS.size), icon = {}) {
                            Text(remoteStreamHeightLabel(option), maxLines = 1)
                        }
                    }
                }
                Text(stringResource(R.string.workspace_screen_quality_custom_fps), style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    RemoteStreamCustom.FRAME_RATES.forEachIndexed { index, option ->
                        SegmentedButton(selected = fps == option, onClick = { fps = option },
                            shape = SegmentedButtonDefaults.itemShape(index, RemoteStreamCustom.FRAME_RATES.size), icon = {}) {
                            Text("$option", maxLines = 1)
                        }
                    }
                }
                Text(stringResource(R.string.workspace_screen_quality_custom_hint,
                    remoteStreamGigabytesPerHour(RemoteScreenQuality.CUSTOM, picked)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(picked) }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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
                    RemoteDesktopProtocol.SPACE -> stringResource(R.string.im_computer_detail_user_space)
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
