package me.ayuilos.miffan.data.ai

import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model

internal const val OWNER_THREAD_RELAY_APPROVAL_REASON = "owner_thread_non_owner_turn"

/**
 * R9: add a tool only if it reads locally without transmitting caller-provided content.
 * Exemption here preserves the tool's own interaction/approval rules (e.g. ask_user).
 * MCP and computer tools must never be exempted by matching their underlying tool names.
 * Network help stays gated even though it routes free-text topics locally.
 */
internal val OWNER_THREAD_LOCAL_TOOL_ALLOWLIST = setOf(
    "my_environment", "list_contacts", "app_diagnostics",
    "recent_chats", "conversation_search", "read_partner_chat",
    "get_time_info", "get_screen_time", "calendar_query", "use_skill", "ask_user",
    "workspace_read_file",
)

/** Apply after assembling tools, and again to each dynamic snapshot. Unknown tools fail closed. */
internal fun List<Tool>.withAgentRelayApproval(access: AgentAccess, dynamic: Boolean = false): List<Tool> {
    if (!access.ownerThreadNonOwnerTurn) return this
    return map { tool ->
        val localRead = tool.name != "workspace_read_file" ||
            tool.workspaceTarget?.kind == WorkspaceEntity.KIND_LOCAL
        if (!dynamic && tool.name in OWNER_THREAD_LOCAL_TOOL_ALLOWLIST && localRead) tool else tool.copy(
            needsApproval = { true },
            autoApprovedBy = { null },
            approvalReason = OWNER_THREAD_RELAY_APPROVAL_REASON,
        )
    }
}

/** Provider-side tools cannot stop for app approval; disable every built-in tool for this turn. */
internal fun Model.forAgentRelayTurn(access: AgentAccess): Model =
    if (access.ownerThreadNonOwnerTurn) copy(tools = emptySet()) else this
