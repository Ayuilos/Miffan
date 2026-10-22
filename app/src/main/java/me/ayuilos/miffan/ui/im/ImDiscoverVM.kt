package me.ayuilos.miffan.ui.im

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.favorite.NodeFavoriteAdapter
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.FavoriteType
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.data.repository.FavoriteRepository
import me.ayuilos.miffan.data.repository.GenMediaRepository
import me.ayuilos.miffan.ui.pages.imggen.GeneratedImage
import me.ayuilos.miffan.ui.pages.imggen.toGeneratedImage
import kotlin.uuid.Uuid

/** A saved message as easy chat lists it: who said it, what it said, and where to find it again. */
data class ImFavorite(
    val refKey: String,
    val conversationId: Uuid,
    val nodeId: Uuid,
    val preview: String,
    val createdAt: Long,
    /** The partner whose chat it came from; null once that partner is gone. */
    val partner: Assistant?,
)

/** What the Discover tab and its favorites page show: saved messages and the latest drawings. */
@OptIn(ExperimentalCoroutinesApi::class)
class ImDiscoverVM(
    settingsStore: SettingsStore,
    private val favoriteRepository: FavoriteRepository,
    genMediaRepository: GenMediaRepository,
    conversations: ConversationRepository,
    filesManager: FilesManager,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    val savedMessages: StateFlow<List<ImFavorite>?> = combine(
        favoriteRepository.listByType(FavoriteType.NODE), settingsStore.settingsFlow.map { it.assistants },
    ) { entities, assistants -> entities to assistants }
        .mapLatest { (entities, assistants) ->
            val byId = assistants.associateBy { it.id }
            val owners = HashMap<Uuid, Uuid?>()
            entities.mapNotNull { entity ->
                val ref = NodeFavoriteAdapter.decodeRef(entity) ?: return@mapNotNull null
                val owner = owners.getOrPut(ref.conversationId) { conversations.getAssistantIdOf(ref.conversationId) }
                ImFavorite(
                    refKey = entity.refKey,
                    conversationId = ref.conversationId,
                    nodeId = ref.nodeId,
                    preview = NodeFavoriteAdapter.decodeMeta(entity)?.previewText.orEmpty(),
                    createdAt = entity.createdAt,
                    partner = owner?.let { byId[it] },
                )
            }.sortedByDescending { it.createdAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recentDrawings: StateFlow<List<GeneratedImage>> = genMediaRepository.recentMedia(RECENT_DRAWINGS)
        .map { media -> media.map { it.toGeneratedImage(filesManager) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun removeFavorite(refKey: String) = favoriteRepository.deleteByRefKey(refKey)

    private companion object {
        const val RECENT_DRAWINGS = 8
    }
}
