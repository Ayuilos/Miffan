package me.ayuilos.miffan.ui.components.ui

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Provider-independent generation meaning. Character renderers choose their own expression. */
enum class AssistantGenerationPhase { None, Waiting, Reasoning, Responding }

internal fun assistantGenerationPhase(message: UIMessage?, loading: Boolean): AssistantGenerationPhase {
    if (!loading) return AssistantGenerationPhase.None
    if (message?.role != MessageRole.ASSISTANT) return AssistantGenerationPhase.Waiting
    for (part in message.parts.asReversed()) {
        when (part) {
            is UIMessagePart.Text -> if (part.text.isNotBlank()) return AssistantGenerationPhase.Responding
            is UIMessagePart.Reasoning -> return if (part.finishedAt == null) {
                AssistantGenerationPhase.Reasoning
            } else AssistantGenerationPhase.Waiting
            is UIMessagePart.Tool, is UIMessagePart.ServerTool -> return AssistantGenerationPhase.Waiting
            is UIMessagePart.Image, is UIMessagePart.Audio, is UIMessagePart.Video ->
                return AssistantGenerationPhase.Responding
            else -> Unit
        }
    }
    return AssistantGenerationPhase.Waiting
}
