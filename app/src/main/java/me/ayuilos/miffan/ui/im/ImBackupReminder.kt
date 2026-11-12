package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Cloud
import org.koin.compose.koinInject

private const val SNOOZE_MILLIS = 24 * 60 * 60 * 1_000L

/**
 * At the top of the chat list once it has been longer than the owner's interval since the last backup, when they
 * asked to be reminded. Tapping opens the backup page; × puts it off until tomorrow.
 */
@Composable
internal fun ImBackupReminderCard(modifier: Modifier = Modifier) {
    val settings = LocalSettings.current
    // Read again whenever the list comes back into view, so a reminder due overnight shows without a restart.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    if (!settings.backupReminderDue(now)) return
    val store = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    val nav = LocalNavController.current
    val days = settings.daysSinceBackup(now)
    Surface(onClick = { nav.navigate(Screen.ImBackup) }, modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(HugeIcons.Cloud, null, Modifier.size(22.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.im_backup_due_title), style = MaterialTheme.typography.titleSmall)
                Text(if (days == null) stringResource(R.string.im_backup_due_never) else pluralStringResource(R.plurals.im_backup_days_ago, days, days),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                scope.launch {
                    store.update { it.copy(backupReminderConfig = it.backupReminderConfig.copy(snoozedUntil = System.currentTimeMillis() + SNOOZE_MILLIS)) }
                }
            }) {
                Icon(HugeIcons.Cancel01, stringResource(R.string.im_backup_due_later), Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
