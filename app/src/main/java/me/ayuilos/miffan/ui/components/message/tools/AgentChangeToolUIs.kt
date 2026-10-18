package me.ayuilos.miffan.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.ui.im.thread.AGENT_CHANGE_TOOLS
import me.ayuilos.miffan.ui.im.thread.ToolRecordDetails
import me.ayuilos.miffan.ui.im.thread.UPDATE_MY_PREFERENCES
import me.ayuilos.miffan.ui.im.thread.agentChangeTitle
import me.ayuilos.miffan.ui.im.thread.rememberToolCallChange
import me.ayuilos.miffan.ui.im.thread.toolArg
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiEditing

/**
 * Changes a partner makes to the app itself (its profile, preferences, partners, chats): the step reads
 * as what changed, and its details show the change in readable form with undo where the app kept a version.
 */
private class AgentChangeToolUI(override val toolName: String) : ToolUIRenderer {
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.AiEditing

    @Composable
    override fun title(context: ToolUIContext): String =
        if (toolName == UPDATE_MY_PREFERENCES) toolArg(context.tool, "summary") ?: stringResource(me.ayuilos.miffan.R.string.im_p5_preferences)
        else stringResource(agentChangeTitle(toolName))

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val change by rememberToolCallChange(context.tool)
        ToolRecordDetails(context.tool, assistant = null, change = change, onDismiss = onDismissRequest)
    }
}

internal val AgentChangeToolUIRenderers: List<ToolUIRenderer> =
    (AGENT_CHANGE_TOOLS + UPDATE_MY_PREFERENCES).map(::AgentChangeToolUI)
