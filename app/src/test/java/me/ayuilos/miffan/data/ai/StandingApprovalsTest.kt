package me.ayuilos.miffan.data.ai

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.ai.tools.createWorkspaceTools
import me.ayuilos.miffan.data.audit.recordAutomaticApproval
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.ComputerUseMode
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolDecisionVia
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import org.junit.Assert.*
import org.junit.Test

class StandingApprovalsTest {
    private val empty = JsonObject(emptyMap())
    private fun tool(name: String) = Tool(name, name, needsApproval = { true }, execute = { emptyList() })

    @Test fun onlyExplicitlyEligibleNamesHaveKeys() {
        listOf("calendar_create", "workspace_write_file", "workspace_edit_file", "mcp__server__write", "mcp__other__write")
            .forEach { assertEquals(it, standingApprovalKey(it)) }
        listOf("computer_click", "computer_screenshot", "computer_workspace_write_file", "workspace_read_file",
            "workspace_fetch_url", "workspace_shell", "workspace_terminal", "workspace_publish_files", "send_message",
            "update_my_profile", "create_partner", "create_group_chat", "invite_to_group_chat", "organize_chat_list",
            "draft_bug_report", "generate_image", "request_web_search", "ask_user", "extensions_apply_changes",
            "calendar_query", "update_my_preferences", "future_tool", "MCP__server__write", "")
            .forEach { assertNull(it, standingApprovalKey(it)) }
    }

    @Test fun standingGrantsAreExactAssistantScopedAndLeaveWorkspaceChecksIntact() {
        val names = listOf("calendar_create", "mcp__one__write", "mcp__two__write", "workspace_edit_file", "computer_click")
        val originals = names.map(::tool)
        val assistant = Assistant(standingToolApprovals = names.toSet() - "mcp__two__write")
        val wrapped = originals.withStandingApprovals(assistant)
        wrapped.take(2).forEach {
            assertFalse(it.needsApproval(empty))
            assertEquals(ToolDecisionVia.STANDING_ALWAYS_ALLOW, it.autoApprovedBy(empty))
            assertEquals(ToolDecisionVia.STANDING_ALWAYS_ALLOW,
                UIMessagePart.Tool("call", it.name, "{}").recordAutomaticApproval(it, 12).approvalRecord?.via)
        }
        (2..4).forEach { assertSame(originals[it], wrapped[it]) }
        originals.withStandingApprovals(Assistant()).forEachIndexed { i, it -> assertSame(originals[i], it) }
        val decoded = JsonInstant.decodeFromString<Assistant>("{}")
        assertTrue(decoded.standingToolApprovals.isEmpty())
        assertEquals(assistant, JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(assistant)))
    }

    @Test fun relayAlwaysOverridesStandingGrantsForBothStaticAndDynamicTools() {
        val assistant = Assistant(standingToolApprovals = setOf("calendar_create", "mcp__one__write"))
        val relay = AgentAccess.forAssistant(assistant, false).copy(ownerThreadNonOwnerTurn = true)
        for (dynamic in listOf(false, true)) {
            assistant.standingToolApprovals.map(::tool).withStandingApprovals(assistant)
                .withAgentRelayApproval(relay, dynamic).forEach {
                    assertTrue(it.needsApproval(empty))
                    assertNull(it.autoApprovedBy(empty))
                    assertEquals(OWNER_THREAD_RELAY_APPROVAL_REASON, it.approvalReason)
                }
        }
    }

    @Test fun workspaceStandingGrantRemovesOnlyBaseApprovalAndNeverComputerApproval() = runBlocking {
        val repository = mockk<WorkspaceRepository>()
        val workspace = WorkspaceEntity("ws", "Local", "/local", createdAt = 1, updatedAt = 1,
            toolApprovals = "{\"workspace_edit_file\":true,\"workspace_write_file\":true}")
        val target = WorkspaceToolTargetSnapshot("assistant", "revision", "ws", null, "LOCAL", workspaceName = "Local")
        coEvery { repository.getById("ws") } returns workspace
        coEvery { repository.currentWorkspaceToolTarget("assistant", "ws", null) } returns target
        val grants = setOf("workspace_edit_file", "workspace_write_file")
        suspend fun tools(mode: ComputerUseMode?, keys: Set<String>) = createWorkspaceTools(
            "assistant", "ws", null, false, true, null, repository, computerUse = mode, standingToolApprovals = keys,
        ).filter { it.name in grants }
        val inside = buildJsonObject { put("path", "/workspace/note.txt") }
        tools(null, emptySet()).forEach { assertTrue(it.needsApproval(inside)) }
        tools(null, grants).forEach { tool ->
            for (path in listOf("/workspace/note.txt", "/tmp/note.txt")) {
                val input = buildJsonObject { put("path", path) }
                assertFalse(tool.needsApproval(input))
                assertEquals(ToolDecisionVia.STANDING_ALWAYS_ALLOW, tool.autoApprovedBy(input))
            }
            for (path in listOf("/etc/hosts", "/workspace/../etc/hosts", "relative", "/tmp-other/file")) {
                val input = buildJsonObject { put("path", path) }
                assertTrue(path, tool.needsApproval(input))
                assertNull(tool.autoApprovedBy(input))
            }
            assertTrue(tool.needsApproval(empty))
        }
        tools(ComputerUseMode.ASK, grants).forEach { assertTrue(it.needsApproval(inside)) }
        tools(ComputerUseMode.AUTO, grants).forEach {
            assertFalse(it.needsApproval(inside))
            assertNull(it.autoApprovedBy(inside))
        }
    }
}
