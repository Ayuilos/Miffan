package me.ayuilos.miffan.ui.pages.update

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.datastore.UpdateReminder
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.components.ui.permission.PermissionManager
import me.ayuilos.miffan.ui.components.ui.permission.PermissionNotification
import me.ayuilos.miffan.ui.components.ui.permission.rememberPermissionState
import me.ayuilos.miffan.ui.im.ImCardDivider
import me.ayuilos.miffan.ui.im.ImCardGroup
import me.ayuilos.miffan.ui.im.ImIconTone
import me.ayuilos.miffan.ui.im.ImListRow
import me.ayuilos.miffan.ui.im.ImPageBar
import me.ayuilos.miffan.ui.im.ImRowValue
import me.ayuilos.miffan.ui.im.ImSectionLabel
import me.ayuilos.miffan.ui.im.ImSwitchRow
import me.ayuilos.miffan.utils.normalizeUpdateDownloadBaseUrl
import me.ayuilos.miffan.utils.toLocalDateTime
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CancelCircle
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Route01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.UserCircle
import me.rerere.hugeicons.stroke.Wifi01
import org.koin.androidx.compose.koinViewModel
import java.time.Instant

private val PAUSE_DAYS = listOf(7, 30)
private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

private enum class ReminderChoice { BADGE, BADGE_AND_NOTIFY, PAUSED }

@Composable
fun UpdateSettingsPage(vm: UpdateVM = koinViewModel()) {
    val nav = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val presentation by vm.presentation.collectAsStateWithLifecycle()
    val display = settings.displaySetting
    var pausing by remember { mutableStateOf(false) }
    val choice = when {
        !presentation.remindersOn -> ReminderChoice.PAUSED
        display.updateReminder == UpdateReminder.BADGE -> ReminderChoice.BADGE
        else -> ReminderChoice.BADGE_AND_NOTIFY
    }
    val customSource = normalizeUpdateDownloadBaseUrl(settings.networkSetting.updateDownloadBaseUrl)
    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(PermissionNotification) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    Scaffold(topBar = { ImPageBar(stringResource(R.string.update_settings_title)) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("reminder") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImSectionLabel(stringResource(R.string.update_settings_when_new))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ReminderTile(HugeIcons.UserCircle, stringResource(R.string.update_settings_badge_only),
                            choice == ReminderChoice.BADGE, Modifier.weight(1f), badge = true) {
                            vm.updateDisplay { it.copy(updateReminder = UpdateReminder.BADGE, updateCheckDisabledUntilEpochMillis = 0L) }
                        }
                        ReminderTile(HugeIcons.Notification01, stringResource(R.string.update_settings_badge_and_notify),
                            choice == ReminderChoice.BADGE_AND_NOTIFY, Modifier.weight(1f)) {
                            if (!permissionState.allPermissionsGranted) permissionState.requestPermissions()
                            vm.updateDisplay {
                                it.copy(updateReminder = UpdateReminder.BADGE_AND_NOTIFY, updateCheckDisabledUntilEpochMillis = 0L)
                            }
                        }
                        ReminderTile(HugeIcons.Moon02, stringResource(R.string.update_settings_pause),
                            choice == ReminderChoice.PAUSED, Modifier.weight(1f)) { pausing = true }
                    }
                    Text(
                        if (choice == ReminderChoice.PAUSED) stringResource(R.string.setting_update_reminder_paused_until,
                            Instant.ofEpochMilli(presentation.remindersPausedUntil).toLocalDateTime())
                        else stringResource(R.string.update_settings_when_new_note),
                        Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item("switches") {
                ImCardGroup {
                    ImSwitchRow(stringResource(R.string.update_settings_wifi), display.updateAutoDownloadOnWifi, { on ->
                        vm.updateDisplay { it.copy(updateAutoDownloadOnWifi = on) }
                    }, supporting = stringResource(R.string.update_settings_wifi_desc), icon = HugeIcons.Wifi01, tone = ImIconTone.SECONDARY)
                    ImCardDivider()
                    ImSwitchRow(stringResource(R.string.update_settings_whats_new), display.updateShowWhatsNew, { on ->
                        vm.updateDisplay { it.copy(updateShowWhatsNew = on) }
                    }, supporting = stringResource(R.string.update_settings_whats_new_desc), icon = HugeIcons.Sparkles, tone = ImIconTone.TERTIARY)
                }
            }
            item("more") {
                ImCardGroup {
                    presentation.skippedVersion?.let { skipped ->
                        ImListRow(HugeIcons.CancelCircle, stringResource(R.string.update_settings_skipped), null,
                            supporting = skipped,
                            trailing = { TextButton(onClick = vm::unskip) { Text(stringResource(R.string.update_settings_unskip)) } })
                        ImCardDivider()
                    }
                    ImListRow(HugeIcons.Route01, stringResource(R.string.update_settings_source),
                        { nav.navigate(Screen.SettingPreferencesNetwork) },
                        trailing = {
                            ImRowValue(if (customSource == null) stringResource(R.string.update_settings_source_auto)
                            else stringResource(R.string.update_settings_source_custom))
                        })
                }
            }
        }
    }

    if (pausing) {
        var days by remember { mutableIntStateOf(PAUSE_DAYS.first()) }
        AlertDialog(
            onDismissRequest = { pausing = false },
            title = { Text(stringResource(R.string.setting_update_reminder_pause_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.setting_update_reminder_pause_description))
                    PAUSE_DAYS.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(days == option, role = Role.RadioButton) { days = option },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(days == option, onClick = null)
                            Spacer(Modifier.size(12.dp))
                            Text(stringResource(R.string.setting_update_reminder_pause_days, option))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateDisplay {
                        it.copy(updateCheckDisabledUntilEpochMillis = System.currentTimeMillis() + days * DAY_MILLIS)
                    }
                    pausing = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { pausing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** One of the three reminder choices, as a selectable tile with an icon and a short name. */
@Composable
private fun ReminderTile(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    badge: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = 100.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceContainerLow,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Box {
                Icon(icon, null, Modifier.size(26.dp))
                if (badge) {
                    Surface(Modifier.align(Alignment.TopEnd).size(8.dp), shape = RoundedCornerShape(50), color = colors.error) { }
                }
            }
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}
