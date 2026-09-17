package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot

/** A model supplies only tool name/input. The app attaches this target after receiving the call. */
internal fun captureWorkspaceToolTarget(call: UIMessagePart.Tool, definition: Tool?): UIMessagePart.Tool =
    if (call.toolName in WORKSPACE_TOOL_NAMES) {
        call.copy(workspaceTarget = definition?.workspaceTarget)
    } else call

internal fun workspaceToolTargetError(
    call: UIMessagePart.Tool,
    currentTarget: WorkspaceToolTargetSnapshot?,
): String? {
    if (call.toolName !in WORKSPACE_TOOL_NAMES) return null
    val original = call.workspaceTarget
        ?: return "This Workspace tool call has no saved execution target. Please request it again."
    if (currentTarget == null) {
        return "The original Workspace target or tool capability is no longer available. Please request it again."
    }
    if (!original.sameTarget(currentTarget)) {
        return "The Workspace target or permissions changed after this tool call was created. Please request it again."
    }
    return null
}

/** The only dispatch path used by GenerationHandler for executable tools. */
internal suspend fun executeToolWithTargetGuard(
    call: UIMessagePart.Tool,
    definition: Tool?,
    input: JsonElement,
): List<UIMessagePart> {
    workspaceToolTargetError(call, definition?.workspaceTarget)?.let(::error)
    return requireNotNull(definition) { "Tool ${call.toolName} not found" }.execute(input)
}
