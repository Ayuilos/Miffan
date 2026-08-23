package me.rerere.ai.provider.providers.openai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model

internal fun Model.resolveReasoningLevelForProvider(
    host: String,
    requested: ReasoningLevel,
): ReasoningLevel {
    // Models saved before reasoning metadata support have an unknown mandatory state.
    // OpenRouter rejects an explicit "none" for mandatory models, so prefer its
    // automatic/default behavior until the model metadata is refreshed.
    if (host == "openrouter.ai" &&
        requested == ReasoningLevel.OFF &&
        reasoningCapabilities == null
    ) {
        return ReasoningLevel.AUTO
    }
    return resolveReasoningLevel(requested)
}
