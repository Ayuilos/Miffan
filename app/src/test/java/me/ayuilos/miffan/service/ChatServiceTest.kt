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
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTest {
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
                MessageNode(
                    messages = listOf(unselectedMessage, selectedMessage),
                    selectIndex = 1,
                )
            ),
        )

        val updated = conversation.approvePendingWorkspaceShellTools()
        val selectedTools = updated.messageNodes.single().currentMessage.parts
            .filterIsInstance<UIMessagePart.Tool>()

        assertEquals(ToolApprovalState.Approved, selectedTools[0].approvalState)
        assertEquals(ToolApprovalState.Pending, selectedTools[1].approvalState)
        assertEquals(ToolApprovalState.Auto, selectedTools[2].approvalState)
        assertEquals(
            ToolApprovalState.Pending,
            (updated.messageNodes.single().messages[0].parts.single() as UIMessagePart.Tool).approvalState,
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
}
