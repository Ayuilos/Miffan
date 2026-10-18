package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.revision.RestoreResult
import me.ayuilos.miffan.data.revision.RevisionService
import me.ayuilos.miffan.data.revision.ToolCallChange
import me.ayuilos.miffan.data.revision.ToolCallChangeState
import me.ayuilos.miffan.ui.components.message.approvalDecisionText
import me.ayuilos.miffan.ui.components.message.tools.ToolUIContext
import me.ayuilos.miffan.ui.components.message.tools.ToolUIRegistry
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.Screen
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import org.koin.compose.koinInject

internal const val UPDATE_MY_PREFERENCES = "update_my_preferences"

/** The versioned change a tool call made, if any; follows undo and later edits as they happen. */
@Composable
internal fun rememberToolCallChange(tool: UIMessagePart.Tool): State<ToolCallChange?> {
    val revisions: RevisionService = koinInject()
    // Only calls that ran can have changed anything; skip the lookup for the rest.
    val flow = remember(tool.toolCallId, tool.isExecuted) {
        if (tool.isExecuted) revisions.observeToolCallChange(tool.toolCallId) else flowOf(null)
    }
    return flow.collectAsState(null)
}

/**
 * A settled request in full: how it was answered, what the partner asked for in readable form, and,
 * when the app kept a version of what it changed, undo (and redo) right here.
 */
@Composable
internal fun ToolRecordDetails(tool: UIMessagePart.Tool, assistant: Assistant?, change: ToolCallChange?, onDismiss: () -> Unit) {
    val record = tool.approvalRecord
    val settings = LocalSettings.current
    // Not scrollable itself: tool previews below scroll on their own, and nested vertical scrolling cannot measure.
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // A change's record line reads as the partner's reason; here the reason gets its own box below.
            val title = if (tool.toolName in AGENT_CHANGE_TOOLS) stringResource(agentChangeTitle(tool.toolName)) else toolRecordTitle(tool)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (record?.decision != null && record.decidedAt != null) Text(approvalDecisionText(record.decision!!, record.via, record.decidedAt!!),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        toolArg(tool, "reason")?.takeIf { tool.toolName in AGENT_CHANGE_TOOLS }?.let {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.im_record_reason, it), Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        // The partner's own profile as it was before this call; the current profile already includes the change.
        val before = change?.assistantBefore ?: assistant?.takeIf { tool.approvalRecord?.decision != me.rerere.ai.ui.ToolDecision.ALLOWED }
        when (tool.toolName) {
            UPDATE_MY_PROFILE -> ProfileChangeContent(tool, before)
            CREATE_PARTNER -> CreatePartnerContent(tool)
            CREATE_GROUP_CHAT, INVITE_TO_GROUP_CHAT -> GroupChatContent(tool,
                settings.assistants.firstOrNull { it.id == assistant?.id })
            ORGANIZE_CHAT_LIST -> OrganizeContent(tool)
            EXTENSIONS_APPLY -> ExtensionChangeList(tool)
            UPDATE_MY_PREFERENCES -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                toolArg(tool, "summary")?.let { Text(it) }
                toolArg(tool, "preferences")?.let { ChangeLongText(stringResource(R.string.im_p5_preferences), it) }
            }
            else -> {
                val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
                val context = remember(tool) {
                    ToolUIContext(tool, tool.inputAsJson(), tool.outputJsonOrNull(), false)
                }
                renderer.Preview(context, onDismiss)
            }
        }
        change?.let { UndoSection(tool, it) }
    }
}

internal fun UIMessagePart.Tool.outputJsonOrNull() = if (!isExecuted) null else runCatching {
    kotlinx.serialization.json.Json.parseToJsonElement(output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
}.getOrNull()

@Composable
private fun UndoSection(tool: UIMessagePart.Tool, change: ToolCallChange) {
    val revisions: RevisionService = koinInject()
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    fun run(block: suspend () -> RestoreResult) {
        busy = true
        scope.launch {
            conflict = block() == RestoreResult.Conflict
            busy = false
        }
    }
    val history = change.revisions.firstOrNull()?.let { Screen.RevisionHistory(it.subject.name, it.subjectId) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (change.state) {
            ToolCallChangeState.Undoable -> OutlinedButton(onClick = { run { revisions.undoToolCall(tool.toolCallId) } },
                enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = ButtonDefaults.outlinedButtonBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error))) {
                Text(stringResource(R.string.im_record_undo))
            }
            ToolCallChangeState.Undone -> {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(HugeIcons.Tick01, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.im_record_undone), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                TextButton(onClick = { run { revisions.redoToolCall(tool.toolCallId) } }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.im_record_redo)) }
            }
            ToolCallChangeState.Superseded -> Text(stringResource(R.string.im_record_superseded), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (conflict) Text(stringResource(R.string.im_record_conflict), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
        history?.let { target ->
            TextButton(onClick = { nav.navigate(target) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.im_record_history))
            }
        }
    }
}
