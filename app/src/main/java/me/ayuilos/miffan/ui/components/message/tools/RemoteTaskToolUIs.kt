package me.ayuilos.miffan.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.tools.AGENT_TOOLS_TOOL
import me.ayuilos.miffan.data.ai.tools.SCHEDULE_TASK_TOOL
import me.ayuilos.miffan.data.remotetask.RemoteTaskRepository
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Wrench01
import org.koin.compose.koinInject

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
            // An update may name only what changes; the stored task still knows what it is called.
            "update" -> name.ifEmpty { storedTaskName(context).orEmpty() }.let { known ->
                if (known.isEmpty()) stringResource(R.string.remote_task_step_update_unnamed) else stringResource(R.string.remote_task_step_update, known)
            }
            "delete" -> stringResource(R.string.remote_task_step_delete)
            "run_now" -> stringResource(R.string.remote_task_step_run_now)
            "enable" -> stringResource(R.string.remote_task_step_enable)
            "disable" -> stringResource(R.string.remote_task_step_disable)
            else -> stringResource(R.string.remote_task_step_list)
        }
    }
}

@Composable
private fun storedTaskName(context: ToolUIContext): String? {
    val hostId = context.tool.workspaceTarget?.remoteHostId ?: return null
    val taskId = context.arg("task_id") ?: return null
    val repository: RemoteTaskRepository = koinInject()
    val tasks by remember(hostId) { repository.tasksFlow(hostId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    return tasks.find { it.taskId == taskId }?.let { repository.decodeMeta(it)?.name }
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
