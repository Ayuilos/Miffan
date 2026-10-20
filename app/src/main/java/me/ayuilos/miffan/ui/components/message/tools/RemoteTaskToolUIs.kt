package me.ayuilos.miffan.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.tools.AGENT_TOOLS_TOOL
import me.ayuilos.miffan.data.ai.tools.SCHEDULE_TASK_TOOL
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Wrench01

private fun ToolUIContext.arg(key: String): String? =
    ((arguments as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Steps and records of schedule_task read as what happened to which task. */
object ScheduleTaskToolUI : ToolUIRenderer {
    override val toolName: String = SCHEDULE_TASK_TOOL

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Clock01

    @Composable
    override fun title(context: ToolUIContext): String {
        val name = context.arg("name").orEmpty()
        return when (context.arg("action")) {
            "create" -> stringResource(R.string.remote_task_step_create, name)
            "update" -> stringResource(R.string.remote_task_step_update, name)
            "delete" -> stringResource(R.string.remote_task_step_delete)
            "run_now" -> stringResource(R.string.remote_task_step_run_now)
            "enable" -> stringResource(R.string.remote_task_step_enable)
            "disable" -> stringResource(R.string.remote_task_step_disable)
            else -> stringResource(R.string.remote_task_step_list)
        }
    }
}

/** Steps and records of agent_tools, named after the tool they add or change. */
object AgentToolsToolUI : ToolUIRenderer {
    override val toolName: String = AGENT_TOOLS_TOOL

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Wrench01

    @Composable
    override fun title(context: ToolUIContext): String {
        val name = context.arg("name").orEmpty()
        return when (context.arg("action")) {
            "save" -> if (context.arg("tool_id") == null) stringResource(R.string.agent_tool_step_add, name)
                else stringResource(R.string.agent_tool_step_change, name)
            "delete" -> stringResource(R.string.agent_tool_step_delete)
            else -> stringResource(R.string.agent_tool_step_list)
        }
    }
}
