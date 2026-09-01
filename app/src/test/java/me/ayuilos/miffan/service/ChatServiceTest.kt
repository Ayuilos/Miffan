package me.ayuilos.miffan.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.ayuilos.miffan.data.ai.tools.local.LocalToolOption
import me.ayuilos.miffan.data.ai.tools.WORKSPACE_SHELL_TOOL_NAME
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.model.toLinearMessageNodes
import me.ayuilos.miffan.data.model.withWorkspaceBinding
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTest {
    @Test
    fun `editing a user message creates a sibling branch and preserves the original reply`() {
        val originalUserMessage = UIMessage.user("Original question")
        val editedUserMessage = UIMessage.user("Edited question")
        val originalReply = UIMessage.assistant("Original reply")
        val editedReply = UIMessage.assistant("Edited reply")
        val originalNodes = listOf(originalUserMessage, originalReply).toLinearMessageNodes()
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = originalNodes,
            selectedRootId = originalNodes.first().id,
        )

        val editedNode = MessageNode(message = editedUserMessage)
        val editedConversation = conversation.addNodeAndSelect(editedNode)
        val withReply = editedConversation.updateCurrentMessages(listOf(editedUserMessage, editedReply))

        assertEquals(listOf(editedUserMessage, editedReply), withReply.currentMessages)
        assertEquals(
            listOf(originalUserMessage, originalReply),
            withReply.selectNode(originalNodes.first().id).currentMessages,
        )
    }

    @Test
    fun `regenerating an assistant reply creates a sibling and switching restores descendants`() {
        val userMessage = UIMessage.user("Question")
        val originalReply = UIMessage.assistant("Original reply")
        val followUp = UIMessage.user("Follow-up")
        val followUpReply = UIMessage.assistant("Follow-up reply")
        val originalNodes = listOf(userMessage, originalReply, followUp, followUpReply).toLinearMessageNodes()
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = originalNodes,
            selectedRootId = originalNodes.first().id,
        )
        val regeneratedReply = UIMessage.assistant("Regenerated reply")

        val regenerated = conversation.updateCurrentMessages(listOf(userMessage, regeneratedReply))

        assertEquals(listOf(userMessage, regeneratedReply), regenerated.currentMessages)
        assertEquals(
            listOf(userMessage, originalReply, followUp, followUpReply),
            regenerated.selectNode(originalNodes[1].id).currentMessages,
        )
    }

    @Test
    fun `deleting the selected branch removes only its subtree and restores a sibling`() {
        val originalMessages = listOf(
            UIMessage.user("Original question"),
            UIMessage.assistant("Original reply"),
        )
        val originalNodes = originalMessages.toLinearMessageNodes()
        val base = Conversation(
            assistantId = Uuid.random(),
            messageNodes = originalNodes,
            selectedRootId = originalNodes.first().id,
        )
        val editedUser = UIMessage.user("Edited question")
        val editedRoot = MessageNode(message = editedUser)
        val edited = base.addNodeAndSelect(editedRoot)
            .updateCurrentMessages(listOf(editedUser, UIMessage.assistant("Edited reply")))

        val restored = edited.deleteNodeSubtree(editedRoot.id)

        assertEquals(originalMessages, restored.currentMessages)
        assertEquals(originalNodes.map { it.id }.toSet(), restored.messageNodes.map { it.id }.toSet())
    }

    @Test
    fun `completion feedback requires an assistant reply without pending approval`() {
        val reply = UIMessage.assistant("Done")
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode.of(reply)))
        assertEquals(reply.id, conversation.completedAssistantReplyId())
        assertNull(conversation.completedAssistantReplyId(conversation))
        assertNull(conversation.copy(messageNodes = emptyList()).completedAssistantReplyId())
        assertNull(conversation.copy(messageNodes = listOf(MessageNode.of(UIMessage.user("Queued")))).completedAssistantReplyId())
        assertNull(conversation.copy(messageNodes = listOf(MessageNode.of(UIMessage.assistant("")))).completedAssistantReplyId())
        val pending = reply.copy(parts = reply.parts + tool("approval", "ask_user", ToolApprovalState.Pending))
        assertNull(conversation.copy(messageNodes = listOf(MessageNode.of(pending))).completedAssistantReplyId())
    }

    @Test
    fun `always allow approves pending shell tools on selected branches only`() {
        val selectedMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                tool("shell-1", WORKSPACE_SHELL_TOOL_NAME, ToolApprovalState.Pending),
                tool("other", "ask_user", ToolApprovalState.Pending),
                tool("shell-auto", WORKSPACE_SHELL_TOOL_NAME, ToolApprovalState.Auto),
            ),
        )
        val unselectedMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(tool("shell-old", WORKSPACE_SHELL_TOOL_NAME, ToolApprovalState.Pending)),
        )
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode(message = unselectedMessage),
                MessageNode(message = selectedMessage),
            ),
            selectedRootId = null,
        )
        val selectedConversation = conversation.selectNode(conversation.messageNodes[1].id)

        val updated = selectedConversation.approvePendingWorkspaceShellTools()
        val selectedTools = updated.getMessageNodeByMessageId(selectedMessage.id)!!.message.parts
            .filterIsInstance<UIMessagePart.Tool>()

        assertEquals(ToolApprovalState.Approved, selectedTools[0].approvalState)
        assertEquals(ToolApprovalState.Pending, selectedTools[1].approvalState)
        assertEquals(ToolApprovalState.Auto, selectedTools[2].approvalState)
        assertEquals(
            ToolApprovalState.Pending,
            (updated.getMessageNodeByMessageId(unselectedMessage.id)!!.message.parts.single() as UIMessagePart.Tool).approvalState,
        )
        assertTrue(updated.hasPendingToolApprovals())
        assertFalse(updated.hasPendingWorkspaceShellTools())
    }

    @Test
    fun `always allow resolves the whole pending shell batch`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode.of(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = (1..4).map { index ->
                            tool("shell-$index", WORKSPACE_SHELL_TOOL_NAME, ToolApprovalState.Pending)
                        },
                    )
                )
            ),
        )

        val updated = conversation.approvePendingWorkspaceShellTools()

        assertFalse(updated.hasPendingToolApprovals())
        assertFalse(updated.hasPendingWorkspaceShellTools())
        assertTrue(
            updated.messageNodes.single().currentMessage.parts
                .filterIsInstance<UIMessagePart.Tool>()
                .all { it.approvalState is ToolApprovalState.Approved }
        )
    }

    @Test
    fun `fork conversation inherits folder and workspace context`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            title = "Source conversation",
            messageNodes = emptyList(),
            workspaceCwd = "/workspace/project",
            folderId = Uuid.random(),
        )

        val fork = createForkConversation(source, emptyList())

        assertNotEquals(source.id, fork.id)
        assertEquals(source.assistantId, fork.assistantId)
        assertEquals(source.workspaceCwd, fork.workspaceCwd)
        assertEquals(source.folderId, fork.folderId)
        assertEquals("", fork.title)
        assertFalse(fork.isPinned)
    }

    @Test
    fun `forking from an edited branch copies only that complete path into a new conversation`() {
        val openingQuestion = UIMessage.user("Opening question")
        val openingReply = UIMessage.assistant("Opening reply")
        val originalFollowUp = UIMessage.user("Original follow-up")
        val originalReply = UIMessage.assistant("Original follow-up reply")
        val originalNodes = listOf(
            openingQuestion,
            openingReply,
            originalFollowUp,
            originalReply,
        ).toLinearMessageNodes()
        val base = Conversation(
            assistantId = Uuid.random(),
            messageNodes = originalNodes,
            selectedRootId = originalNodes.first().id,
        )
        val editedFollowUp = UIMessage.user("Edited follow-up")
        val editedReply = UIMessage.assistant("Edited follow-up reply")
        val source = base
            .addNodeAndSelect(
                MessageNode(
                    message = editedFollowUp,
                    parentId = originalNodes[1].id,
                )
            )
            .appendMessage(editedReply)

        val copiedNodes = requireNotNull(source.copyMessagePathForFork(editedReply.id))
        val fork = createForkConversation(source, copiedNodes)

        assertNotEquals(source.id, fork.id)
        assertEquals(
            listOf(openingQuestion, openingReply, editedFollowUp, editedReply),
            fork.currentMessages,
        )
        assertFalse(fork.currentMessages.contains(originalFollowUp))
        assertFalse(fork.currentMessages.contains(originalReply))
        assertTrue(copiedNodes.map { it.id }.none(source.messageNodes.map { it.id }.toSet()::contains))
        assertNull(copiedNodes.first().parentId)
        copiedNodes.zipWithNext().forEach { (parent, child) ->
            assertEquals(parent.id, child.parentId)
            assertEquals(child.id, parent.selectedChildId)
        }
        assertNull(copiedNodes.last().selectedChildId)
        assertEquals(
            listOf(openingQuestion, openingReply, originalFollowUp, originalReply),
            source.selectNode(originalNodes[2].id).currentMessages,
        )
    }

    private fun tool(
        id: String,
        name: String,
        state: ToolApprovalState,
    ) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = "{}",
        approvalState = state,
    )

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `external web search is disabled when assistant preference is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model()

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `external web search is enabled when assistant preference is enabled`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model()

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search suppresses enabled external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search remains exclusive when external web search is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `unrelated built-in tools do not suppress external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.UrlContext))

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `extension management is disabled unless assistant opts in`() {
        val model = Model(abilities = listOf(ModelAbility.TOOL))

        assertFalse(shouldEnableExtensionManagement(Assistant(), model))
    }

    @Test
    fun `extension management requires model tool ability`() {
        val assistant = Assistant(
            localTools = listOf(LocalToolOption.ExtensionManagement),
        )

        assertFalse(shouldEnableExtensionManagement(assistant, Model()))
        assertTrue(
            shouldEnableExtensionManagement(
                assistant,
                Model(abilities = listOf(ModelAbility.TOOL)),
            )
        )
    }

    @Test
    fun `persistent shell approval changes only the matching assistant scope`() {
        val workspaceId = Uuid.random()
        val first = Assistant().withWorkspaceBinding(workspaceId)
        val second = Assistant().withWorkspaceBinding(workspaceId)
        val updated = Settings(assistants = listOf(first, second))
            .withWorkspaceShellAllowedFor(first)

        assertFalse(updated.assistants[0].workspaceShellApprovalRequired)
        assertTrue(updated.assistants[1].workspaceShellApprovalRequired)
    }

    @Test
    fun `stale shell approval cannot cross a changed binding`() {
        val assistant = Assistant().withWorkspaceBinding(Uuid.random())
        val rebound = assistant.withWorkspaceBinding(Uuid.random())
        val updated = Settings(assistants = listOf(rebound))
            .withWorkspaceShellAllowedFor(assistant)

        assertTrue(updated.assistants.single().workspaceShellApprovalRequired)
    }
}
