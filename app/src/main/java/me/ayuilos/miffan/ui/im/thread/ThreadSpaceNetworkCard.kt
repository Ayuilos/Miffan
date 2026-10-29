package me.ayuilos.miffan.ui.im.thread

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
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
 * firewall command fits. "Run from this phone" runs that command on the host in a terminal (the owner
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
    ApprovalCardFrame(
        tool, stringResource(R.string.im_space_fix_card_title), onApproval,
        icon = HugeIcons.WifiOff01,
        allowLabel = stringResource(when {
            current == null -> R.string.im_space_net_checking
            current.online -> R.string.confirm
            fix -> R.string.im_space_run_here
            else -> R.string.im_space_check_again
        }),
        allowEnabled = hostId != null && current != null,
        footnote = if (fix) stringResource(R.string.im_space_network_note) else null,
        // Only the firewall fix runs anything on the host; otherwise allowing just reports the network again.
        beforeAllow = if (fix) { go ->
            proceed = go
            running = true
        } else null,
    ) {
        toolArg(tool, "reason")?.let { Text(it) }
        when {
            current == null -> Unit
            current.online -> Text(stringResource(R.string.im_space_fix_card_online), color = MaterialTheme.colorScheme.primary)
            fix -> SpaceFixCommand(command)
            else -> Text(stringResource(R.string.im_space_network_help, computer), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
