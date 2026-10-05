package me.ayuilos.miffan.data.revision

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.repository.MemoryRepository
import me.ayuilos.miffan.data.thread.ThreadNotice
import me.ayuilos.miffan.data.thread.ThreadNoticeKind
import me.ayuilos.miffan.data.thread.ThreadNoticeSource
import kotlin.uuid.Uuid

sealed interface RestoreResult {
    data object Restored : RestoreResult

    /** The subject changed since the caller looked at it; nothing was restored. */
    data object Conflict : RestoreResult

    data object NotFound : RestoreResult
}

/** Restores earlier versions of assistants and memory sets; every restore is a new revision. */
class RevisionService(
    private val revisions: RevisionRepository,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
) {
    /** Restores [target]'s snapshot if the subject's head is still [expectedHeadId]. */
    suspend fun restore(target: Revision, expectedHeadId: String): RestoreResult {
        val head = revisions.head(target.subject, target.subjectId) ?: return RestoreResult.NotFound
        if (head.id != expectedHeadId) return RestoreResult.Conflict
        if (head.snapshot == target.snapshot) return RestoreResult.Restored
        val origin = RevisionOrigin(RevisionAuthor.RESTORE, revertOf = target.id)
        return withContext(origin) {
            when (target.subject) {
                RevisionSubject.ASSISTANT -> restoreAssistant(target)
                RevisionSubject.MEMORY -> {
                    memoryRepository.replaceAll(target.subjectId, MemoryRepository.restore(target.snapshot))
                    RestoreResult.Restored
                }
            }
        }
    }

    /** Reverts [revisionId] when it is still the newest change of its subject. */
    suspend fun undo(revisionId: String): RestoreResult {
        val revision = revisions.get(revisionId) ?: return RestoreResult.NotFound
        val parent = revision.parentId?.let { revisions.get(it) } ?: return RestoreResult.NotFound
        return restore(parent, expectedHeadId = revision.id)
    }

    private suspend fun restoreAssistant(target: Revision): RestoreResult {
        val restored = AssistantRevisionRecorder.restore(target.snapshot)
        var found = false
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map { current ->
                if (current.id.toString() != target.subjectId) return@map current
                found = true
                restored.keepingPermissionsOf(current)
            })
        }
        return if (found) RestoreResult.Restored else RestoreResult.NotFound
    }
}

/**
 * Workspace access is granted against the current binding; restoring an old version must not
 * silently bring back a revoked binding or Shell approval.
 */
internal fun Assistant.keepingPermissionsOf(current: Assistant): Assistant = copy(
    id = current.id,
    workspaceId = current.workspaceId,
    workspaceScopeId = current.workspaceScopeId,
    workspaceShellEnabled = current.workspaceShellEnabled,
    workspaceShellApprovalRequired = current.workspaceShellApprovalRequired,
    workspacePermissionRevision = current.workspacePermissionRevision,
    workspaceShellApprovalTarget = current.workspaceShellApprovalTarget,
)

/** Timeline notices for changes the assistant made itself (settings and memories). */
@OptIn(ExperimentalCoroutinesApi::class)
class RevisionNoticeSource(
    private val revisions: RevisionRepository,
    private val settingsStore: SettingsStore,
) : ThreadNoticeSource {
    override fun observe(assistantId: Uuid): Flow<List<ThreadNotice>> = settingsStore.settingsFlow
        .map { settings ->
            val assistant = settings.getAssistantById(assistantId)
            buildList {
                add(assistantId.toString())
                if (assistant?.useGlobalMemory == true) add(MemoryRepository.GLOBAL_MEMORY_ID)
            }
        }
        .distinctUntilChanged()
        .flatMapLatest { subjectIds -> revisions.agentRevisions(subjectIds) }
        .mapLatest { list ->
            val heads = list.map { it.subject to it.subjectId }.distinct()
                .associateWith { (subject, id) -> revisions.head(subject, id)?.id }
            list.mapNotNull { revision ->
                val trigger = revision.trigger ?: return@mapNotNull null
                ThreadNotice(
                    id = revision.id,
                    at = revision.createdAt,
                    kind = when (revision.subject) {
                        RevisionSubject.ASSISTANT -> ThreadNoticeKind.SETTINGS
                        RevisionSubject.MEMORY -> if (memoryCount(revision) < memoryCount(revision.parentId)) {
                            ThreadNoticeKind.MEMORY_FORGOTTEN
                        } else {
                            ThreadNoticeKind.MEMORY
                        }
                    },
                    summary = revision.summary,
                    trigger = trigger,
                    revisionId = revision.id,
                    undoable = revision.parentId != null && heads[revision.subject to revision.subjectId] == revision.id,
                )
            }
        }

    private suspend fun memoryCount(parentId: String?): Int =
        parentId?.let { revisions.get(it) }?.let { memoryCount(it) } ?: 0

    private fun memoryCount(revision: Revision): Int =
        runCatching { MemoryRepository.restore(revision.snapshot).size }.getOrDefault(0)
}
