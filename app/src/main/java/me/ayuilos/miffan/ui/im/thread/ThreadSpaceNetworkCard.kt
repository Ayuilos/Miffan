package me.ayuilos.miffan.ui.im.thread

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.tools.SPACE_NETWORK_FIX_COMMAND
import me.ayuilos.miffan.ui.im.computer.HostCommandTerminal
import me.ayuilos.miffan.ui.im.computer.SpaceFixCommand
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.WifiOff01

/**
 * A partner whose own computer is offline asks the owner to open its network. "Run from this phone"
 * runs the fixed firewall command on the host in a terminal (the owner types sudo's password); only a
 * clean exit approves the call, whose result is the network checked again.
 */
@Composable
internal fun ThreadSpaceNetworkCard(tool: UIMessagePart.Tool, onApproval: (String, Boolean) -> Unit) {
    val hostId = tool.workspaceTarget?.remoteHostId
    var running by remember(tool.toolCallId) { mutableStateOf(false) }
    var proceed by remember(tool.toolCallId) { mutableStateOf<(() -> Unit)?>(null) }
    if (running && hostId != null) HostCommandTerminal(hostId, SPACE_NETWORK_FIX_COMMAND,
        onFinished = { ok ->
            if (ok) {
                running = false
                proceed?.invoke()
            }
        },
        onDismiss = { running = false })
    ApprovalCardFrame(
        tool, stringResource(R.string.im_space_fix_card_title), onApproval,
        icon = HugeIcons.WifiOff01,
        allowLabel = stringResource(R.string.im_space_run_here),
        allowEnabled = hostId != null,
        footnote = stringResource(R.string.im_space_network_note),
        beforeAllow = { go ->
            proceed = go
            running = true
        },
    ) {
        toolArg(tool, "reason")?.let { Text(it) }
        SpaceFixCommand()
    }
}
