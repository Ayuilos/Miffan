package me.ayuilos.miffan.ui.im.computer

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.components.ui.QRCodeScannerDialog
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cloud
import me.rerere.hugeicons.stroke.QrCode01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.SquareLock02

/** The relay's own setup guide on the Miffan site. */
internal const val RELAY_HOSTING_GUIDE_URL = "https://miffan.ayuilos.me/docs"

/**
 * Whether this computer can also be reached away from home through a relay: first the choice,
 * then the setup and one test trip (prototype https://claude.ai/artifact/AQkBwK213tbYNxeAGXUp4W).
 */
@Composable
internal fun ComputerAwayStep(vm: ComputerSetupVM, state: ComputerSetupState, onLeave: () -> Unit) {
    val progress = state.away
    if (progress == null) AwayChoice(vm, state, onLeave) else AwayProgressScreen(vm, state, progress, onLeave)
}

@Composable
private fun AwayChoice(vm: ComputerSetupVM, state: ComputerSetupState, onLeave: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    SetupScreen(
        title = stringResource(R.string.im_computer_away_title),
        subtitle = stringResource(R.string.im_computer_away_subtitle, state.target?.name.orEmpty()),
        state = state,
        onDismissError = vm::dismissError,
        primary = SetupAction(stringResource(R.string.im_computer_away_enable)) { vm.enableAway(url, code) },
        secondary = SetupAction(stringResource(R.string.im_computer_away_skip), onClick = onLeave),
    ) {
        SetupCard(padding = 16.dp, spacing = 0.dp) {
            AwayPoint(HugeIcons.SquareLock02, stringResource(R.string.im_computer_away_point_encrypted_title),
                stringResource(R.string.im_computer_away_point_encrypted_body), divider = true)
            AwayPoint(HugeIcons.SmartPhone01, stringResource(R.string.im_computer_away_point_phone_title),
                stringResource(R.string.im_computer_away_point_phone_body), divider = true)
            AwayPoint(HugeIcons.Cloud, stringResource(R.string.im_computer_away_point_free_title),
                stringResource(R.string.im_computer_away_point_free_body), divider = false)
        }
        SetupDisclosure(stringResource(R.string.im_computer_away_own_relay), initiallyExpanded = url.isNotEmpty()) {
            SetupCard(padding = 16.dp, spacing = 0.dp) {
                SetupField(stringResource(R.string.im_computer_away_relay_address), url, { url = it },
                    enabled = !state.busy, placeholder = "https://relay.example.com", keyboardType = KeyboardType.Uri, divider = true)
                SetupField(stringResource(R.string.im_computer_away_access_code), code, { code = it },
                    enabled = !state.busy, placeholder = stringResource(R.string.im_computer_away_access_code_hint), password = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scanning = true }, enabled = !state.busy, modifier = Modifier.heightIn(min = 44.dp)) {
                    Icon(HugeIcons.QrCode01, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.im_computer_away_scan), Modifier.padding(start = 8.dp))
                }
                TextButton(onClick = { uriHandler.openUri(RELAY_HOSTING_GUIDE_URL) }, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(stringResource(R.string.im_computer_away_how_to_host))
                }
            }
        }
    }
    if (scanning) QRCodeScannerDialog(
        onResult = { text ->
            parseRelayCode(text)?.let { (scannedUrl, scannedCode) ->
                url = scannedUrl
                code = scannedCode.orEmpty()
            }
            scanning = false
        },
        onPermissionDenied = { scanning = false },
        onDismissRequest = { scanning = false },
    )
}

@Composable
private fun AwayPoint(icon: ImageVector, title: String, body: String, divider: Boolean) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun AwayProgressScreen(vm: ComputerSetupVM, state: ComputerSetupState, progress: AwayProgress, onLeave: () -> Unit) {
    val computer = state.target?.name.orEmpty()
    val finished = progress.finished
    val failed = progress.failed
    fun rowState(index: Int): CheckState = when {
        failed && index == 2 && finished -> CheckState.TODO
        progress.stage > index -> CheckState.DONE
        progress.stage == index && !finished -> CheckState.WAITING
        else -> CheckState.LATER
    }
    SetupScreen(
        title = stringResource(when {
            !finished -> R.string.im_computer_away_working
            failed -> R.string.im_computer_away_failed_title
            else -> R.string.im_computer_away_done_title
        }),
        subtitle = when {
            !finished -> stringResource(R.string.im_computer_away_working_subtitle, computer)
            failed -> stringResource(R.string.im_computer_away_failed_subtitle)
            else -> stringResource(R.string.im_computer_away_done_subtitle)
        },
        state = state,
        onDismissError = vm::dismissError,
        primary = if (finished) SetupAction(stringResource(if (failed) R.string.im_computer_away_finish_anyway
            else R.string.im_computer_away_finish), onClick = onLeave) else null,
        secondary = if (finished && failed) SetupAction(stringResource(R.string.im_computer_away_retry), onClick = vm::retryAway) else null,
    ) {
        SetupCard(padding = 16.dp, spacing = 0.dp) {
            ChecklistRow(rowState(0), stringResource(R.string.im_computer_away_step_install))
            ChecklistRow(rowState(1), stringResource(R.string.im_computer_away_step_allow),
                detail = if (progress.stage > 1) deviceLabel() else null)
            ChecklistRow(rowState(2), stringResource(R.string.im_computer_away_step_try), divider = false,
                detail = when {
                    !finished -> null
                    failed -> stringResource(R.string.im_computer_away_step_try_failed)
                    else -> progress.probe?.let { stringResource(R.string.im_computer_away_step_try_ok, seconds(it.openMillis + it.commandMillis)) }
                })
        }
        if (finished && failed) Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(20.dp)) {
            Text(stringResource(R.string.im_computer_away_failed_note), Modifier.fillMaxWidth().padding(16.dp),
                color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium)
        }
        if (!finished) Text(stringResource(R.string.im_computer_away_keep_on), Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

private fun deviceLabel(): String = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ")

internal fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1f", millis / 1000.0)

/**
 * A relay's QR code: `miffan-relay://add?url=<relay URL>&token=<access code>` (relay/PROTOCOL.md),
 * or just the relay's https URL. Anything else is ignored.
 */
internal fun parseRelayCode(text: String): Pair<String, String?>? {
    val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
    return when (uri.scheme?.lowercase()) {
        "miffan-relay" -> uri.getQueryParameter("url")?.takeIf { it.startsWith("https://") }
            ?.let { it to uri.getQueryParameter("token")?.takeIf(String::isNotEmpty) }
        "https" -> text.trim() to null
        else -> null
    }
}
