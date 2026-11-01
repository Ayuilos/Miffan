package me.ayuilos.miffan.ui.im.computer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.repository.relay.OFFICIAL_RELAY_URL
import me.ayuilos.miffan.data.repository.relay.RelayAccess
import me.ayuilos.miffan.data.repository.relay.RemoteRelaySetup
import me.ayuilos.miffan.ui.context.LocalNavController
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import org.koin.compose.koinInject

/**
 * "Away from home" on a computer's page: the relay switch, today's use, a connection test, another
 * relay, and forgetting this phone (prototype https://claude.ai/artifact/AQkBwK213tbYNxeAGXUp4W).
 */
@Composable
internal fun ComputerRelaySection(
    host: RemoteHostEntity,
    computerName: String,
    workspaceId: String?,
    enabled: Boolean,
    perform: (suspend () -> Unit) -> Unit,
) {
    val nav = LocalNavController.current
    val setup: RemoteRelaySetup = koinInject()
    val access: RelayAccess = koinInject()
    val lastReady by access.lastReady.collectAsStateWithLifecycle()
    val relay = host.relay
    val on = relay?.enabled == true
    var forgetting by rememberSaveable { mutableStateOf(false) }
    val divider = @Composable { HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) }

    Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 10.dp)) {
        Text(stringResource(R.string.im_computer_relay_section), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.im_computer_relay_section_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SetupCard(padding = 0.dp, spacing = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.im_computer_relay_switch), style = MaterialTheme.typography.bodyLarge)
                Text(when {
                    relay == null -> stringResource(R.string.im_computer_relay_off_hint)
                    !relay.enabled -> stringResource(R.string.im_computer_relay_paused)
                    relay.url.trimEnd('/') == OFFICIAL_RELAY_URL -> stringResource(R.string.im_computer_relay_official)
                    else -> relay.endpoint.host
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, enabled = enabled, onCheckedChange = { checked ->
                // A computer without a relay goes through the setup: connector, this phone, one test trip.
                if (relay == null) nav.navigate(Screen.ComputerSetup(hostId = host.id, away = true))
                else perform { setup.setEnabled(host.id, checked) }
            })
        }
        if (on) {
            lastReady[host.id]?.let { ready ->
                divider()
                RelayUsageRow(ready.usage.dayActiveMin, ready.policy.quotas.hostDailyActiveMin, ready.usage.resetsAt)
            }
            divider()
            RelayLinkRow(stringResource(R.string.im_computer_relay_test), enabled) { nav.navigate(Screen.RelayDiagnostics(host.id)) }
            divider()
            RelayLinkRow(stringResource(R.string.im_computer_relay_change), enabled) {
                nav.navigate(Screen.ComputerSetup(hostId = host.id, away = true))
            }
            divider()
            Surface(onClick = { forgetting = true }, enabled = enabled && workspaceId != null, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text(stringResource(R.string.im_computer_relay_forget), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (on) Text(stringResource(R.string.im_computer_relay_note), Modifier.padding(horizontal = 6.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (forgetting && workspaceId != null) AlertDialog(
        onDismissRequest = { forgetting = false },
        title = { Text(stringResource(R.string.im_computer_relay_forget_title)) },
        text = { Text(stringResource(R.string.im_computer_relay_forget_body, computerName)) },
        confirmButton = {
            TextButton(onClick = {
                forgetting = false
                perform { setup.forget(workspaceId, host.id) }
            }) { Text(stringResource(R.string.im_computer_relay_forget_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { forgetting = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Today's relay minutes, as a bar against the daily allowance when the relay has one. */
@Composable
private fun RelayUsageRow(minutes: Int, allowance: Int, resetsAt: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.im_computer_relay_today), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(if (allowance > 0) stringResource(R.string.im_computer_relay_minutes_of, minutes, allowance)
                else stringResource(R.string.im_computer_relay_minutes, minutes),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (allowance > 0) LinearProgressIndicator(progress = { (minutes.toFloat() / allowance).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), drawStopIndicator = {})
        localResetTime(resetsAt)?.let {
            Text(stringResource(R.string.im_computer_relay_resets, it), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RelayLinkRow(title: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(HugeIcons.ArrowRight01, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The relay's reset moment (UTC midnight) in the phone's own time, such as "08:00". */
internal fun localResetTime(resetsAt: String): String? = runCatching {
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(resetsAt))
}.getOrNull()
