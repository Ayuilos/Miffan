package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import me.ayuilos.miffan.data.repository.RemoteStreamFallbackReason
import android.content.pm.ActivityInfo
import android.content.res.Resources
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ripple
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AppWindowMac
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.ComputerScreenShare
import me.rerere.hugeicons.stroke.Cube
import me.rerere.hugeicons.stroke.Zap
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Tick02
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
import me.ayuilos.miffan.data.repository.snapshotQuality
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
internal fun remoteScreenModeLabel(resources: Resources, protocol: RemoteDesktopProtocol, window: Boolean = false): String =
    if (window) resources.getString(R.string.workspace_screen_mode_window) else when (protocol) {
    RemoteDesktopProtocol.STREAM -> resources.getString(R.string.workspace_screen_mode_stream)
    RemoteDesktopProtocol.RDP -> "RDP"
    RemoteDesktopProtocol.VNC -> "VNC"
    RemoteDesktopProtocol.SPACE -> resources.getString(R.string.im_computer_detail_user_space)
    RemoteDesktopProtocol.SNAPSHOT -> resources.getString(R.string.workspace_screen_mode_snapshot)
}

/** A picture level's name and what it means for the connection in use. */
internal fun remoteScreenQualityLabel(resources: Resources, quality: RemoteScreenQuality): String = resources.getString(when (quality) {
    RemoteScreenQuality.SAVER -> R.string.workspace_screen_quality_saver
    RemoteScreenQuality.BALANCED -> R.string.workspace_screen_quality_balanced
    RemoteScreenQuality.BEST -> R.string.workspace_screen_quality_best
    RemoteScreenQuality.CUSTOM -> R.string.workspace_screen_quality_custom
})

internal fun remoteScreenQualityDetail(resources: Resources, quality: RemoteScreenQuality, protocol: RemoteDesktopProtocol?,
    custom: RemoteStreamCustom, window: Boolean = false): String =
    if (protocol == RemoteDesktopProtocol.SPACE) spaceQuality(quality, window).let { (dimension, fps) -> resources.getString(R.string.workspace_screen_quality_space, dimension, fps) }
    else if (protocol == RemoteDesktopProtocol.SNAPSHOT) resources.getString(R.string.workspace_screen_quality_snapshot,
        quality.snapshotQuality().maxDimension)
    else if (protocol == RemoteDesktopProtocol.STREAM) streamPicture(quality, custom).let { (height, fps) -> "${remoteStreamHeightLabel(height)} · $fps fps" }
    else resources.getString(R.string.workspace_screen_quality_fps, vncFps(quality))

/** A stream's (height, fps) at [quality]. */
private fun streamPicture(quality: RemoteScreenQuality, custom: RemoteStreamCustom): Pair<Int, Int> = when (quality) {
    RemoteScreenQuality.SAVER -> 720 to 30
    RemoteScreenQuality.BALANCED -> 1080 to 60
    RemoteScreenQuality.BEST -> 1440 to 60
    RemoteScreenQuality.CUSTOM -> custom.height to custom.fps
}

private fun vncFps(quality: RemoteScreenQuality) = when (quality) {
    RemoteScreenQuality.SAVER -> 5
    RemoteScreenQuality.BALANCED, RemoteScreenQuality.CUSTOM -> 10
    RemoteScreenQuality.BEST -> 20
}

/** A Space's picture (long edge, fps) at [quality]; a computer's single window is sharper. Matches RemoteScreenRepository. */
private fun spaceQuality(quality: RemoteScreenQuality, window: Boolean): Pair<Int, Int> = when (quality) {
    RemoteScreenQuality.SAVER -> if (window) 1280 to 20 else 960 to 15
    RemoteScreenQuality.BALANCED -> if (window) 1600 to 30 else 1280 to 30
    else -> if (window) 2400 to 30 else 1920 to 30
}

/**
 * The short tag beside the status line: what the picture is right now. Streams say size and frame rate,
 * plain connections their frame rate, still pictures their width. Null where nothing can be chosen (RDP).
 */
internal fun remoteScreenQualityTag(resources: Resources, chosen: RemoteScreenQuality, protocol: RemoteDesktopProtocol,
    custom: RemoteStreamCustom, window: Boolean): String? = chosen.effectiveFor(protocol).let { quality -> when (protocol) {
    RemoteDesktopProtocol.RDP -> null
    RemoteDesktopProtocol.STREAM -> streamPicture(quality, custom).let { (height, fps) ->
        resources.getString(R.string.workspace_screen_quality_tag_fps, remoteStreamHeightLabel(height), fps) }
    RemoteDesktopProtocol.SPACE -> resources.getString(R.string.workspace_screen_quality_tag_fps, resources.getString(remoteScreenQualityName(quality)),
        spaceQuality(quality, window).second)
    RemoteDesktopProtocol.SNAPSHOT -> resources.getString(R.string.workspace_screen_quality_tag_snapshot, quality.snapshotQuality().maxDimension)
    RemoteDesktopProtocol.VNC -> resources.getString(R.string.workspace_screen_quality_tag_vnc, vncFps(quality))
} }

/**
 * The level a connection really runs at for [this] choice: a custom size and frame rate only shape a
 * stream, elsewhere it runs as VNC's balanced level or the others' sharpest (vncFps, snapshotQuality, openSpace).
 */
internal fun RemoteScreenQuality.effectiveFor(protocol: RemoteDesktopProtocol): RemoteScreenQuality = when {
    this != RemoteScreenQuality.CUSTOM || protocol == RemoteDesktopProtocol.STREAM -> this
    protocol == RemoteDesktopProtocol.VNC -> RemoteScreenQuality.BALANCED
    else -> RemoteScreenQuality.BEST
}

private fun remoteScreenQualityName(quality: RemoteScreenQuality) = when (quality) {
    RemoteScreenQuality.SAVER -> R.string.workspace_screen_quality_saver
    RemoteScreenQuality.BALANCED -> R.string.workspace_screen_quality_balanced
    RemoteScreenQuality.BEST -> R.string.workspace_screen_quality_best
    RemoteScreenQuality.CUSTOM -> R.string.workspace_screen_quality_custom
}

/** The symbol for how the screen is connected, beside the status dot. */
internal fun remoteScreenModeIcon(protocol: RemoteDesktopProtocol, window: Boolean): ImageVector = if (window) HugeIcons.AppWindowMac
    else when (protocol) {
        RemoteDesktopProtocol.STREAM -> HugeIcons.Zap
        RemoteDesktopProtocol.SPACE -> HugeIcons.Cube
        RemoteDesktopProtocol.SNAPSHOT -> HugeIcons.Camera01
        RemoteDesktopProtocol.VNC, RemoteDesktopProtocol.RDP -> HugeIcons.ComputerScreenShare
    }

/**
 * The picture pill: the connection's symbol and, where a level can be chosen, the level now, opening
 * [RemoteQualitySheet]. [label] names the connection for screen readers, [clickLabel] what a tap does.
 * It looks 22dp tall but takes taps over the 48dp a finger needs, without making the status line taller.
 */
@Composable
internal fun RemoteModeTag(icon: ImageVector, label: String, text: String?, clickLabel: String, onClick: () -> Unit,
    modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.layout { measurable, constraints ->
            // Laid out at the pill's own height; the touch area around it reaches above and below.
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val height = MODE_TAG_HEIGHT.roundToPx()
            layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
        }.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = clickLabel, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).indication(interaction, ripple())
                .padding(start = 7.dp, end = if (text != null) 5.dp else 7.dp).height(MODE_TAG_HEIGHT),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, label, Modifier.size(13.dp), tint = tint)
            if (text != null) {
                Text(text, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
                Icon(HugeIcons.ArrowDown01, null, Modifier.size(12.dp).offset(x = (-2).dp), tint = tint)
            }
        }
    }
}

private val MODE_TAG_HEIGHT = 22.dp

/** Picks the picture level: one card per level with what it means for this connection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteQualitySheet(protocol: RemoteDesktopProtocol, window: Boolean, quality: RemoteScreenQuality,
    custom: RemoteStreamCustom, onPick: (RemoteScreenQuality) -> Unit, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.workspace_screen_quality_sheet_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(when (protocol) {
                RemoteDesktopProtocol.STREAM -> R.string.workspace_screen_quality_sheet_stream
                RemoteDesktopProtocol.SNAPSHOT -> R.string.workspace_screen_quality_sheet_snapshot
                RemoteDesktopProtocol.SPACE -> if (window) R.string.workspace_screen_quality_sheet_window else R.string.workspace_screen_quality_sheet_space
                else -> R.string.workspace_screen_quality_sheet_fps
            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp))
            // Only a stream has its own size and frame rate to set.
            RemoteScreenQuality.entries.filter { it != RemoteScreenQuality.CUSTOM || protocol == RemoteDesktopProtocol.STREAM }.forEach { level ->
                val selected = level == quality.effectiveFor(protocol)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onPick(level) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(resources.getString(remoteScreenQualityName(level)), style = MaterialTheme.typography.titleSmall)
                        Text(remoteScreenQualityDetail(resources, level, protocol, custom, window), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (protocol == RemoteDesktopProtocol.STREAM) Text(stringResource(R.string.workspace_screen_quality_gb_per_hour, remoteStreamGigabytesPerHour(level, custom)),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selected) Icon(HugeIcons.Tick02, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    else if (level == RemoteScreenQuality.CUSTOM) Icon(HugeIcons.ArrowRight01, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

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
                DetailRow(stringResource(R.string.workspace_screen_details_mode), if (info.window) stringResource(R.string.workspace_screen_mode_window) else when (info.protocol) {
                    RemoteDesktopProtocol.STREAM -> stringResource(R.string.workspace_screen_details_stream)
                    RemoteDesktopProtocol.RDP -> stringResource(R.string.workspace_screen_details_rdp)
                    RemoteDesktopProtocol.VNC -> "VNC"
                    RemoteDesktopProtocol.SPACE -> stringResource(R.string.im_computer_detail_user_space)
                    RemoteDesktopProtocol.SNAPSHOT -> stringResource(R.string.workspace_screen_details_snapshot)
                })
                DetailRow(stringResource(R.string.workspace_screen_details_path), when {
                    info.relayMediaKbps != null && info.relayMediaKbps > 0 ->
                        stringResource(R.string.workspace_screen_details_path_relay_capped, info.relayMediaKbps / 1000.0)
                    info.relayMediaKbps != null -> stringResource(R.string.workspace_screen_details_path_relay)
                    info.protocol == RemoteDesktopProtocol.STREAM -> stringResource(R.string.workspace_screen_details_path_stream, info.streamAddress ?: "—")
                    else -> stringResource(R.string.workspace_screen_details_path_ssh)
                })
                // A single window streams on its own; the computer's high-performance switch has no say.
                if (info.protocol != RemoteDesktopProtocol.STREAM && !info.window) {
                    DetailRow(stringResource(R.string.workspace_screen_stream), when {
                        info.relayMediaKbps != null -> stringResource(R.string.workspace_screen_details_stream_fallback,
                            streamFallbackReason(resources, RemoteStreamFallbackReason.RELAY))
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
