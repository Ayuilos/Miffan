package me.ayuilos.miffan.ui.im.thread

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.components.message.ChatMessageToolStep
import me.ayuilos.miffan.ui.components.message.ThinkingStep
import me.ayuilos.miffan.ui.components.ui.ChainOfThought
import me.ayuilos.miffan.ui.modifier.shimmer
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01

private const val ASK_USER = "ask_user"

/**
 * What a streaming reply is doing right now, or null once its own text (or a prompt card) is the feedback.
 * Easy mode never keeps process details after the reply finishes; those belong to professional mode.
 */
@StringRes
internal fun threadLiveStatus(parts: List<UIMessagePart>, streaming: Boolean): Int? {
    if (!streaming) return null
    for (part in parts.asReversed()) {
        when (part) {
            is UIMessagePart.Text -> if (part.text.isNotBlank()) return null
            is UIMessagePart.Reasoning -> return R.string.im_thread_status_thinking
            is UIMessagePart.Tool -> return if (part.approvalState is ToolApprovalState.Pending) null else toolStatus(part.toolName)
            is UIMessagePart.ServerTool -> return toolStatus(part.toolName)
            is UIMessagePart.Image, is UIMessagePart.Audio, is UIMessagePart.Video -> return null
            else -> Unit
        }
    }
    return R.string.im_thread_status_thinking
}

private fun toolStatus(name: String): Int =
    if (name.contains("search", true) || name.contains("web", true)) R.string.im_thread_tools_running else R.string.im_thread_tools_working

/** Tool calls that stay in the chat: prompts waiting for the user, and questions the user already answered. */
internal fun UIMessagePart.Tool.isThreadPrompt(): Boolean =
    approvalState is ToolApprovalState.Pending || (toolName == ASK_USER && approvalState is ToolApprovalState.Answered)

@Composable
internal fun ThreadToolPrompt(tool: UIMessagePart.Tool, onApproval: (String, Boolean) -> Unit, onAnswer: (String, String) -> Unit) {
    when {
        tool.approvalState is ToolApprovalState.Pending && tool.toolName == ASK_USER ->
            ThreadToolDetails(listOf(ThinkingStep.ToolStep(tool)), onAnswer)
        tool.approvalState is ToolApprovalState.Pending -> ThreadPermissionCard(tool, onApproval)
        tool.toolName == ASK_USER -> ThreadAnsweredQuestions(tool)
    }
}

@Composable
internal fun ThreadLiveStatus(@StringRes label: Int) {
    Text(stringResource(label), Modifier.shimmer(isLoading = true), style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ThreadToolDetails(steps: List<ThinkingStep>, onAnswer: ((String, String) -> Unit)? = null) {
    ChainOfThought(steps = steps, collapsedVisibleCount = Int.MAX_VALUE) { step ->
        if (step is ThinkingStep.ToolStep) ChatMessageToolStep(step.tool, loading = false, onToolAnswer = onAnswer)
    }
}

/** The question and the user's choice, kept as part of the conversation rather than as process. */
@Composable
private fun ThreadAnsweredQuestions(tool: UIMessagePart.Tool) {
    val answered = tool.approvalState as? ToolApprovalState.Answered ?: return
    val rows = remember(tool.input, answered.answer) {
        val answers = runCatching { Json.parseToJsonElement(answered.answer).jsonObject["answers"]?.jsonObject }.getOrNull()
        runCatching {
            tool.inputAsJson().jsonObject["questions"]?.jsonArray?.map { item ->
                val question = item.jsonObject
                val id = question["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                question["question"]?.jsonPrimitive?.contentOrNull.orEmpty() to
                    (answers?.get(id)?.jsonPrimitive?.contentOrNull ?: answered.answer)
            }
        }.getOrNull().orEmpty()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { (question, answer) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (question.isNotBlank()) Text(question, style = MaterialTheme.typography.titleMedium)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(answer, Modifier.weight(1f))
                        Icon(HugeIcons.Tick01, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadPermissionCard(tool: UIMessagePart.Tool, onApproval: (String, Boolean) -> Unit) {
    var answered by remember(tool.toolCallId) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.im_p5_permission), style = MaterialTheme.typography.titleMedium)
            if (tool.toolName == "request_web_search") {
                val reason = (tool.inputAsJson() as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull
                Text(reason?.takeIf { it.isNotBlank() } ?: stringResource(R.string.im_p5_web))
                Text(stringResource(R.string.im_p5_web_permission), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.im_p5_permission_info), color = MaterialTheme.colorScheme.onSurfaceVariant)
                ThreadToolDetails(listOf(ThinkingStep.ToolStep(tool)))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = !answered, onClick = { answered = true; onApproval(tool.toolCallId, true) }) { Text(stringResource(R.string.im_p5_allow)) }
                OutlinedButton(modifier = Modifier.weight(1f), enabled = !answered, onClick = { answered = true; onApproval(tool.toolCallId, false) }) { Text(stringResource(R.string.im_p5_not_now)) }
            }
        }
    }
}
