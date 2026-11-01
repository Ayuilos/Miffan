package me.ayuilos.miffan.ui.im.computer

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.data.repository.relay.RelayAccess
import me.ayuilos.miffan.data.repository.relay.RelayProbe
import me.ayuilos.miffan.data.repository.relay.RemoteRelaySetup
import me.ayuilos.miffan.ui.im.ImPageBar
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.relayConnectError
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.FlashOff
import me.rerere.workspace.relay.RelayException
import org.koin.compose.koinInject

/** What one run of the relay test found; null fields are still running or did not get that far. */
private data class RelayCheck(
    val network: String? = null,
    val edgeMillis: Long? = null,
    val edgeRegion: String? = null,
    val probe: RelayProbe? = null,
    val failure: String? = null,
    val done: Boolean = false,
)

/**
 * A computer's relay, tested from here: this phone's network, the fastest entry, one session to the
 * computer and one command, then what the relay offers (prototype
 * https://claude.ai/artifact/AQkBwK213tbYNxeAGXUp4W). Also how the mobile-data numbers R0 could
 * not measure get collected.
 */
@Composable
fun RelayDiagnosticsPage(hostId: String) {
    val resources = LocalResources.current
    val access: RelayAccess = koinInject()
    val setup: RemoteRelaySetup = koinInject()
    val workspaces: WorkspaceRepository = koinInject()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val lastReady by access.lastReady.collectAsStateWithLifecycle()
    var run by remember { mutableIntStateOf(0) }
    var check by remember { mutableStateOf(RelayCheck()) }

    LaunchedEffect(run) {
        check = RelayCheck()
        val host = workspaces.getHostById(hostId)
        val relay = host?.relay
        if (relay == null) {
            check = RelayCheck(done = true, failure = resources.getString(R.string.workspace_relay_unreachable))
            return@LaunchedEffect
        }
        val networkKey = access.network.current()
        check = check.copy(network = networkLabel(resources, networkKey))
        try {
            val (millis, region) = withContext(Dispatchers.IO) {
                val best = access.client.measureEdges(relay.endpoint, networkKey).firstOrNull { it.millis != null }
                    ?: throw RelayException(RelayException.UNREACHABLE)
                best.millis to access.client.edgeLocation(relay.endpoint, best.ip)?.let { regionLabel(resources, it) }
            }
            check = check.copy(edgeMillis = millis, edgeRegion = region)
            val probe = setup.probe(hostId)
            check = check.copy(probe = probe, done = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val reason = generateSequence<Throwable>(error) { it.cause }.take(16).filterIsInstance<RelayException>().firstOrNull()?.reason
            check = check.copy(done = true, failure = relayConnectError(resources, reason ?: RelayException.UNREACHABLE))
        }
    }

    val probe = check.probe
    val ready = lastReady[hostId]
    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_relay_test_title)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SetupCard(padding = 18.dp, spacing = 6.dp) {
                Text(when {
                    !check.done -> stringResource(R.string.im_relay_test_running)
                    check.failure != null -> stringResource(R.string.im_relay_test_failed)
                    (probe?.commandMillis ?: 0) > SLOW_MILLIS -> stringResource(R.string.im_relay_test_slow)
                    else -> stringResource(R.string.im_relay_test_ok)
                }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(when {
                    !check.done -> stringResource(R.string.im_relay_test_running_hint)
                    check.failure != null -> check.failure.orEmpty()
                    (probe?.commandMillis ?: 0) > SLOW_MILLIS -> stringResource(R.string.im_relay_test_slow_hint)
                    else -> stringResource(R.string.im_relay_test_ok_hint)
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SetupCard(padding = 0.dp, spacing = 0.dp) {
                val stopped = check.done && check.failure != null
                CheckRow(stringResource(R.string.im_relay_test_network), stringResource(R.string.im_relay_test_network_hint),
                    check.network, stopped)
                CheckRow(stringResource(R.string.im_relay_test_entry),
                    check.edgeRegion?.let { stringResource(R.string.im_relay_test_entry_hint, it) } ?: stringResource(R.string.im_relay_test_entry_hint_plain),
                    check.edgeMillis?.let { stringResource(R.string.im_relay_test_millis, it) }, stopped)
                CheckRow(stringResource(R.string.im_relay_test_open), stringResource(R.string.im_relay_test_open_hint),
                    probe?.let { stringResource(R.string.im_relay_test_seconds, seconds(it.openMillis)) }, stopped)
                CheckRow(stringResource(R.string.im_relay_test_answer), stringResource(R.string.im_relay_test_answer_hint),
                    probe?.let { stringResource(R.string.im_relay_test_millis, it.commandMillis) }, stopped,
                    warn = (probe?.commandMillis ?: 0) > SLOW_MILLIS, divider = false)
            }

            (probe?.let { it.policy to it.usage } ?: ready?.let { it.policy to it.usage })?.let { (policy, usage) ->
                Text(stringResource(R.string.im_relay_test_offers), Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val media = policy.media.allowed
                    RuleTile(Modifier.weight(1f), stringResource(R.string.im_relay_rule_screen),
                        stringResource(if (media) R.string.im_relay_rule_screen_saver else R.string.im_relay_rule_screen_off), good = media) {
                        RuleIcon(HugeIcons.Computer, media)
                    }
                    val allowance = policy.quotas.hostDailyActiveMin
                    RuleTile(Modifier.weight(1f), stringResource(R.string.im_relay_rule_today),
                        if (allowance > 0) stringResource(R.string.im_relay_rule_today_of, allowance)
                        else stringResource(R.string.im_relay_rule_today_unlimited), good = null) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(progress = { if (allowance > 0) (usage.dayActiveMin.toFloat() / allowance).coerceIn(0f, 1f) else 0f },
                                modifier = Modifier.size(44.dp), strokeWidth = 4.dp,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                            Text("${usage.dayActiveMin}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    RuleTile(Modifier.weight(1f), stringResource(R.string.im_relay_rule_fast),
                        stringResource(R.string.im_relay_rule_fast_off), good = false) {
                        RuleIcon(HugeIcons.FlashOff, false)
                    }
                }
            }

            Row(Modifier.padding(top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { run++ }, enabled = check.done, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.im_relay_test_again))
                }
                OutlinedButton(onClick = {
                    val text = buildString {
                        appendLine("network=${check.network}")
                        appendLine("entry=${check.edgeMillis}ms ${check.edgeRegion.orEmpty()}")
                        probe?.let {
                            appendLine("open=${it.openMillis}ms answer=${it.commandMillis}ms")
                            appendLine("media=${it.policy.media.allowed} ${it.policy.media.kbps}kbps today=${it.usage.dayActiveMin}min")
                        }
                        check.failure?.let { appendLine("failure=$it") }
                    }
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("relay", text))) }
                }, enabled = check.done, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.im_relay_test_copy))
                }
            }
        }
    }
}

private const val SLOW_MILLIS = 800L

@Composable
private fun CheckRow(label: String, hint: String, value: String?, stopped: Boolean, warn: Boolean = false, divider: Boolean = true) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                value != null -> Text(value, style = MaterialTheme.typography.titleSmall,
                    color = if (warn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                stopped -> Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** One thing the relay offers: a picture, a short name and a badge (no "label: value" lines). */
@Composable
private fun RuleTile(modifier: Modifier, name: String, badge: String, good: Boolean?, picture: @Composable () -> Unit) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            picture()
            Text(name, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            val (container, content) = when (good) {
                true -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                false -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
                null -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            }
            Text(badge, Modifier.clip(RoundedCornerShape(10.dp)).background(container).padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall, color = content, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun RuleIcon(icon: ImageVector, on: Boolean) {
    val tint: Color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(44.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
    }
}

private fun networkLabel(resources: android.content.res.Resources, key: String): String = resources.getString(when (key.substringBefore(':')) {
    "wifi" -> R.string.im_relay_network_wifi
    "cell" -> R.string.im_relay_network_cell
    "eth" -> R.string.im_relay_network_ethernet
    else -> R.string.im_relay_network_other
})

/** A Cloudflare location code as a place people know. */
private fun regionLabel(resources: android.content.res.Resources, colo: String): String = when (colo) {
    "LAX", "SJC", "SEA", "PDX", "SFO", "LAS", "PHX", "DEN", "SLC", "SAN" -> resources.getString(R.string.im_relay_region_us_west)
    "IAD", "EWR", "ATL", "ORD", "DFW", "MIA", "BOS", "JFK", "IAH", "MSP", "YYZ" -> resources.getString(R.string.im_relay_region_us_east)
    "HKG" -> resources.getString(R.string.im_relay_region_hk)
    "NRT", "KIX", "FUK", "OKA" -> resources.getString(R.string.im_relay_region_jp)
    "ICN" -> resources.getString(R.string.im_relay_region_kr)
    "SIN" -> resources.getString(R.string.im_relay_region_sg)
    "TPE", "KHH" -> resources.getString(R.string.im_relay_region_tw)
    "AMS", "FRA", "LHR", "CDG", "MAD", "MXP", "ARN", "WAW", "VIE", "ZRH", "DUS", "HAM", "MUC" -> resources.getString(R.string.im_relay_region_eu)
    else -> colo
}
