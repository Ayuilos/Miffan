package me.ayuilos.miffan.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.tools.COMPUTER_TOOL_PREFIX
import me.ayuilos.miffan.ui.components.message.tools.ToolUIContext
import me.ayuilos.miffan.ui.components.message.tools.ToolUIRenderer
import me.ayuilos.miffan.ui.components.message.tools.getStringContent
import me.ayuilos.miffan.utils.computerActionTitle
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.Computer

internal enum class ComputerToolStatus { REFUSED, ERROR }

/** Only result status fields count; quoted status examples in a guide are ordinary text. */
internal fun computerToolStatus(output: List<UIMessagePart>): ComputerToolStatus? {
    val statuses = output.filterIsInstance<UIMessagePart.Text>().flatMap { part ->
        (sequenceOf(part.text) + part.text.lineSequence()).mapNotNull { text ->
            val json = text.trim().removePrefix("structured:").trim()
            runCatching { JsonInstant.parseToJsonElement(json) }.getOrNull().getStringContent("status")
        }.toList()
    }
    return when {
        "error" in statuses -> ComputerToolStatus.ERROR
        "refused" in statuses -> ComputerToolStatus.REFUSED
        else -> null
    }
}

internal fun computerToolNeedsForegroundWarning(toolName: String, arguments: JsonElement): Boolean =
    toolName == "${COMPUTER_TOOL_PREFIX}bring_to_front" || arguments.getStringContent("delivery_mode") == "foreground"

/** Reuses the existing approval controls and raw tool preview in ChatMessageTools. */
internal object ComputerToolUIRenderer : ToolUIRenderer {
    override val toolName: String = COMPUTER_TOOL_PREFIX

    override fun icon(context: ToolUIContext): ImageVector =
        if (computerToolStatus(context.tool.output) == ComputerToolStatus.ERROR) HugeIcons.AlertCircle
        else HugeIcons.Computer

    @Composable
    override fun title(context: ToolUIContext): String {
        val action = computerActionTitle(context.tool.toolName, context.arguments)
        val label = action.label?.let { stringResource(it) } ?: context.tool.toolName
        return action.detail?.let { stringResource(R.string.computer_use_action_detail, label, it) } ?: label
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        computerToolStatus(context.tool.output) != null ||
            (context.tool.approvalState is ToolApprovalState.Pending &&
                computerToolNeedsForegroundWarning(context.tool.toolName, context.arguments))

    @Composable
    override fun Summary(context: ToolUIContext) {
        when (computerToolStatus(context.tool.output)) {
            ComputerToolStatus.REFUSED -> Text(
                stringResource(R.string.computer_use_refused),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ComputerToolStatus.ERROR -> ComputerToolWarning(stringResource(R.string.computer_use_error))
            null -> Unit
        }
        if (context.tool.approvalState is ToolApprovalState.Pending &&
            computerToolNeedsForegroundWarning(context.tool.toolName, context.arguments)
        ) {
            ComputerToolWarning(stringResource(R.string.computer_use_foreground_warning))
        }
    }
}

@Composable
private fun ComputerToolWarning(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.AlertCircle, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
