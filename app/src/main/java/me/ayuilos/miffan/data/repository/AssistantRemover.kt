package me.ayuilos.miffan.data.repository

import androidx.core.net.toUri
import me.ayuilos.miffan.data.datastore.DEFAULT_ASSISTANTS_IDS
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

/** Deletes an assistant together with its files, terminals, memories and conversations. */
class AssistantRemover(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
    private val workspaceRepository: WorkspaceRepository,
    private val removePartnerChats: suspend (Uuid) -> Unit = {},
    /** Deletes a partner's own computer (a Space workspace) on its host, then the workspace. */
    private val deleteSpace: suspend (workspaceId: String) -> Unit = {},
) {
    fun canRemove(assistantId: Uuid) = assistantId !in DEFAULT_ASSISTANTS_IDS

    /**
     * With [withSpace], the partner's own computer goes too. Its host may be out of reach: then it stays, idle,
     * on the computer's page, where it can be given to another partner or deleted later.
     */
    suspend fun remove(assistant: Assistant, withSpace: Boolean = false) {
        require(canRemove(assistant.id)) { "Built-in assistant ${assistant.id} cannot be removed" }
        if (withSpace) assistant.workspaceId?.toString()
            ?.takeIf { workspaceRepository.getById(it)?.isSpace == true }
            ?.let { workspaceId ->
                try {
                    deleteSpace(workspaceId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Left idle on the computer's page.
                }
            }
        workspaceRepository.closeAssistantTerminals(assistant.id.toString())
        removePartnerChats(assistant.id)
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
