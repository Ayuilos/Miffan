package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R

private typealias GestureRow = Pair<Int, Int>

private fun gestureRows(trackpad: Boolean, macOS: Boolean): List<GestureRow> = buildList {
    if (trackpad) {
        add(R.string.workspace_screen_help_one_finger_slide to R.string.workspace_screen_help_move_pointer)
        add(R.string.workspace_screen_help_tap to R.string.workspace_screen_help_click)
        add(R.string.workspace_screen_help_double_tap to R.string.workspace_screen_help_double_click)
        add(R.string.workspace_screen_help_double_tap_drag to R.string.workspace_screen_help_drag)
        add(R.string.workspace_screen_help_three_finger_drag to R.string.workspace_screen_help_drag)
    } else {
        add(R.string.workspace_screen_help_tap to R.string.workspace_screen_help_click)
        add(R.string.workspace_screen_help_double_tap to R.string.workspace_screen_help_double_click)
        add(R.string.workspace_screen_help_long_press to R.string.workspace_screen_help_right_click)
        add(R.string.workspace_screen_help_long_press_drag to R.string.workspace_screen_help_drag)
    }
    add(R.string.workspace_screen_help_two_finger_tap to R.string.workspace_screen_help_right_click)
    add(R.string.workspace_screen_help_two_finger_slide to R.string.workspace_screen_help_scroll)
    add(R.string.workspace_screen_help_pinch to
        if (macOS) R.string.workspace_screen_help_zoom_mac else R.string.workspace_screen_help_zoom_other)
    if (macOS) {
        add(R.string.workspace_screen_help_four_left_right to R.string.workspace_screen_help_switch_desktop)
        add(R.string.workspace_screen_help_four_up to R.string.workspace_screen_help_mission_control)
        add(R.string.workspace_screen_help_four_down to R.string.workspace_screen_help_app_windows)
    }
    add(R.string.workspace_screen_help_zoom_buttons to
        if (trackpad) R.string.workspace_screen_help_view_zoom_trackpad else R.string.workspace_screen_help_view_zoom_direct)
}

/** What every gesture does in each input mode; opened from the menu and once per mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteScreenGestureHelp(
    trackpad: Boolean,
    macOS: Boolean,
    onDismiss: () -> Unit,
) {
    var showTrackpad by rememberSaveable(trackpad) { mutableStateOf(trackpad) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.workspace_screen_help), style = MaterialTheme.typography.titleLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to R.string.workspace_screen_help_direct_tab, true to R.string.workspace_screen_help_trackpad_tab)
                    .forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = showTrackpad == value,
                            onClick = { showTrackpad = value },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(stringResource(label)) }
                    }
            }
            Text(
                stringResource(if (showTrackpad) R.string.workspace_screen_help_trackpad_intro else R.string.workspace_screen_help_direct_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                gestureRows(showTrackpad, macOS).forEachIndexed { index, (gesture, action) ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    GestureLine(gesture, action)
                }
            }
            Text(
                stringResource(R.string.workspace_screen_help_switch_mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GestureLine(@StringRes gesture: Int, @StringRes action: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(gesture), Modifier.weight(0.42f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(stringResource(action), Modifier.weight(0.58f), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
