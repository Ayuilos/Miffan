package me.ayuilos.miffan.ui.im

import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.components.ui.permission.PermissionManager
import me.ayuilos.miffan.ui.components.ui.permission.PermissionNotification
import me.ayuilos.miffan.ui.components.ui.permission.rememberPermissionState
import me.ayuilos.miffan.ui.pages.setting.SettingVM
import me.ayuilos.miffan.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import java.time.Instant

private val UPDATE_PAUSE_DAYS = listOf(7, 14, 21)
private const val DAY_MILLIS = 24 * 60 * 60 * 1_000L

/**
 * Easy chat's notifications in plain words. When the system blocks Miffan's notifications the page
 * says so first, since none of the switches below can work until it is allowed.
 */
@Composable
fun ImNotificationsPage(vm: SettingVM = koinViewModel()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val display = settings.displaySetting
    var blocked by remember { mutableStateOf(!NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        blocked = !NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(PermissionNotification) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)
    var pausing by remember { mutableStateOf(false) }
    val updatesOn = display.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()

    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_me_notification)) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (blocked) item("blocked") {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.im_notify_blocked), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.im_notify_blocked_desc), style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = {
                            context.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName))
                        }) { Text(stringResource(R.string.im_notify_open)) }
                    }
                }
            }
            item("switches") {
                ImCardGroup {
                    ImSwitchRow(stringResource(R.string.im_notify_reply), display.enableNotificationOnMessageGeneration, { on ->
                        if (on && !permissionState.allPermissionsGranted) permissionState.requestPermissions()
                        vm.updateSettings { it.copy(displaySetting = it.displaySetting.copy(enableNotificationOnMessageGeneration = on)) }
                    }, supporting = stringResource(R.string.im_notify_reply_desc))
                    ImCardDivider(start = 16.dp)
                    // Progress rides on the reply notification, so it only means something while that is on.
                    ImSwitchRow(stringResource(R.string.im_notify_progress),
                        display.enableNotificationOnMessageGeneration && display.enableLiveUpdateNotification, { on ->
                            vm.updateSettings { it.copy(displaySetting = it.displaySetting.copy(enableLiveUpdateNotification = on)) }
                        }, supporting = stringResource(R.string.im_notify_progress_desc), enabled = display.enableNotificationOnMessageGeneration)
                    ImCardDivider(start = 16.dp)
                    ImSwitchRow(stringResource(R.string.im_notify_update), updatesOn, { on ->
                        if (on) vm.updateSettings { it.copy(displaySetting = it.displaySetting.copy(updateCheckDisabledUntilEpochMillis = 0L)) }
                        else pausing = true
                    }, supporting = if (updatesOn) stringResource(R.string.im_notify_update_desc)
                    else stringResource(R.string.setting_update_reminder_paused_until,
                        Instant.ofEpochMilli(display.updateCheckDisabledUntilEpochMillis).toLocalDateTime()))
                }
            }
        }
    }
    // Update reminders pause for a while rather than switch off for good.
    if (pausing) {
        var days by remember { mutableIntStateOf(UPDATE_PAUSE_DAYS.first()) }
        AlertDialog(onDismissRequest = { pausing = false },
            title = { Text(stringResource(R.string.setting_update_reminder_pause_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.setting_update_reminder_pause_description))
                    UPDATE_PAUSE_DAYS.forEach { option ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(days == option, { days = option })
                            Text(stringResource(R.string.setting_update_reminder_pause_days, option))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateSettings { it.copy(displaySetting = it.displaySetting.copy(
                        updateCheckDisabledUntilEpochMillis = System.currentTimeMillis() + days * DAY_MILLIS)) }
                    pausing = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { pausing = false }) { Text(stringResource(R.string.cancel)) } })
    }
}
