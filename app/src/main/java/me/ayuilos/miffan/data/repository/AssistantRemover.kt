package me.ayuilos.miffan.data.repository

import androidx.core.net.toUri
import me.ayuilos.miffan.data.datastore.DEFAULT_ASSISTANTS_IDS
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import kotlin.uuid.Uuid

/** Deletes an assistant together with its files, terminals, memories and conversations. */
class AssistantRemover(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    fun canRemove(assistantId: Uuid) = assistantId !in DEFAULT_ASSISTANTS_IDS

    suspend fun remove(assistant: Assistant) {
        require(canRemove(assistant.id)) { "Built-in assistant ${assistant.id} cannot be removed" }
        workspaceRepository.closeAssistantTerminals(assistant.id.toString())
        cleanupAssistantFiles(assistant)
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.filter { it.id != assistant.id })
        }
        memoryRepository.deleteMemoriesOfAssistant(assistant.id.toString())
        conversationRepo.deleteConversationOfAssistant(assistant.id)
    }

    private fun cleanupAssistantFiles(assistant: Assistant) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
        }

        if (uris.isNotEmpty()) {
            filesManager.deleteChatFiles(uris)
        }
    }
}
