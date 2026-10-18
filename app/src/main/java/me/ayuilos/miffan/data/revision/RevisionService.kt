package me.ayuilos.miffan.data.revision

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
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

sealed interface ToolCallChangeState {
    data object Undoable : ToolCallChangeState
    data object Undone : ToolCallChangeState
    data object Superseded : ToolCallChangeState
}

data class ToolCallChange(
    val revisions: List<Revision>,
    val state: ToolCallChangeState,
    /** The assistant before this call, when the call changed an assistant. */
    val assistantBefore: Assistant?,
)

/** Restores earlier versions of assistants and memory sets; every restore is a new revision. */
class RevisionService(
    private val revisions: RevisionRepository,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
) {
    private val restoreMutex = Mutex()

    /**
     * Observes reversible changes and their current subject heads. Creations without a parent
     * are excluded: undo cannot delete subjects. Multiple writes to a subject form one change.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeToolCallChange(toolCallId: String): Flow<ToolCallChange?> = revisions.byToolCallId(toolCallId)
        .distinctUntilChanged()
        .flatMapLatest { changes ->
            val groups = changes.filter { it.author != RevisionAuthor.RESTORE }
                .groupBy { it.subject to it.subjectId }.values.filter { it.first().parentId != null }
            if (groups.isEmpty()) flowOf(null) else combine(groups.map { group ->
                revisions.history(group.first().subject, group.first().subjectId)
            }) { histories ->
                val states = groups.zip(histories).map { (group, history) -> callState(group, history) }
                val state = when {
                    states.all { it == ToolCallChangeState.Undoable } -> ToolCallChangeState.Undoable
                    states.all { it == ToolCallChangeState.Undone } -> ToolCallChangeState.Undone
                    else -> ToolCallChangeState.Superseded
                }
                val before = groups.zip(histories).firstOrNull { (group, _) ->
                    group.first().subject == RevisionSubject.ASSISTANT
                }?.let { (group, history) ->
                    history.find { it.id == group.first().parentId }?.let { parent ->
                        runCatching { AssistantRevisionRecorder.restore(parent.snapshot) }.getOrNull()
                    }
                }
                ToolCallChange(groups.flatten(), state, before)
            }
        }

    /** Reverts every reversible subject changed by the call, after checking all subject heads. */
    suspend fun undoToolCall(toolCallId: String): RestoreResult = restoreToolCall(toolCallId, redo = false)

    /** Reapplies the call's final snapshots only while its undo revisions are still the heads. */
    suspend fun redoToolCall(toolCallId: String): RestoreResult = restoreToolCall(toolCallId, redo = true)

    private suspend fun restoreToolCall(toolCallId: String, redo: Boolean): RestoreResult = restoreMutex.withLock {
        val change = observeToolCallChange(toolCallId).first() ?: return@withLock RestoreResult.NotFound
        val expected = if (redo) ToolCallChangeState.Undone else ToolCallChangeState.Undoable
        if (change.state != expected) return@withLock RestoreResult.Conflict
        val groups = change.revisions.groupBy { it.subject to it.subjectId }.values
        val targets = groups.map { group ->
            val target = if (redo) group.last() else revisions.get(requireNotNull(group.first().parentId))
            target ?: return@withLock RestoreResult.NotFound
        }
        val heads = targets.map { target ->
            revisions.head(target.subject, target.subjectId) ?: return@withLock RestoreResult.NotFound
        }
        // Validate all subjects before changing any of them, including deleted assistants.
        val settings = if (targets.any { it.subject == RevisionSubject.ASSISTANT }) settingsStore.settingsFlow.first() else null
        targets.forEach { target ->
            when (target.subject) {
                RevisionSubject.ASSISTANT -> {
                    if (settings?.assistants.orEmpty().none { it.id.toString() == target.subjectId }) return@withLock RestoreResult.NotFound
                    AssistantRevisionRecorder.restore(target.snapshot)
                }
                RevisionSubject.MEMORY -> MemoryRepository.restore(target.snapshot)
            }
        }
        if (observeToolCallChange(toolCallId).first()?.state != expected) return@withLock RestoreResult.Conflict
        targets.zip(heads).forEach { (target, head) ->
            val result = restoreUnlocked(target, head.id, recordNoOp = true)
            if (result != RestoreResult.Restored) return@withLock result
        }
        RestoreResult.Restored
    }

    private fun callState(group: List<Revision>, history: List<Revision>): ToolCallChangeState {
        if (group.zipWithNext().any { (previous, next) -> next.parentId != previous.id }) {
            return ToolCallChangeState.Superseded
        }
        val byId = history.associateBy { it.id }
        var head = history.firstOrNull() ?: return ToolCallChangeState.Superseded
        val undone = head.author == RevisionAuthor.RESTORE && head.revertOf == group.first().parentId
        var expectUndo = undone
        // Follow alternating undo/redo restores back to this call, rather than accepting an
        // unrelated later restore of the same snapshot. Parent links also handle equal timestamps.
        while (head.id != group.last().id) {
            val target = if (expectUndo) group.first().parentId else group.last().id
            if (head.author != RevisionAuthor.RESTORE || head.revertOf != target) return ToolCallChangeState.Superseded
            head = byId[head.parentId] ?: return ToolCallChangeState.Superseded
            expectUndo = !expectUndo
        }
        if (expectUndo) return ToolCallChangeState.Superseded
        return if (undone) ToolCallChangeState.Undone else ToolCallChangeState.Undoable
    }

    /** Preview for approval UI: newest own historical avatar that differs from the current one. */
    suspend fun previousAvatar(assistantId: Uuid): Avatar? {
        val current = settingsStore.settingsFlow.first().getAssistantById(assistantId) ?: return null
        return avatarHistory(assistantId).firstOrNull { it != current.avatar }
    }

    /** Scoped query only; never inspect other assistants or memory revisions. Newest first. */
    internal suspend fun avatarHistory(assistantId: Uuid): List<Avatar> = revisions
        .history(RevisionSubject.ASSISTANT, assistantId.toString()).first()
        .filter { it.subject == RevisionSubject.ASSISTANT && it.subjectId == assistantId.toString() }
        .mapNotNull { revision ->
            runCatching { AssistantRevisionRecorder.restore(revision.snapshot) }.getOrNull()
                ?.takeIf { it.id == assistantId }?.avatar
        }

    /** Restores [target]'s snapshot if the subject's head is still [expectedHeadId]. */
    suspend fun restore(target: Revision, expectedHeadId: String): RestoreResult = restoreMutex.withLock {
        restoreUnlocked(target, expectedHeadId)
    }

    private suspend fun restoreUnlocked(target: Revision, expectedHeadId: String, recordNoOp: Boolean = false): RestoreResult {
        val head = revisions.head(target.subject, target.subjectId) ?: return RestoreResult.NotFound
        if (head.id != expectedHeadId) return RestoreResult.Conflict
        if (head.snapshot == target.snapshot && !recordNoOp) return RestoreResult.Restored
        val origin = RevisionOrigin(RevisionAuthor.RESTORE, revertOf = target.id)
        return withContext(origin) {
            val result = when (target.subject) {
                RevisionSubject.ASSISTANT -> restoreAssistant(target)
                RevisionSubject.MEMORY -> {
                    memoryRepository.replaceAll(target.subjectId, MemoryRepository.restore(target.snapshot))
                    RestoreResult.Restored
                }
            }
            // Permission preservation can make an assistant restore a no-op. Retain an explicit
            // restore revision so the call still has an observable undo/redo state.
            if (recordNoOp && result == RestoreResult.Restored && revisions.head(target.subject, target.subjectId)?.id == head.id) {
                val snapshot = when (target.subject) {
                    RevisionSubject.ASSISTANT -> settingsStore.settingsFlow.first()
                        .assistants.find { it.id.toString() == target.subjectId }
                        ?.let { AssistantRevisionRecorder.snapshot(it) } ?: return@withContext RestoreResult.NotFound
                    RevisionSubject.MEMORY -> target.snapshot
                }
                revisions.record(target.subject, target.subjectId, before = head.snapshot, after = snapshot)
            }
            result
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
    workspaceShellApprovalVia = current.workspaceShellApprovalVia,
    standingToolApprovals = current.standingToolApprovals,
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
