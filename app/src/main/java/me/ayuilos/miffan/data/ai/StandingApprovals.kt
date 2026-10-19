package me.ayuilos.miffan.data.ai

import me.ayuilos.miffan.data.model.Assistant
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolDecisionVia

/** Returns the exact permission key for tools that support an assistant-scoped standing grant. */
fun standingApprovalKey(toolName: String): String? = when {
    toolName in setOf("calendar_create", "workspace_write_file", "workspace_edit_file") -> toolName
    toolName.startsWith("mcp__") -> toolName
    else -> null
}

/** Applies standing grants before relay approval; workspace tools keep their own path checks. */
fun List<Tool>.withStandingApprovals(assistant: Assistant): List<Tool> = map { tool ->
    val key = standingApprovalKey(tool.name)
    if (!tool.name.startsWith("workspace_") && key != null && key in assistant.standingToolApprovals) {
        tool.copy(needsApproval = { false }, autoApprovedBy = { ToolDecisionVia.STANDING_ALWAYS_ALLOW })
    } else tool
}
