package me.ayuilos.miffan.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.data.ai.tools.WORKSPACE_SHELL_TOOL_NAME
import me.ayuilos.miffan.data.ai.tools.WORKSPACE_TERMINAL_TOOL_NAME
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.repository.RemoteConversationShellInfo
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

internal enum class ChatTerminalPhase(val label: String) {
    WAITING("等待终端操作"), APPROVAL("等待命令确认"), INTERACTIVE("终端交互中"),
    RUNNING("命令执行中"), CONNECTED("终端已连接"), ENDED("终端已结束"),
}

internal data class ChatTerminalStatus(
    val phase: ChatTerminalPhase,
    val host: String,
    val nodeId: Uuid?,
    val sessionId: String?,
    val key: String,
)

private data class TerminalToolReference(val nodeId: Uuid, val tool: UIMessagePart.Tool) {
    val sessionId: String? get() = tool.output.filterIsInstance<UIMessagePart.Text>().firstNotNullOfOrNull {
        runCatching { Json.parseToJsonElement(it.text).jsonObject["sessionId"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    }
    val command: String? get() = runCatching {
        tool.inputAsJson().jsonObject["command"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}

/** Saved tool results are navigation anchors only; connection state always comes from the live pool. */
internal fun resolveChatTerminalStatus(
    conversation: Conversation,
    session: RemoteConversationShellInfo?,
): ChatTerminalStatus? {
    val conversationId = conversation.id.toString()
    fun references(nodes: List<MessageNode>) = nodes.asReversed().flatMap { node ->
        node.message.getTools().asReversed().filter {
            it.toolName in setOf(WORKSPACE_SHELL_TOOL_NAME, WORKSPACE_TERMINAL_TOOL_NAME) &&
                it.workspaceTarget?.kind == "REMOTE" && it.workspaceTarget?.conversationId == conversationId
        }.map { TerminalToolReference(node.id, it) }
    }
    val current = references(conversation.currentMessageNodes)
    val live = session?.takeIf { it.target.conversationId == conversationId }
    val active = live?.activeCommand
    val pending = current.lastOrNull { it.tool.isPending && !it.tool.isExecuted }
    val anchor = if (live != null) {
        val all = references(conversation.messageNodes)
        active?.let { command -> all.lastOrNull {
            !it.tool.isExecuted && it.command == command.command && it.tool.workspaceTarget?.sameTarget(live.target) == true
        } }
            ?: current.firstOrNull { it.sessionId == live.sessionId }
            ?: all.firstOrNull { it.sessionId == live.sessionId }
    } else null
    val reference = if (active != null) anchor else pending ?: anchor
    if (live != null || pending != null) {
        val phase = when {
            active?.interactive == true -> ChatTerminalPhase.INTERACTIVE
            active != null -> ChatTerminalPhase.RUNNING
            pending?.tool?.toolName == WORKSPACE_TERMINAL_TOOL_NAME -> ChatTerminalPhase.WAITING
            pending != null -> ChatTerminalPhase.APPROVAL
            else -> ChatTerminalPhase.CONNECTED
        }
        val target = reference?.tool?.workspaceTarget ?: requireNotNull(live).target
        return ChatTerminalStatus(phase, target.remoteHostLabel ?: target.workspaceName,
            reference?.nodeId, live?.sessionId, live?.sessionId ?: requireNotNull(pending).tool.toolCallId)
    }
    val previous = current.firstOrNull { it.sessionId != null } ?: return null
    val target = requireNotNull(previous.tool.workspaceTarget)
    return ChatTerminalStatus(ChatTerminalPhase.ENDED, target.remoteHostLabel ?: target.workspaceName,
        previous.nodeId, null, requireNotNull(previous.sessionId))
}

@Composable
internal fun ChatTerminalStatusContent(
    conversation: Conversation,
    repository: WorkspaceRepository,
    onLocate: (Uuid) -> Unit,
    onError: (String) -> Unit,
) {
    val sessions by repository.conversationShellStates.collectAsStateWithLifecycle()
    val session = sessions[conversation.id.toString()]
    val status = remember(conversation, session) { resolveChatTerminalStatus(conversation, session) } ?: return
    var dismissed by remember(conversation.id) { mutableStateOf<String?>(null) }
    var closing by remember(conversation.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (status.phase == ChatTerminalPhase.ENDED && dismissed == status.key) return
    ChatTerminalStatusBar(
        status = status,
        closing = closing != null && closing == status.sessionId,
        onLocate = { status.nodeId?.let(onLocate) },
        onEnd = {
            val sessionId = status.sessionId
            if (sessionId != null && closing == null) scope.launch {
                closing = sessionId
                try {
                    // Do not close a replacement connection if the user tapped an older status.
                    repository.closeConversationTerminal(conversation.id.toString(), sessionId)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    onError(error.message ?: "结束终端会话失败")
                } finally { closing = null }
            }
        },
        onDismiss = { dismissed = status.key },
    )
}

@Composable
internal fun ChatTerminalStatusBar(
    status: ChatTerminalStatus,
    closing: Boolean,
    onLocate: () -> Unit,
    onEnd: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = status.phase in setOf(ChatTerminalPhase.RUNNING, ChatTerminalPhase.INTERACTIVE)
    val color = when (status.phase) {
        ChatTerminalPhase.WAITING, ChatTerminalPhase.APPROVAL -> MaterialTheme.colorScheme.tertiary
        ChatTerminalPhase.ENDED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("chat_terminal_status"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .clickable(enabled = status.nodeId != null, role = Role.Button,
                        onClickLabel = "定位终端命令", onClick = onLocate)
                    .padding(horizontal = 12.dp).testTag("chat_terminal_locate"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy || closing) CircularProgressIndicator(Modifier.size(12.dp), color = color, strokeWidth = 1.5.dp)
                else Box(Modifier.size(7.dp).background(color, CircleShape))
                Text("${if (closing) "正在结束会话" else status.phase.label} · ${status.host}",
                    color = color, style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                status.sessionId != null -> TextButton(onClick = onEnd, enabled = !closing,
                    modifier = Modifier.testTag("chat_terminal_end")) { Text("结束会话") }
                status.phase == ChatTerminalPhase.ENDED -> TextButton(onClick = onDismiss) { Text("收起") }
                status.nodeId != null -> TextButton(onClick = onLocate) { Text("去处理") }
            }
        }
    }
}
