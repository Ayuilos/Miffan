package me.ayuilos.miffan.ui.pages.chat

import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.repository.RemoteConversationShellCommand
import me.ayuilos.miffan.data.repository.RemoteConversationShellInfo
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatTerminalStatusTest {
    private val chatId = Uuid.random()
    private val target = WorkspaceToolTargetSnapshot("assistant", "permission", "workspace", null, "REMOTE",
        remoteHostId = "host", remoteRoot = "/srv", hostConnectionRevision = "v1",
        workspaceName = "Remote", remoteHostLabel = "user@server", conversationId = chatId.toString())
    private val live = RemoteConversationShellInfo("session", target)
    private fun tool(pending: Boolean = false, name: String = "workspace_terminal") = UIMessagePart.Tool(
        "call", name, """{"command":"sudo -v"}""",
        output = if (pending) emptyList() else listOf(UIMessagePart.Text("""{"sessionId":"session","sessionOpen":true}""")),
        approvalState = if (pending) ToolApprovalState.Pending else ToolApprovalState.Approved,
        workspaceTarget = target, terminalRequestId = "request",
    )
    private fun conversation(vararg parts: UIMessagePart): Conversation {
        val nodes = parts.map { MessageNode(message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(it))) }
        return Conversation(id = chatId, assistantId = Uuid.random(), messageNodes = nodes.mapIndexed { index, node ->
            node.copy(parentId = nodes.getOrNull(index - 1)?.id, selectedChildId = nodes.getOrNull(index + 1)?.id)
        })
    }

    @Test fun connectedSessionStaysDiscoverableAfterLongConversation() {
        val chat = conversation(tool(), *Array(200) { UIMessagePart.Text("Later message $it") })
        val status = requireNotNull(resolveChatTerminalStatus(chat, live))
        assertEquals(ChatTerminalPhase.CONNECTED, status.phase)
        assertEquals("session", status.sessionId)
        assertEquals(chat.messageNodes.first().id, status.nodeId)
    }

    @Test fun waitingCommandShowsBeforeOpeningAnyTerminal() {
        val chat = conversation(tool(pending = true))
        val status = requireNotNull(resolveChatTerminalStatus(chat, null))
        assertEquals(ChatTerminalPhase.WAITING, status.phase)
        assertNull(status.sessionId)
        assertEquals(chat.messageNodes.single().id, status.nodeId)
        assertEquals(ChatTerminalPhase.APPROVAL,
            resolveChatTerminalStatus(conversation(tool(pending = true, name = "workspace_shell")), live)?.phase)
    }

    @Test fun savedConnectedReceiptNeverClaimsTheSessionIsStillAlive() {
        val status = requireNotNull(resolveChatTerminalStatus(conversation(tool()), null))
        assertEquals(ChatTerminalPhase.ENDED, status.phase)
        assertNull(status.sessionId)
        assertEquals("session", status.key)
    }

    @Test fun activeCommandCanBeStoppedEvenWithoutAVisibleOrSavedCard() {
        val running = live.copy(activeCommand = RemoteConversationShellCommand("execution", "sleep 30", false))
        val status = requireNotNull(resolveChatTerminalStatus(conversation(UIMessagePart.Text("Hello")), running))
        assertEquals(ChatTerminalPhase.RUNNING, status.phase)
        assertNull(status.nodeId)
        assertEquals("session", status.sessionId)
    }

    @Test fun interactionStateTakesPriorityOverPendingApprovalLabel() {
        val running = live.copy(activeCommand = RemoteConversationShellCommand("execution", "sudo -v", true))
        val chat = conversation(tool(pending = true))
        val status = requireNotNull(resolveChatTerminalStatus(chat, running))
        assertEquals(ChatTerminalPhase.INTERACTIVE, status.phase)
        assertEquals(chat.messageNodes.single().id, status.nodeId)
    }

    @Test fun forkDoesNotExposeAnotherChatsConnectionOrControls() {
        val fork = conversation(tool(pending = true)).copy(id = Uuid.random())
        assertNull(resolveChatTerminalStatus(fork, live))
    }

    @Test fun unrelatedChatsHaveNoTerminalStatus() {
        assertNull(resolveChatTerminalStatus(conversation(UIMessagePart.Text("Hello")), null))
    }
}
