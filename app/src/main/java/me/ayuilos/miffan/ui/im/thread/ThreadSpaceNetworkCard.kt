package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.ui.im.ImCardButton
import me.ayuilos.miffan.ui.im.ImCardButtons
import me.ayuilos.miffan.ui.im.ImCardOutlinedButton
import me.ayuilos.miffan.ui.im.ImCardStatus
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.CuaSpaceNetcheck
import me.ayuilos.miffan.data.ai.computer.PartnerSpaces
import me.ayuilos.miffan.data.ai.tools.spaceNetworkFixCommand
import me.ayuilos.miffan.ui.im.computer.HostCommandTerminal
import me.ayuilos.miffan.ui.im.computer.SpaceFixCommand
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.WifiOff01
import org.koin.compose.koinInject

/**
 * A partner whose own computer is offline asks the owner to open its network. The card checks the network
 * first: the check itself may bring the computer online (the host's network share), and it tells which
 * firewall command fits. "Fix it on this phone" runs that command on the host in a terminal (the owner
 * types sudo's password); only a clean exit approves the call, whose result is the network checked again.
 */
@Composable
internal fun ThreadSpaceNetworkCard(tool: UIMessagePart.Tool, onApproval: (String, Boolean) -> Unit) {
    val spaces: PartnerSpaces = koinInject()
    val hostId = tool.workspaceTarget?.remoteHostId
    val workspaceId = tool.workspaceTarget?.workspaceId
    val pending = tool.approvalState is ToolApprovalState.Pending
    val check by produceState(workspaceId?.let(spaces::lastNetcheck), workspaceId, pending) {
        if (pending && workspaceId != null && value?.online != true) {
            value = runCatching { spaces.netcheck(workspaceId) }.getOrNull() ?: value
        }
    }
    var running by remember(tool.toolCallId) { mutableStateOf(false) }
    var proceed by remember(tool.toolCallId) { mutableStateOf<(() -> Unit)?>(null) }
    var allowed by remember(tool.toolCallId) { mutableStateOf(false) }
    var showCommand by remember(tool.toolCallId) { mutableStateOf(false) }
    val current: CuaSpaceNetcheck? = check
    val command = spaceNetworkFixCommand(current)
    if (running && hostId != null) HostCommandTerminal(hostId, command,
        onFinished = { ok ->
            if (ok) {
                running = false
                proceed?.invoke()
            }
        },
        onDismiss = { running = false })
    val fix = current?.online == false && current.firewallFix
    val computer = tool.workspaceTarget?.remoteHostName.orEmpty()
    val decline = LocalToolCardDecline.current
    val allowLabel = stringResource(when {
        current == null -> R.string.im_space_net_checking
        current.online -> R.string.confirm
        fix -> R.string.im_space_fix_on_phone
        else -> R.string.im_space_check_again
    })
    val allow = {
        val go = {
            allowed = true
            onApproval(tool.toolCallId, true)
        }
        // Only the firewall fix runs anything on the host; otherwise allowing just reports the network again.
        if (fix) {
            proceed = go
            running = true
        } else go()
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(HugeIcons.WifiOff01, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                Text(stringResource(R.string.im_space_fix_card_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            when {
                current == null -> Unit
                current.online -> Text(stringResource(R.string.im_space_fix_card_online), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
                fix -> Text(stringResource(R.string.im_space_fix_why, computer), style = MaterialTheme.typography.bodyMedium)
                else -> Text(stringResource(R.string.im_space_network_help, computer), style = MaterialTheme.typography.bodyMedium)
            }
            toolArg(tool, "reason")?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RelayApprovalNotice(tool)
            if (fix && !allowed) {
                TextButton(onClick = { showCommand = !showCommand }) {
                    Text(stringResource(R.string.im_space_show_command), style = MaterialTheme.typography.labelMedium)
                }
                if (showCommand) SpaceFixCommand(command)
            }
            if (allowed) ImCardStatus(stringResource(R.string.im_approval_allowed_continuing))
            else {
                ImCardButtons {
                    ImCardOutlinedButton(stringResource(R.string.im_space_fix_skip), onClick = {
                        decline?.invoke(tool.toolCallId) ?: onApproval(tool.toolCallId, false)
                    })
                    ImCardButton(allowLabel, enabled = hostId != null && current != null && LocalToolCardAllowEnabled.current, onClick = allow)
                }
                if (fix) Text(stringResource(R.string.im_space_fix_password, computer), Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}
