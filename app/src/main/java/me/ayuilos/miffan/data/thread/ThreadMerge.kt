package me.ayuilos.miffan.data.thread

import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.model.MessageRef
import me.rerere.ai.core.MessageRole
import kotlin.uuid.Uuid

/** A process-local undo handle; snapshots are held by ThreadService, never by the UI. */
data class UndoableMerge(
    val assistantId: Uuid,
    val sourceId: Uuid,
    val targetId: Uuid,
    val targetTitle: String?,
    val anchor: MessageRef?,
)

sealed interface MergeResult {
    data class Success(val merge: UndoableMerge) : MergeResult
    data class Failure(val reason: String) : MergeResult
}

internal data class SegmentMergePlan(
    val merged: Conversation,
    val regenerate: MessageNode?,
    val continuationIds: List<Uuid>,
)

/** Interleave selected paths while retaining the target's unselected branches and node ids. */
internal fun planSegmentMerge(source: Conversation, target: Conversation): SegmentMergePlan {
    val sourcePath = source.currentMessageNodes
    val selected = (target.currentMessageNodes + sourcePath).sortedBy { it.message.createdAt }
    val links = selected.mapIndexed { index, node ->
        node.copy(
            parentId = selected.getOrNull(index - 1)?.id,
            selectedChildId = selected.getOrNull(index + 1)?.id,
            replyTo = node.replyTo?.let { ref ->
                if (ref.conversationId == source.id) ref.copy(conversationId = target.id) else ref
            },
            revision = node.revision + 1,
        )
    }.associateBy { it.id }
    val sourceIds = sourcePath.mapTo(HashSet()) { it.id }
    val owner = sourcePath.singleOrNull { it.role == MessageRole.USER && it.origin == null }
    val singleTurn = owner?.takeIf {
        sourcePath.count { it.role == MessageRole.USER } == 1 &&
            sourcePath.any { it.role == MessageRole.ASSISTANT }
    }
    val continuation = singleTurn?.let { trigger ->
        selected.dropWhile { it.id != trigger.id }.drop(1).filterNot { it.id in sourceIds }.map { it.id }
    }.orEmpty()
    return SegmentMergePlan(
        merged = target.copy(
            messageNodes = target.messageNodes.map { links[it.id] ?: it } + sourcePath.map { links.getValue(it.id) },
            selectedRootId = selected.firstOrNull()?.id,
            threadClosedAt = 0,
            threadSummary = "",
            updateAt = maxOf(source.updateAt, target.updateAt),
        ),
        regenerate = singleTurn,
        continuationIds = continuation,
    )
}

internal fun Conversation.ownerMessageIds(): Set<Uuid> = messageNodes
    .filter { it.role == MessageRole.USER && it.origin == null }.mapTo(HashSet()) { it.message.id }

/** Append later interleaved target messages after a regenerated reply without losing its siblings. */
internal fun Conversation.attachMergeContinuation(
    ids: List<Uuid>,
    ownerId: Uuid? = null,
    retainedReplyIds: List<Uuid> = emptyList(),
): Conversation {
    val continuation = ids.mapNotNull(::getMessageNode)
    if (continuation.isEmpty()) return this
    val idsToMove = ids.toSet()
    val prefix = currentMessageNodes.takeWhile { it.id !in idsToMove }
    val tail = prefix.lastOrNull() ?: return this
    // A failed turn before the first reply chunk must keep the original interleaved path.
    if (ownerId != null && (tail.id == ownerId || tail.id in retainedReplyIds)) return this
    val chain = listOf(tail) + continuation
    val replacements = chain.mapIndexed { index, node ->
        node.copy(
            parentId = if (index == 0) node.parentId else chain[index - 1].id,
            selectedChildId = continuation.getOrNull(index)?.id,
            revision = node.revision + 1,
        )
    }.associateBy { it.id }
    val retained = retainedReplyIds.mapNotNull(::getMessageNode)
    val oldBranch = retained.mapIndexed { index, node ->
        node.copy(
            parentId = if (index == 0) ownerId else retained[index - 1].id,
            selectedChildId = retained.getOrNull(index + 1)?.id,
            revision = node.revision + 1,
        )
    }.associateBy { it.id }
    return copy(messageNodes = messageNodes.map { oldBranch[it.id] ?: replacements[it.id] ?: it })
}
