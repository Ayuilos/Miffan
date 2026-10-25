package me.ayuilos.miffan.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.tools.PartnerAbility
import me.ayuilos.miffan.data.ai.tools.REQUEST_ABILITY
import me.ayuilos.miffan.ui.im.title
import me.ayuilos.miffan.data.ai.tools.COMPUTER_TOOL_PREFIX
import me.ayuilos.miffan.data.ai.tools.GENERATE_IMAGE_TOOL
import me.ayuilos.miffan.data.ai.tools.WORKSPACE_SHELL_TOOL_NAME
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.PartnerMessageTool
import me.ayuilos.miffan.utils.computerActionTitle
import me.rerere.ai.ui.UIMessagePart
import java.net.URI
import kotlin.uuid.Uuid

internal fun Conversation.pendingApprovalTools(): List<UIMessagePart.Tool> = currentMessages
    .flatMap { it.parts.filterIsInstance<UIMessagePart.Tool>() }.filter { it.isPending }

/** Negative IDs cannot collide with the done/live/foreground-service notification IDs. */
internal fun approvalNotificationId(conversationId: Uuid): Int = conversationId.hashCode() or Int.MIN_VALUE

/** Pure formatting: the caller supplies localized strings; no Context or Compose dependency. */
internal fun approvalNotificationSummary(
    tools: List<UIMessagePart.Tool>,
    text: (Int, List<Any>) -> String,
): String? {
    val pending = tools.filter { it.isPending }
    val first = pending.firstOrNull() ?: return null
    val arguments = first.inputAsJson()
    fun stringArgument(name: String): String? = ((arguments as? JsonObject)?.get(name) as? JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    fun actionWithArgument(value: String?, withArgument: Int, withoutArgument: Int): String {
        val preview = value?.approvalPreview()?.takeIf { it.isNotBlank() }
        return if (preview != null) text(withArgument, listOf(preview)) else text(withoutArgument, emptyList())
    }
    val summary = when {
        first.toolName == WORKSPACE_SHELL_TOOL_NAME -> {
            val command = ((arguments as? JsonObject)?.get("command") as? JsonPrimitive)?.contentOrNull
                ?: first.input
            text(R.string.notification_approval_shell, listOf(command.approvalPreview()))
        }
        first.toolName.startsWith(COMPUTER_TOOL_PREFIX) -> {
            val computer = first.workspaceTarget?.remoteHostName?.takeIf { it.isNotBlank() }
                ?: first.workspaceTarget?.remoteHostLabel?.takeIf { it.isNotBlank() }
                ?: text(R.string.im_computer_computer, emptyList())
            val action = computerActionTitle(first.toolName, arguments)
            val label = action.label?.let { text(it, emptyList()) } ?: first.toolName
            val description = action.detail?.let { text(R.string.computer_use_action_detail, listOf(label, it)) } ?: label
            text(R.string.notification_approval_computer, listOf(computer, description))
        }
        first.toolName == "calendar_create" -> actionWithArgument(
            stringArgument("title"), R.string.notification_approval_calendar, R.string.im_step_calendar_add,
        )
        first.toolName == "request_web_search" -> text(R.string.notification_approval_web_search, emptyList())
        first.toolName == REQUEST_ABILITY && PartnerAbility.of(stringArgument("ability")) != null ->
            text(R.string.notification_approval_ability, listOf(text(PartnerAbility.of(stringArgument("ability"))!!.title, emptyList())))
        first.toolName == "workspace_fetch_url" -> actionWithArgument(
            stringArgument("url")?.let { runCatching { URI(it).host }.getOrNull() },
            R.string.notification_approval_fetch, R.string.notification_approval_fetch_any,
        )
        first.toolName in listOf("workspace_write_file", "workspace_edit_file", "workspace_read_file") -> {
            val (withArgument, withoutArgument) = when (first.toolName) {
                "workspace_write_file" -> R.string.im_step_write_file to R.string.im_step_write_file_any
                "workspace_edit_file" -> R.string.im_step_edit_file to R.string.im_step_edit_file_any
                else -> R.string.im_step_read_file to R.string.im_step_read_file_any
            }
            actionWithArgument(stringArgument("path")?.substringAfterLast('/'), withArgument, withoutArgument)
        }
        first.toolName.startsWith("mcp__") -> {
            val name = first.toolName.removePrefix("mcp__")
            val (service, step) = name.substringBefore("__") to name.substringAfter("__")
            text(R.string.im_record_mcp, listOf(service.approvalPreview(), step.approvalPreview()))
        }
        first.toolName == "update_my_profile" -> text(R.string.im_profile_title, emptyList())
        first.toolName == "create_partner" -> text(R.string.im_change_create_partner, emptyList())
        first.toolName == "create_group_chat" -> text(R.string.im_change_create_group_chat, emptyList())
        first.toolName == "invite_to_group_chat" -> text(R.string.im_change_invite, emptyList())
        first.toolName == "organize_chat_list" -> text(R.string.im_change_organize, emptyList())
        first.toolName == "update_my_preferences" -> text(R.string.im_step_preferences, emptyList())
        first.toolName == "extensions_apply_changes" -> text(R.string.im_step_extensions_apply, emptyList())
        first.toolName == "draft_bug_report" -> text(R.string.im_bug_report_title, emptyList())
        first.toolName == GENERATE_IMAGE_TOOL -> text(R.string.notification_approval_image, emptyList())
        first.toolName == "ask_user" -> {
            val question = (((arguments as? JsonObject)?.get("questions") as? JsonArray)
                ?.firstOrNull() as? JsonObject)?.get("question") as? JsonPrimitive
            actionWithArgument(
                question?.takeIf { it.isString }?.contentOrNull,
                R.string.notification_approval_question, R.string.notification_approval_question_any,
            )
        }
        first.toolName == PartnerMessageTool.NAME -> text(R.string.notification_approval_partner_message, emptyList())
        else -> text(R.string.notification_approval_generic, listOf(first.toolName))
    }
    return if (pending.size > 1) text(R.string.notification_approval_more, listOf(summary, pending.size - 1)) else summary
}

private fun String.approvalPreview(): String {
    val line = lineSequence().firstOrNull().orEmpty()
    return line.substring(0, line.offsetByCodePoints(0, minOf(80, line.codePointCount(0, line.length))))
}
