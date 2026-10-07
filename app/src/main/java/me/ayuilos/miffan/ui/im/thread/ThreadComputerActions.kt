package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.PartnerComputer
import me.ayuilos.miffan.data.ai.tools.COMPUTER_TOOL_PREFIX
import me.ayuilos.miffan.ui.components.message.ComputerToolStatus
import me.ayuilos.miffan.ui.components.message.computerActionTitle
import me.ayuilos.miffan.ui.components.message.computerToolNeedsForegroundWarning
import me.ayuilos.miffan.ui.components.message.computerToolStatus
import me.ayuilos.miffan.ui.components.message.tools.DefaultToolPreview
import me.ayuilos.miffan.ui.components.message.tools.ToolUIContext
import me.ayuilos.miffan.ui.components.richtext.ZoomableAsyncImage
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart

private val computerObservationNames = setOf(
    "get_desktop_state", "get_window_state", "list_windows", "list_apps", "read_guide",
    "get_screen_size", "get_cursor_position", "zoom", "verify_state",
)

internal fun UIMessagePart.Tool.isComputerTool() = toolName.startsWith(COMPUTER_TOOL_PREFIX)

internal data class ThreadComputerEvidence(
    val tools: List<UIMessagePart.Tool>,
    val actionCount: Int,
    val screenshot: String?,
    val status: ComputerToolStatus?,
) {
    val latest: UIMessagePart.Tool get() = tools.last()
}

internal fun threadComputerEvidence(parts: List<UIMessagePart>): ThreadComputerEvidence? {
    val tools = parts.filterIsInstance<UIMessagePart.Tool>().filter { it.isComputerTool() }
    if (tools.isEmpty()) return null
    return ThreadComputerEvidence(
        tools = tools,
        actionCount = tools.count {
            it.isExecuted && it.approvalState !is ToolApprovalState.Denied &&
                computerToolStatus(it.output) == null &&
                it.toolName.removePrefix(COMPUTER_TOOL_PREFIX) !in computerObservationNames
        },
        screenshot = tools.flatMap { it.output }.filterIsInstance<UIMessagePart.Image>().lastOrNull()?.url,
        status = computerToolStatus(tools.flatMap { it.output }),
    )
}

internal fun latestComputerAction(parts: List<UIMessagePart>): UIMessagePart.Tool? =
    (parts.lastOrNull { it is UIMessagePart.Tool || it is UIMessagePart.ServerTool } as? UIMessagePart.Tool)
        ?.takeIf { it.isComputerTool() }

internal fun computerTargetName(tool: UIMessagePart.Tool, computer: PartnerComputer?, fallback: String): String =
    tool.workspaceTarget?.remoteHostName?.takeIf { it.isNotBlank() } ?: computer?.name ?: fallback

internal fun canOpenComputer(tool: UIMessagePart.Tool, computer: PartnerComputer?): Boolean =
    computer?.showsEntry == true &&
        (tool.workspaceTarget?.remoteHostId == null || tool.workspaceTarget?.remoteHostId == computer.hostId)

@Composable
internal fun computerActionText(tool: UIMessagePart.Tool): String {
    val action = computerActionTitle(tool.toolName, tool.inputAsJson())
    val title = action.label?.let { stringResource(it) } ?: tool.toolName
    return action.detail?.let { stringResource(R.string.computer_use_action_detail, title, it) } ?: title
}

@Composable
internal fun ThreadComputerScreenshot(url: String, maxHeight: Dp = 160.dp, preview: Boolean = true) {
    val modifier = Modifier.heightIn(max = maxHeight)
        .then(if (maxHeight <= 64.dp) Modifier.widthIn(max = 96.dp) else Modifier)
        .wrapContentWidth().clip(MaterialTheme.shapes.medium)
    if (preview) ZoomableAsyncImage(url, stringResource(R.string.im_computer_screenshot), modifier)
    else AsyncImage(model = url, contentDescription = null, modifier = modifier)
}

@Composable
internal fun ThreadComputerResultStatus(status: ComputerToolStatus?) {
    if (status == null) return
    val error = status == ComputerToolStatus.ERROR
    Text(stringResource(if (error) R.string.im_computer_action_error else R.string.im_computer_taken_over),
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ThreadComputerLiveCard(evidence: ThreadComputerEvidence, name: String, canOpen: Boolean, onScreen: () -> Unit) {
    Card(onClick = onScreen, enabled = canOpen,
        colors = CardDefaults.cardColors(containerColor = if (evidence.status == ComputerToolStatus.ERROR)
            MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.im_computer_operating, name), style = MaterialTheme.typography.titleSmall)
            }
            Text(computerActionText(evidence.latest), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ThreadComputerResultStatus(evidence.status)
            evidence.screenshot?.let { ThreadComputerScreenshot(it, preview = false) }
        }
    }
}

@Composable
internal fun ThreadComputerSummary(evidence: ThreadComputerEvidence, name: String, canOpen: Boolean, onScreen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (evidence.tools.all { it.toolName.removePrefix(COMPUTER_TOOL_PREFIX) in computerObservationNames }) stringResource(R.string.im_computer_observed, name)
            else pluralStringResource(R.plurals.im_computer_steps, evidence.actionCount, name, evidence.actionCount),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ThreadComputerResultStatus(evidence.status)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            evidence.screenshot?.let { ThreadComputerScreenshot(it, 64.dp) }
            TextButton(onClick = onScreen, enabled = canOpen) { Text(stringResource(R.string.im_computer_view_screen)) }
        }
    }
}

@Composable
internal fun ThreadComputerApprovalCard(
    tool: UIMessagePart.Tool,
    name: String,
    canOpen: Boolean,
    onScreen: () -> Unit,
    onApproval: (String, Boolean) -> Unit,
    compact: Boolean = false,
) {
    var answered by remember(tool.toolCallId) { mutableStateOf(false) }
    var details by remember(tool.toolCallId) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.im_computer_wants_to_operate, name), style = MaterialTheme.typography.titleSmall)
            Text(computerActionText(tool), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (computerToolNeedsForegroundWarning(tool.toolName, tool.inputAsJson())) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.im_computer_foreground_warning), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !answered, onClick = { answered = true; onApproval(tool.toolCallId, true) }) { Text(stringResource(R.string.im_computer_allow)) }
                OutlinedButton(enabled = !answered, onClick = { answered = true; onApproval(tool.toolCallId, false) }) { Text(stringResource(R.string.im_computer_decline)) }
            }
            Row {
                TextButton(onClick = onScreen, enabled = canOpen) { Text(stringResource(R.string.im_computer_view_screen)) }
                TextButton(onClick = { details = true }) { Text(stringResource(R.string.im_computer_details)) }
            }
        }
    }
    if (details) ModalBottomSheet(onDismissRequest = { details = false }) {
        DefaultToolPreview(ToolUIContext(tool, tool.inputAsJson(), null, false))
    }
}
