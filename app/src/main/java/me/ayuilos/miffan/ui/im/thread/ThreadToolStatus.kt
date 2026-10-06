package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.components.message.ChatMessageReasoningStep
import me.ayuilos.miffan.ui.components.message.ChatMessageServerToolStep
import me.ayuilos.miffan.ui.components.message.ChatMessageToolStep
import me.ayuilos.miffan.ui.components.message.ThinkingStep
import me.ayuilos.miffan.ui.components.ui.ChainOfThought
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01

@Composable
internal fun ThreadToolStatus(steps: List<ThinkingStep>, assistant: Assistant?, streaming: Boolean,
    onApproval: (String, Boolean) -> Unit, onAnswer: (String, String) -> Unit) {
    val pending = steps.filterIsInstance<ThinkingStep.ToolStep>().filter { it.tool.approvalState is ToolApprovalState.Pending }
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
        pending.forEach { step ->
            if (step.tool.toolName == "ask_user") ThreadToolDetails(listOf(step), streaming, onAnswer)
            else ThreadPermissionCard(step.tool, onApproval)
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
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(summary, style = MaterialTheme.typography.labelLarge)
                    Icon(if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01, null, Modifier.size(16.dp))
                }
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
                ThreadToolDetails(listOf(ThinkingStep.ToolStep(tool)), false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = !answered, onClick = { answered = true; onApproval(tool.toolCallId, true) }) { Text(stringResource(R.string.im_p5_allow)) }
                OutlinedButton(modifier = Modifier.weight(1f), enabled = !answered, onClick = { answered = true; onApproval(tool.toolCallId, false) }) { Text(stringResource(R.string.im_p5_not_now)) }
            }
        }
    }
}
