package me.ayuilos.miffan.ui.im.thread

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.components.ui.AssistantGenerationPhase
import me.ayuilos.miffan.ui.components.message.ChatMessageToolStep
import me.ayuilos.miffan.ui.components.message.ComputerToolUIRenderer
import me.ayuilos.miffan.ui.components.message.approvalDecisionText
import me.ayuilos.miffan.ui.components.message.tools.ToolUIContext
import me.ayuilos.miffan.ui.components.message.tools.ToolUIRegistry
import me.ayuilos.miffan.ui.components.message.ThinkingStep
import me.ayuilos.miffan.ui.components.ui.ChainOfThought
import me.ayuilos.miffan.ui.modifier.shimmer
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.ToolDecision
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick01

private const val ASK_USER = "ask_user"
private const val REQUEST_WEB_SEARCH = "request_web_search"

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

/**
 * Tool calls that stay in the chat: prompts waiting for the user, questions the user already answered,
 * and permission requests the user already settled.
 */
internal fun UIMessagePart.Tool.isThreadPrompt(): Boolean =
    approvalState is ToolApprovalState.Pending || (toolName == ASK_USER && approvalState is ToolApprovalState.Answered) ||
        isSettledPermission()

/** A permission card the user (or stopping the reply) already settled; it shrinks to a one-line record in place. */
internal fun UIMessagePart.Tool.isSettledPermission(): Boolean {
    if (toolName == ASK_USER || approvalState is ToolApprovalState.Pending) return false
    val record = approvalRecord ?: return false
    return record.decidedAt != null && record.decision != null &&
        record.decision != ToolDecision.ANSWERED && record.decision != ToolDecision.AUTO_ALLOWED
}

@Composable
internal fun ThreadToolPrompt(tool: UIMessagePart.Tool, onApproval: (String, Boolean) -> Unit, onAnswer: (String, String) -> Unit) {
    when {
        tool.approvalState is ToolApprovalState.Pending && tool.toolName == ASK_USER ->
            ThreadToolDetails(listOf(ThinkingStep.ToolStep(tool)), onAnswer)
        tool.approvalState is ToolApprovalState.Pending -> ThreadPermissionCard(tool, onApproval)
        tool.toolName == ASK_USER -> ThreadAnsweredQuestions(tool)
        tool.isSettledPermission() -> ThreadPermissionRecord(tool)
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
            if (tool.toolName == REQUEST_WEB_SEARCH) {
                Text(webSearchReason(tool) ?: stringResource(R.string.im_p5_web))
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

private fun webSearchReason(tool: UIMessagePart.Tool): String? =
    (tool.inputAsJson() as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * What a settled permission card leaves behind: what was asked and how it was answered,
 * in the same wording as the approval history. Tapping it shows the exact request.
 */
@Composable
private fun ThreadPermissionRecord(tool: UIMessagePart.Tool) {
    val record = tool.approvalRecord ?: return
    val decision = record.decision ?: return
    val at = record.decidedAt ?: return
    val computer = tool.isComputerTool()
    val renderer = remember(tool.toolName) { if (computer) ComputerToolUIRenderer else ToolUIRegistry.resolve(tool.toolName) }
    val context = remember(tool) { ToolUIContext(tool, tool.inputAsJson(), null, false) }
    var details by remember(tool.toolCallId) { mutableStateOf(false) }
    val allowed = decision == ToolDecision.ALLOWED
    val declined = decision == ToolDecision.DECLINED
    val reason = (tool.approvalState as? ToolApprovalState.Denied)?.reason?.takeIf { declined && it.isNotBlank() }
    val title = when {
        tool.toolName == REQUEST_WEB_SEARCH -> webSearchReason(tool) ?: stringResource(R.string.im_p5_web)
        computer -> computerActionText(tool)
        else -> renderer.title(context)
    }
    Surface(onClick = { details = true }, color = Color.Transparent, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (allowed) HugeIcons.Tick01 else HugeIcons.Cancel01, null, Modifier.padding(top = 2.dp).size(16.dp),
                tint = when {
                    allowed -> MaterialTheme.colorScheme.primary
                    declined -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(approvalDecisionText(decision, record.via, at) + reason?.let { ": $it" }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (declined) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (details) ModalBottomSheet(onDismissRequest = { details = false }) {
        renderer.Preview(context) { details = false }
    }
}

/**
 * What the partner is doing right now, outside any bubble: its avatar, animated for the phase, then
 * the status. While the thread welcomes the user back, the big partner from the welcome flies here.
 */
@Composable
internal fun ThreadLiveStatusRow(assistant: Assistant?, phase: AssistantGenerationPhase, @StringRes label: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThreadPartnerHandOff(Modifier.size(32.dp)) { modifier ->
            ThreadAvatar(assistant, phase.takeIf { it != AssistantGenerationPhase.None } ?: AssistantGenerationPhase.Waiting, modifier = modifier)
        }
        ThreadLiveStatus(label)
    }
}
