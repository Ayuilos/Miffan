package me.ayuilos.miffan.ui.components.message

import androidx.annotation.StringRes
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.tools.COMPUTER_TOOL_PREFIX
import me.ayuilos.miffan.ui.components.message.tools.ToolUIContext
import me.ayuilos.miffan.ui.components.message.tools.ToolUIRenderer
import me.ayuilos.miffan.ui.components.message.tools.getStringContent
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

internal data class ComputerActionTitle(@param:StringRes val label: Int?, val detail: String? = null)

internal fun computerActionTitle(toolName: String, arguments: JsonElement): ComputerActionTitle {
    fun argument(vararg names: String) = names.firstNotNullOfOrNull { name ->
        arguments.getStringContent(name)?.takeIf { it.isNotBlank() }
    }
    return when (toolName.removePrefix(COMPUTER_TOOL_PREFIX)) {
        "get_desktop_state" -> ComputerActionTitle(R.string.computer_use_view_screen)
        "get_window_state" -> ComputerActionTitle(R.string.computer_use_view_window)
        "list_windows" -> ComputerActionTitle(R.string.computer_use_list_windows)
        "list_apps" -> ComputerActionTitle(R.string.computer_use_list_apps)
        "click" -> ComputerActionTitle(R.string.computer_use_click)
        "double_click" -> ComputerActionTitle(R.string.computer_use_double_click)
        "right_click" -> ComputerActionTitle(R.string.computer_use_right_click)
        "drag" -> ComputerActionTitle(R.string.computer_use_drag)
        "type_text" -> {
            val text = argument("text")
            val detail = text?.let {
                val count = it.codePointCount(0, it.length)
                it.substring(0, it.offsetByCodePoints(0, minOf(count, 40)))
                    .replace('\n', ' ').replace('\r', ' ') + if (count > 40) "…" else ""
            }
            ComputerActionTitle(R.string.computer_use_type_text, detail)
        }
        "press_key", "hotkey" -> {
            val keys = (arguments as? JsonObject)?.get("keys") as? JsonArray
            val detail = argument("key", "keys") ?: keys?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }?.joinToString(" + ")?.takeIf { it.isNotBlank() }
            ComputerActionTitle(
                if (toolName == "${COMPUTER_TOOL_PREFIX}hotkey") R.string.computer_use_shortcut
                else R.string.computer_use_press_key,
                detail,
            )
        }
        "scroll" -> ComputerActionTitle(R.string.computer_use_scroll)
        "launch_app" -> ComputerActionTitle(R.string.computer_use_open_app, argument("bundle_id", "name", "app"))
        "read_guide" -> ComputerActionTitle(R.string.computer_use_read_guide)
        "bring_to_front" -> ComputerActionTitle(R.string.computer_use_bring_to_front)
        else -> ComputerActionTitle(null)
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
