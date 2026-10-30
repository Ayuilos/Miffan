package me.ayuilos.miffan.ui.im.thread

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.tools.REQUEST_ABILITY
import me.ayuilos.miffan.data.ai.tools.WORKSPACE_SHELL_TOOL_NAME
import me.ayuilos.miffan.data.ai.tools.WORKSPACE_TERMINAL_TOOL_NAME
import me.ayuilos.miffan.ui.modifier.shimmer
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.TaskDone01
import me.rerere.hugeicons.stroke.Tick01

/**
 * One tool call the partner made, in words a user can follow. [changes] marks the calls that alter
 * something (files, commands, memory, settings, outside services); reading and searching do not.
 */
internal data class ThreadStep(
    @StringRes val label: Int,
    val args: List<String>,
    val changes: Boolean,
    val failed: Boolean,
    val running: Boolean,
)

/**
 * The steps behind a reply. Computer actions and added calendar events have their own card, and prompts the user answers
 * (questions, permission requests, refused calls) and partner messages already show in the chat.
 */
internal fun threadSteps(parts: List<UIMessagePart>): List<ThreadStep> = parts.mapNotNull { part ->
    (part as? UIMessagePart.Tool)?.takeUnless {
        it.isComputerTool() || it.isPartnerMessage() || it.isImageGeneration() || it.addedCalendarEvent() != null ||
            it.toolName == ASK_USER || it.toolName == REQUEST_WEB_SEARCH || it.toolName == REQUEST_ABILITY ||
            it.isPending || it.approvalState is ToolApprovalState.Denied
    }?.let(::threadStep)
}

/** Easy mode hides process, but anything that changed something, or a long run of steps, is worth a record. */
internal fun List<ThreadStep>.worthRecording(): Boolean = any { it.changes } || size >= MANY_STEPS

private const val MANY_STEPS = 3
private const val MCP_PREFIX = "mcp__"
private val stepJson = Json { ignoreUnknownKeys = true }

private fun threadStep(tool: UIMessagePart.Tool): ThreadStep {
    val input = tool.inputAsJson() as? JsonObject
    fun arg(key: String) = (input?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val file = arg("path")?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    fun step(@StringRes label: Int, changes: Boolean = false, vararg args: String) =
        ThreadStep(label, args.toList(), changes, failed = tool.failed(), running = !tool.isExecuted)
    fun fileStep(@StringRes named: Int, @StringRes unnamed: Int, changes: Boolean) =
        if (file != null) step(named, changes, file) else step(unnamed, changes)
    return when (tool.toolName) {
        WORKSPACE_SHELL_TOOL_NAME, WORKSPACE_TERMINAL_TOOL_NAME -> step(R.string.im_step_command, changes = true)
        "workspace_write_file" -> fileStep(R.string.im_step_write_file, R.string.im_step_write_file_any, changes = true)
        "workspace_edit_file" -> fileStep(R.string.im_step_edit_file, R.string.im_step_edit_file_any, changes = true)
        "workspace_read_file" -> fileStep(R.string.im_step_read_file, R.string.im_step_read_file_any, changes = false)
        "workspace_publish_files" -> step(R.string.im_step_publish, changes = true)
        me.ayuilos.miffan.data.ai.tools.SPACE_HANDOFF_TOOL ->
            fileStep(R.string.im_step_hand_over, R.string.im_step_hand_over_any, changes = true)
        "workspace_fetch_url", "scrape_web" -> step(R.string.im_step_read_web)
        "search_web" -> step(R.string.im_step_search)
        "conversation_search", "recent_chats", "read_partner_chat" -> step(R.string.im_step_chats)
        "memory_tool" -> when (arg("action")) {
            "create" -> step(R.string.im_step_memory_save, changes = true)
            "edit" -> step(R.string.im_step_memory_edit, changes = true)
            "delete" -> step(R.string.im_step_memory_delete, changes = true)
            else -> step(R.string.im_step_memory_read)
        }
        "update_my_preferences" -> step(R.string.im_step_preferences, changes = true)
        UPDATE_MY_PROFILE -> step(R.string.im_step_profile, changes = true)
        CREATE_PARTNER -> step(R.string.im_step_create_partner, changes = true)
        CREATE_GROUP_CHAT -> step(R.string.im_step_create_group_chat, changes = true)
        INVITE_TO_GROUP_CHAT -> step(R.string.im_step_invite, changes = true)
        ORGANIZE_CHAT_LIST -> step(R.string.im_step_organize, changes = true)
        VIEW_CHAT_LIST -> step(R.string.im_step_view_chat_list)
        "calendar_create" -> step(R.string.im_step_calendar_add, changes = true)
        "calendar_query" -> step(R.string.im_step_calendar_read)
        "clipboard_tool" -> if (arg("action") == "write") step(R.string.im_step_clipboard_write, changes = true)
            else step(R.string.im_step_clipboard_read)
        "text_to_speech" -> step(R.string.im_step_speak)
        "extensions_apply_changes" -> step(R.string.im_step_extensions_apply, changes = true)
        "extensions_catalog", "extensions_preview_changes" -> step(R.string.im_step_extensions_read)
        "eval_javascript" -> step(R.string.im_step_script)
        "use_skill" -> step(R.string.im_step_skill)
        "miffan_help" -> step(R.string.im_step_help)
        "get_time_info" -> step(R.string.im_step_time)
        "get_screen_time" -> step(R.string.im_step_screen_time)
        "list_contacts" -> step(R.string.im_step_contacts)
        "my_environment" -> step(R.string.im_step_environment)
        APP_DIAGNOSTICS -> step(R.string.im_step_diagnostics)
        DRAFT_BUG_REPORT -> step(R.string.im_step_bug_report)
        // An outside tool may change anything, so it always counts.
        else -> if (tool.toolName.startsWith(MCP_PREFIX)) {
            val name = tool.toolName.removePrefix(MCP_PREFIX)
            step(R.string.im_step_mcp, true, name.substringBefore("__"), name.substringAfter("__"))
        } else step(R.string.im_step_tool, true, tool.toolName)
    }
}

/** A thrown error, a refused call, or a command that exited non-zero or timed out. */
private fun UIMessagePart.Tool.failed(): Boolean {
    val text = output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text ?: return false
    val result = runCatching { stepJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
    if ("error" in result) return true
    val exit = (result["exitCode"] as? JsonPrimitive)?.intOrNull
    return (exit != null && exit != 0) || (result["timedOut"] as? JsonPrimitive)?.booleanOrNull == true
}

/** Repeated reads in a row read as one line ("Searched the web (3 times)"); changes always stay separate. */
private data class StepLine(val step: ThreadStep, val count: Int, val failed: Boolean, val running: Boolean)

private fun List<ThreadStep>.lines(): List<StepLine> = fold(mutableListOf()) { lines, step ->
    val last = lines.lastOrNull()
    if (last != null && !step.changes && !last.step.changes && last.step.label == step.label && last.step.args == step.args) {
        lines[lines.lastIndex] = last.copy(count = last.count + 1, failed = last.failed || step.failed, running = step.running)
    } else lines += StepLine(step, 1, step.failed, step.running)
    lines
}

@Composable
internal fun ThreadStep.text(): String = stringResource(label, *args.toTypedArray())

/**
 * What the partner did behind this reply: while it works, the step it is on and how many it has
 * taken; afterwards, one line that opens a plain list of the steps. No arguments or raw output.
 */
@Composable
internal fun ThreadStepRecord(steps: List<ThreadStep>, live: Boolean, @StringRes liveStatus: Int?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val failed = steps.count { it.failed }
    val current = steps.lastOrNull { it.running }
    val subtitle = when {
        live -> current?.text() ?: liveStatus?.let { stringResource(it) } ?: steps.last().text()
        failed > 0 -> pluralStringResource(R.plurals.im_steps_failed, failed, failed)
        else -> (steps.lastOrNull { it.changes } ?: steps.last()).text()
    }
    val toggle = stringResource(if (expanded) R.string.im_steps_collapse else R.string.im_steps_expand)
    Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (live) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(if (failed > 0) HugeIcons.Alert02 else HugeIcons.TaskDone01, null, Modifier.size(20.dp),
                    tint = if (failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (live) stringResource(R.string.im_steps_running, steps.size)
                        else pluralStringResource(R.plurals.im_steps_done, steps.size, steps.size),
                        style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, Modifier.shimmer(isLoading = live), style = MaterialTheme.typography.bodySmall,
                        color = if (!live && failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, toggle, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) Column(Modifier.padding(start = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.lines().forEach { line -> ThreadStepLine(line) }
            }
        }
    }
}

@Composable
private fun ThreadStepLine(line: StepLine) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            line.running -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            line.failed -> Icon(HugeIcons.Cancel01, stringResource(R.string.im_steps_step_failed), Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error)
            else -> Icon(HugeIcons.Tick01, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val text = line.step.text()
        Text(if (line.count > 1) stringResource(R.string.im_steps_repeated, text, line.count) else text,
            style = MaterialTheme.typography.bodyMedium, fontWeight = if (line.step.changes) FontWeight.Medium else null,
            color = if (line.step.changes) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
