package me.ayuilos.miffan.ui.im

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.fts.MessageSearchResult
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.repository.ConversationRepository
import kotlin.uuid.Uuid

internal data class ImSearchHit(val result: MessageSearchResult, val partner: Assistant)
internal data class ImSearchResults(val query: String = "", val partners: List<Assistant> = emptyList(), val messages: List<ImSearchHit> = emptyList(), val failed: Boolean = false)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ImSearchVM(settingsStore: SettingsStore, private val conversations: ConversationRepository) : ViewModel() {
    val query = MutableStateFlow("")
    internal val results = combine(query.debounce(250), settingsStore.settingsFlow) { query, settings -> query.trim() to settings.assistants }
        .mapLatest { (query, assistants) ->
            if (query.isBlank()) return@mapLatest ImSearchResults()
            val partners = assistants.filter { it.name.contains(query, ignoreCase = true) }
            try {
                val results = conversations.searchMessages(query)
                val byConversation = results.map { it.conversationId }.distinct().associateWith { id ->
                    runCatching { Uuid.parse(id) }.getOrNull()?.let { conversations.getAssistantIdOf(it) }
                }
                ImSearchResults(query, partners, results.mapNotNull { result ->
                    assistants.find { it.id == byConversation[result.conversationId] }?.let { ImSearchHit(result, it) }
                })
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { ImSearchResults(query, partners, failed = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImSearchResults())
}
