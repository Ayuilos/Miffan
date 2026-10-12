package me.ayuilos.miffan.data.ai

import android.util.Log
import io.mockk.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.audit.waitForApproval
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test

class AgentRelayApprovalTest {
    private val assistant = Assistant(streamOutput = false)
    private val relay = AgentAccess.forAssistant(assistant, false).copy(ownerThreadNonOwnerTurn = true)
    private val input = JsonObject(emptyMap())
    private fun tool(name: String, target: WorkspaceToolTargetSnapshot? = null) = Tool(
        name = name, description = name, workspaceTarget = target,
        autoApprovedBy = { ToolDecisionVia.NO_ASK_SETTING }, execute = { emptyList() },
    )
    private fun target(kind: String) = WorkspaceToolTargetSnapshot(
        assistantId = assistant.id.toString(), workspacePermissionRevision = "1",
        workspaceId = "w", scopeId = null, kind = kind, workspaceName = "w",
    )

    @Test fun localAllowlistIsExemptAndExistingInteractionGateSurvives() {
        OWNER_THREAD_LOCAL_TOOL_ALLOWLIST.forEach { name ->
            val original = tool(name, target("LOCAL"))
            val wrapped = listOf(original).withAgentRelayApproval(relay).single()
            assertSame(original, wrapped)
            assertFalse(wrapped.needsApproval(input))
            assertNull(wrapped.approvalReason)
        }
        val ask = tool("ask_user").copy(needsApproval = { true })
        assertSame(ask, listOf(ask).withAgentRelayApproval(relay).single())
    }

    @Test fun everyOtherToolAndUnverifiedOrRemoteReadsRequireApproval() {
        val names = listOf("send_message", "workspace_write_file", "workspace_edit_file", "workspace_shell",
            "workspace_terminal", "workspace_publish", "workspace_fetch_url", "search_web", "scrape_web",
            "miffan_help", "mcp__server__calendar_query", "computer_click", "clipboard_tool",
            "calendar_create", "text_to_speech", "eval_javascript", "extensions_catalog", "future_tool")
        val originals = names.map(::tool) + listOf(null, "REMOTE", "FUTURE").map {
            tool("workspace_read_file", it?.let(::target))
        }
        originals.withAgentRelayApproval(relay).forEach { wrapped ->
            assertTrue(wrapped.name, wrapped.needsApproval(input))
            assertNull(wrapped.autoApprovedBy(input))
            assertEquals(OWNER_THREAD_RELAY_APPROVAL_REASON, wrapped.approvalReason)
        }
        // A future dynamic tool cannot gain a local exemption merely by reusing a safe name.
        val dynamicRead = listOf(tool("calendar_query")).withAgentRelayApproval(relay, dynamic = true).single()
        assertTrue(dynamicRead.needsApproval(input))
        assertEquals(OWNER_THREAD_RELAY_APPROVAL_REASON, dynamicRead.approvalReason)
    }

    @Test fun otherTurnsKeepToolAndBuiltInBehaviorAndConfiguredModelIsUnchanged() {
        val tools = listOf(tool("search_web"), tool("computer_click").copy(needsApproval = { true }))
        val model = Model(tools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext, BuiltInTools.ImageGeneration))
        for (access in listOf(AgentAccess.forAssistant(assistant, true), AgentAccess.forAssistant(assistant, false))) {
            assertSame(tools, tools.withAgentRelayApproval(access))
            assertSame(model, model.forAgentRelayTurn(access))
        }
        assertTrue(model.forAgentRelayTurn(relay).tools.isEmpty())
        assertEquals(3, model.tools.size)
    }

    @Test fun approvalReasonPersistsAlongsideProviderMetadataAndCardDecisions() {
        val original = UIMessagePart.Tool("call", "search_web", "{}",
            metadata = buildJsonObject { put("provider_field", "signature") })
        val pending = original.waitForApproval(123, OWNER_THREAD_RELAY_APPROVAL_REASON)
        val restored = JsonInstant.decodeFromString<UIMessagePart.Tool>(JsonInstant.encodeToString(pending))
        assertEquals(ToolApprovalState.Pending, restored.approvalState)
        assertEquals(123L, restored.approvalRecord?.requestedAt)
        assertEquals("signature", restored.metadata?.get("provider_field")?.jsonPrimitive?.content)
        assertEquals(OWNER_THREAD_RELAY_APPROVAL_REASON, restored.metadata?.get("approval_reason")?.jsonPrimitive?.content)
        assertEquals(restored.metadata, restored.copy(approvalState = ToolApprovalState.Approved).metadata)
    }

    @Test fun generationPausesForStaticMcpAndNewDynamicToolsAndDisablesBuiltIns() = runTest {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        try {
            val configured = Model(modelId = "test", abilities = listOf(ModelAbility.TOOL),
                tools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext))
            val setting = ProviderSetting.OpenAI(models = listOf(configured))
            val provider = mockk<Provider<ProviderSetting.OpenAI>>()
            val manager = mockk<ProviderManager>()
            every { manager.getProviderByType(setting) } returns provider
            val params = slot<TextGenerationParams>()
            val handler = GenerationHandler(mockk(), manager, JsonInstant, mockk(), mockk(), mockk())
            for (name in listOf("search_web", "mcp__server__tool", "computer_new_dynamic")) {
                coEvery { provider.generateText(setting, any(), capture(params)) } returns TextGenerationResult(
                    "test", "test", UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("call", name, "{}"))),
                )
                val execute = mockk<suspend (JsonElement) -> List<UIMessagePart>>()
                val original = tool(name).copy(execute = execute)
                val dynamic = name.startsWith("computer_")
                val chunks = handler.generateText(
                    Settings(providers = listOf(setting)), configured.forAgentRelayTurn(relay),
                    listOf(UIMessage.user("partner event")), assistant = assistant, access = relay,
                    tools = (if (dynamic) emptyList() else listOf(original)).withAgentRelayApproval(relay),
                    dynamicTools = { (if (dynamic) listOf(original) else emptyList()).withAgentRelayApproval(relay, dynamic = true) },
                ).toList()
                val call = (chunks.last() as GenerationChunk.Messages).messages.last().getTools().single()
                assertEquals(ToolApprovalState.Pending, call.approvalState)
                assertEquals(OWNER_THREAD_RELAY_APPROVAL_REASON, call.metadata?.get("approval_reason")?.jsonPrimitive?.content)
                assertTrue(params.captured.model.tools.isEmpty())
                coVerify(exactly = 0) { execute.invoke(any()) }
            }
        } finally {
            unmockkStatic(Log::class)
        }
    }
}
