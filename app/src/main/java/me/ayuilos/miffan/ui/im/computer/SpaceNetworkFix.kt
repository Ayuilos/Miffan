package me.ayuilos.miffan.ui.im.computer

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.CuaSpaceNetcheck
import me.ayuilos.miffan.data.ai.tools.spaceNetworkFixCommand
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01

/** The fix [command] in a box, with a button that copies it. */
@Composable
internal fun SpaceFixCommand(command: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { CommandPreview(command) }
        IconButton(onClick = {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("command", command))) }
        }) { Icon(HugeIcons.Copy01, stringResource(R.string.im_space_copy_command)) }
    }
}

/**
 * What to do about an offline Space, wherever it shows: the firewall fix (run from the phone, or copied)
 * when [check] pointed at ufw on [computerName], else a hint to look at its network; and checking again.
 */
@Composable
internal fun SpaceNetworkFixPanel(
    hostId: String,
    computerName: String,
    check: CuaSpaceNetcheck?,
    checking: Boolean,
    onRecheck: () -> Unit,
) {
    val firewall = check?.firewallFix == true
    val command = spaceNetworkFixCommand(check)
    var running by rememberSaveable { mutableStateOf(false) }
    if (running) HostCommandTerminal(hostId, command,
        onFinished = { ok -> if (ok) onRecheck() },
        onDismiss = { running = false })
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(if (firewall) R.string.im_space_network_help_ufw else R.string.im_space_network_help, computerName),
            style = MaterialTheme.typography.bodyMedium)
        if (firewall) {
            SpaceFixCommand(command)
            Text(stringResource(R.string.im_space_network_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (firewall) Button(onClick = { running = true }, enabled = !checking, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.im_space_run_here))
            }
            OutlinedButton(onClick = onRecheck, enabled = !checking, modifier = Modifier.weight(1f)) {
                Text(stringResource(if (checking) R.string.im_space_net_checking else R.string.im_space_check_again))
            }
        }
    }
}
