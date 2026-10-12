package me.ayuilos.miffan.data.ai

import kotlin.uuid.Uuid
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.repository.MemoryRepository

/** Immutable authority shared by every tool in one generation. Null access is UI-only. */
data class AgentAccess(
    val callerId: Uuid,
    val ownerInitiated: Boolean,
    val memoryOwnerId: String,
) {
    fun canChangeAssistant(id: String): Boolean = ownerInitiated ||
        runCatching { Uuid.parse(id.trim()) == callerId }.getOrDefault(false)

    companion object {
        fun forTurn(assistant: Assistant, conversation: Conversation): AgentAccess =
            forAssistant(assistant, conversation.ownerTrigger() != null)

        fun forAssistant(assistant: Assistant, ownerInitiated: Boolean): AgentAccess = AgentAccess(
            callerId = assistant.id,
            ownerInitiated = ownerInitiated,
            memoryOwnerId = if (assistant.useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID
                else assistant.id.toString(),
        )
    }
}
