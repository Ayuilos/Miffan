package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.components.message.ChatMessageReasoningStep
import me.ayuilos.miffan.ui.components.message.ChatMessageServerToolStep
import me.ayuilos.miffan.ui.components.message.ChatMessageToolStep
import me.ayuilos.miffan.ui.components.message.ThinkingStep
import me.ayuilos.miffan.ui.components.ui.ChainOfThought
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart

@Composable
internal fun ThreadToolStatus(steps: List<ThinkingStep>, assistant: Assistant?, streaming: Boolean) {
    val tools = steps.filter { it !is ThinkingStep.ReasoningStep }
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
private fun ThreadToolDetails(steps: List<ThinkingStep>, streaming: Boolean) {
    ChainOfThought(steps = steps, collapsedVisibleCount = Int.MAX_VALUE) { step ->
        when (step) {
            is ThinkingStep.ToolStep -> ChatMessageToolStep(step.tool, loading = streaming && !step.tool.isExecuted)
            is ThinkingStep.ServerToolStep -> ChatMessageServerToolStep(step.tool)
            else -> Unit
        }
    }
}
