package me.ayuilos.miffan.ui.im

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.data.revision.*
import kotlin.uuid.Uuid

class ImRevisionVM(
    subject: RevisionSubject,
    subjectId: String,
    revisions: RevisionRepository,
    private val service: RevisionService,
    private val conversations: ConversationRepository,
) : ViewModel() {
    val history = revisions.history(subject, subjectId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    suspend fun restore(target: Revision, expectedHead: String) = service.restore(target, expectedHead)
    suspend fun sourceAssistantId(conversationId: Uuid) = conversations.getAssistantIdOf(conversationId)?.toString()
}
