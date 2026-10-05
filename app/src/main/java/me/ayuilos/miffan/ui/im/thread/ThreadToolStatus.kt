package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.service.ChatService
import org.koin.compose.koinInject
import kotlin.uuid.Uuid
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.components.message.ChatMessageReasoningStep
import me.ayuilos.miffan.ui.components.message.ChatMessageServerToolStep
import me.ayuilos.miffan.ui.components.message.ChatMessageToolStep
import me.ayuilos.miffan.ui.components.message.ThinkingStep
import me.ayuilos.miffan.ui.components.ui.ChainOfThought
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart

@Composable
internal fun ThreadToolStatus(steps: List<ThinkingStep>, assistant: Assistant?, streaming: Boolean,
    segmentId: Uuid, onApproval: (String, Boolean) -> Unit, onAnswer: (String, String) -> Unit) {
    val pending = steps.filterIsInstance<ThinkingStep.ToolStep>().filter { it.tool.approvalState is ToolApprovalState.Pending }
    val chatService: ChatService = koinInject()
    val needsInteraction = pending.isNotEmpty()
    var interactionReady by remember(segmentId, needsInteraction) { mutableStateOf(false) }
    var interactionFailed by remember(segmentId, needsInteraction) { mutableStateOf(false) }
    var reload by remember(segmentId) { mutableIntStateOf(0) }
    // Pending tools outlive generation jobs. Keep their session alive while this card is visible;
    // on returning to a saved card, reload that exact segment before forwarding the VM action.
    DisposableEffect(segmentId, needsInteraction) {
        if (needsInteraction) chatService.addConversationReference(segmentId)
        onDispose { if (needsInteraction) chatService.removeConversationReference(segmentId) }
    }
    LaunchedEffect(segmentId, needsInteraction, assistant?.id, reload) {
        if (needsInteraction && assistant != null) {
            interactionFailed = false
            try {
                chatService.openThreadSegment(segmentId, assistant.id)
                interactionReady = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { interactionFailed = true }
        }
    }
    val tools = steps.filter { it !is ThinkingStep.ReasoningStep && it !in pending }
    val finished = tools.filter { step ->
        when (step) {
            is ThinkingStep.ToolStep -> step.tool.isExecuted || step.tool.approvalState is ToolApprovalState.Denied
            is ThinkingStep.ServerToolStep -> step.tool.isFinished
            else -> false
        }
    }
    val ongoing = if (streaming) tools - finished.toSet() else emptyList()
    val folded = if (streaming) finished else tools
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(streaming) { if (!streaming) expanded = false }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (interactionFailed) {
            Text(stringResource(R.string.im_p5_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { reload++ }) { Text(stringResource(R.string.im_thread_retry)) }
        }
        pending.forEach { step ->
            if (step.tool.toolName == "ask_user") ThreadToolDetails(listOf(step), streaming, if (interactionReady) onAnswer else null)
            else ThreadPermissionCard(step.tool, interactionReady, onApproval)
        }
        if (folded.isNotEmpty()) {
            // Count known search results; never invent a page count for other provider formats.
            val pages = folded.sumOf { step ->
                if (step is ThinkingStep.ToolStep && step.tool.toolName == "search_web") {
                    runCatching {
                        val output = step.tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                        ((Json.parseToJsonElement(output) as? JsonObject)?.get("items") as? JsonArray)?.size ?: 0
                    }.getOrDefault(0)
                } else 0
            }
            val allSearch = folded.all { it is ThinkingStep.ToolStep && it.tool.toolName == "search_web" }
            val summary = when {
                allSearch && pages > 0 -> stringResource(R.string.im_thread_tools_web, pages)
                folded.size == finished.size -> stringResource(R.string.im_thread_tools_done, folded.size)
                else -> stringResource(R.string.im_thread_tools_details, folded.size)
            }
            Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                Text("$summary  ${if (expanded) "⌄" else "›"}", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
            }
            if (expanded) ThreadToolDetails(folded, streaming)
        }
        if (ongoing.isNotEmpty()) {
            val web = ongoing.any { step ->
                val name = when (step) {
                    is ThinkingStep.ToolStep -> step.tool.toolName
                    is ThinkingStep.ServerToolStep -> step.tool.toolName
                    else -> ""
                }
                name.contains("search", true) || name.contains("web", true)
            }
            Text(stringResource(if (web) R.string.im_thread_tools_running else R.string.im_thread_tools_working),
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ThreadToolDetails(ongoing, streaming)
        }
        val reasoning = steps.filterIsInstance<ThinkingStep.ReasoningStep>()
        if (reasoning.isNotEmpty()) ChainOfThought(steps = reasoning, collapsedVisibleCount = Int.MAX_VALUE) {
            ChatMessageReasoningStep(it.reasoning, model = null, assistant = assistant)
        }
    }
}

@Composable
private fun ThreadToolDetails(steps: List<ThinkingStep>, streaming: Boolean, onAnswer: ((String, String) -> Unit)? = null) {
    ChainOfThought(steps = steps, collapsedVisibleCount = Int.MAX_VALUE) { step ->
        when (step) {
            is ThinkingStep.ToolStep -> ChatMessageToolStep(step.tool, loading = streaming && !step.tool.isExecuted, onToolAnswer = onAnswer)
            is ThinkingStep.ServerToolStep -> ChatMessageServerToolStep(step.tool)
            else -> Unit
        }
    }
}


@Composable
private fun ThreadPermissionCard(tool: UIMessagePart.Tool, ready: Boolean, onApproval: (String, Boolean) -> Unit) {
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
                ThreadToolDetails(listOf(ThinkingStep.ToolStep(tool)), false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = ready && !answered, onClick = { answered = true; onApproval(tool.toolCallId, true) }) { Text(stringResource(R.string.im_p5_allow)) }
                OutlinedButton(modifier = Modifier.weight(1f), enabled = ready && !answered, onClick = { answered = true; onApproval(tool.toolCallId, false) }) { Text(stringResource(R.string.im_p5_not_now)) }
            }
        }
    }
}
