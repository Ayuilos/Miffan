package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot

/** A model supplies only tool name/input. The app attaches this target after receiving the call. */
internal fun captureWorkspaceToolTarget(call: UIMessagePart.Tool, definition: Tool?): UIMessagePart.Tool =
    if (call.toolName in WORKSPACE_TOOL_NAMES) {
        call.copy(workspaceTarget = definition?.workspaceTarget)
    } else call

/**
 * Binds only parts created by this provider request. A response may be appended to an existing
 * assistant message, so older parts must never acquire the current workspace's identity.
 * The first matching definition in this request owns each new call's target for its lifetime.
 */
internal class WorkspaceToolTargetBinder(
    initialMessages: List<UIMessage>,
    definitions: List<Tool>,
) {
    private val firstNewPartIndex = initialMessages.lastOrNull()
        ?.takeIf { it.role == MessageRole.ASSISTANT }?.parts?.size ?: 0
    private val definitionsByName = definitions.associateBy(Tool::name)
    private val capturedTargets = mutableMapOf<Int, WorkspaceToolTargetSnapshot?>()
    private var newToolCount = 0

    fun beginAttempt() {
        capturedTargets.clear()
        newToolCount = 0
    }

    fun bind(messages: List<UIMessage>): List<UIMessage> {
        val last = messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT } ?: return messages
        newToolCount = last.parts.drop(firstNewPartIndex).count { it is UIMessagePart.Tool }
        var changed = false
        val parts = last.parts.mapIndexed { index, part ->
            if (index < firstNewPartIndex || part !is UIMessagePart.Tool || !part.toolName.startsWith("workspace_")) {
                part
            } else {
                if (index !in capturedTargets && part.toolName in definitionsByName) {
                    capturedTargets[index] = captureWorkspaceToolTarget(
                        part,
                        definitionsByName[part.toolName],
                    ).workspaceTarget
                }
                // Until the name matches a tool actually offered for this request, discard any
                // target sent by the provider. Only the app can attach an execution identity.
                val target = capturedTargets[index]
                if (part.workspaceTarget == target) part else part.copy(workspaceTarget = target).also { changed = true }
            }
        }
        return if (changed) messages.dropLast(1) + last.copy(parts = parts) else messages
    }

    // Output transformers may insert text/reasoning parts before tools. Tool order is retained,
    // so select the final N tools rather than reusing the raw stream's part indexes.
    fun newToolPartIndexes(message: UIMessage): Set<Int> = message.parts.mapIndexedNotNull { index, part ->
        index.takeIf { part is UIMessagePart.Tool }
    }.takeLast(newToolCount).toSet()

    fun newTools(messages: List<UIMessage>): List<UIMessagePart.Tool> {
        val last = messages.lastOrNull() ?: return emptyList()
        val indexes = newToolPartIndexes(last)
        return last.parts.mapIndexedNotNull { index, part ->
            (part as? UIMessagePart.Tool)?.takeIf { index in indexes }
        }
    }
}

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
