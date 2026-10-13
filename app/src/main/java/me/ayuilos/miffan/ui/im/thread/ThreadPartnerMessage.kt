package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.PartnerMessageTool
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.partnerName
import me.ayuilos.miffan.ui.modifier.shimmer
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.ToolDecision
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

internal fun UIMessagePart.Tool.isPartnerMessage(): Boolean = toolName == PartnerMessageTool.NAME

/** The send_message call as the thread shows it: who it went to and how it ended. */
private data class PartnerMessageCall(val to: Uuid?, val text: String, val status: String?, val chatId: Uuid?)

private fun UIMessagePart.Tool.partnerMessageCall(): PartnerMessageCall {
    val input = inputAsJson() as? JsonObject
    val result = output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text
        ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    val to = (result?.string(PartnerMessageTool.RESULT_PARTNER_ID) ?: input?.string(PartnerMessageTool.ARG_TO))
        ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    return PartnerMessageCall(
        to = to,
        text = input?.string(PartnerMessageTool.ARG_TEXT).orEmpty(),
        status = result?.string(PartnerMessageTool.RESULT_STATUS),
        chatId = result?.string(PartnerMessageTool.RESULT_CHAT_ID)?.let { runCatching { Uuid.parse(it) }.getOrNull() },
    )
}

/**
 * A partner contacting another partner, inside its reply: the owner's approval the first time,
 * then one line that says how it went and opens the partners' chat.
 */
@Composable
internal fun ThreadPartnerMessage(tool: UIMessagePart.Tool, sender: Assistant?, onApproval: (String, Boolean) -> Unit) {
    val call = remember(tool.input, tool.output) { tool.partnerMessageCall() }
    val target = LocalSettings.current.assistants.firstOrNull { it.id == call.to }
    val targetName = target?.let { partnerName(it) } ?: call.to?.toString().orEmpty()
    if (tool.approvalState is ToolApprovalState.Pending) {
        PartnerMessagePermission(tool, sender, target, targetName, call.text, onApproval)
        return
    }
    val nav = LocalNavController.current
    val declined = tool.approvalRecord?.decision == ToolDecision.DECLINED || tool.approvalState is ToolApprovalState.Denied
    val running = !declined && !tool.isExecuted
    val label = when {
        declined -> stringResource(R.string.im_partner_msg_declined, targetName)
        running -> stringResource(R.string.im_partner_msg_asking, targetName)
        call.status == PartnerMessageTool.STATUS_REPLIED -> stringResource(R.string.im_partner_msg_replied, targetName)
        call.status == PartnerMessageTool.STATUS_PENDING -> stringResource(R.string.im_partner_msg_pending, targetName)
        else -> stringResource(R.string.im_partner_msg_refused, targetName)
    }
    val chatId = call.chatId
    Surface(onClick = { chatId?.let { nav.navigate(Screen.PartnerChat(it.toString())) } }, enabled = chatId != null,
        color = Color.Transparent, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            target?.let { AssistantAvatar(it.name, it.avatar, Modifier.size(22.dp)) }
            Text(label, Modifier.weight(1f, fill = false).shimmer(isLoading = running), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (chatId != null) Text(stringResource(R.string.im_thread_view), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PartnerMessagePermission(
    tool: UIMessagePart.Tool,
    sender: Assistant?,
    target: Assistant?,
    targetName: String,
    text: String,
    onApproval: (String, Boolean) -> Unit,
) {
    val toolCallId = tool.toolCallId
    var answered by remember(toolCallId) { mutableStateOf(false) }
    val senderName = sender?.let { partnerName(it) }.orEmpty()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                target?.let { AssistantAvatar(it.name, it.avatar, Modifier.size(32.dp)) }
                Text(stringResource(R.string.im_partner_msg_permission_title, senderName, targetName),
                    style = MaterialTheme.typography.titleMedium)
            }
            if (text.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
                Text(text, Modifier.fillMaxWidth().padding(12.dp), maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            if (tool.isRelayApproval()) RelayApprovalNotice(tool)
            else Text(stringResource(R.string.im_partner_msg_permission_desc), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = !answered, onClick = { answered = true; onApproval(toolCallId, true) }) {
                    Text(stringResource(R.string.im_p5_allow))
                }
                OutlinedButton(modifier = Modifier.weight(1f), enabled = !answered, onClick = { answered = true; onApproval(toolCallId, false) }) {
                    Text(stringResource(R.string.im_p5_not_now))
                }
            }
        }
    }
}
