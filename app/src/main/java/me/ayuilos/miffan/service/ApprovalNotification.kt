package me.ayuilos.miffan.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.tools.COMPUTER_TOOL_PREFIX
import me.ayuilos.miffan.data.ai.tools.WORKSPACE_SHELL_TOOL_NAME
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.utils.computerActionTitle
import me.rerere.ai.ui.UIMessagePart
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
    val summary = when {
        first.toolName == WORKSPACE_SHELL_TOOL_NAME -> {
            val command = ((arguments as? JsonObject)?.get("command") as? JsonPrimitive)?.contentOrNull
                ?: first.input
            val line = command.lineSequence().firstOrNull().orEmpty()
            val preview = line.substring(0, line.offsetByCodePoints(0, minOf(80, line.codePointCount(0, line.length))))
            text(R.string.notification_approval_shell, listOf(preview))
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
        else -> text(R.string.notification_approval_generic, listOf(first.toolName))
    }
    return if (pending.size > 1) text(R.string.notification_approval_more, listOf(summary, pending.size - 1)) else summary
}
