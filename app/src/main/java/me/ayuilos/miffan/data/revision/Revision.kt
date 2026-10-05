package me.ayuilos.miffan.data.revision

import kotlinx.serialization.Serializable
import me.ayuilos.miffan.data.db.entity.RevisionEntity
import me.ayuilos.miffan.data.model.MessageRef
import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

enum class RevisionSubject { ASSISTANT, MEMORY }

enum class RevisionAuthor {
    /** The user edited it in the interface (also the default when no origin is known). */
    USER,

    /** The assistant changed it with a tool, triggered by a user message. */
    AGENT,

    /** A previous version was restored. */
    RESTORE,

    /** The state before the first recorded change. */
    BASELINE,

    /** A backup was restored. */
    BACKUP,
}

/**
 * Describes who is changing an assistant or memory set. Callers install it in the coroutine
 * context around the change; the stores read it when they record the new revision.
 */
class RevisionOrigin(
    val author: RevisionAuthor,
    val trigger: MessageRef? = null,
    val toolCallId: String? = null,
    val summary: String? = null,
    val revertOf: String? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RevisionOrigin>
}

data class Revision(
    val id: String,
    val subject: RevisionSubject,
    val subjectId: String,
    val parentId: String?,
    val author: RevisionAuthor,
    val summary: String,
    val snapshot: String,
    val trigger: MessageRef?,
    val toolCallId: String?,
    val revertOf: String?,
    val createdAt: Instant,
)

/** One memory as stored in a memory-set snapshot. */
@Serializable
data class MemorySnapshotItem(
    val id: Int,
    val content: String,
    val createdAt: Long = 0,
    val sourceConversationId: String = "",
    val sourceMessageId: String = "",
)

internal fun RevisionEntity.toRevision() = Revision(
    id = id,
    subject = RevisionSubject.valueOf(subjectType),
    subjectId = subjectId,
    parentId = parentId.ifEmpty { null },
    author = RevisionAuthor.valueOf(author),
    summary = summary,
    snapshot = snapshot,
    trigger = if (triggerConversationId.isNotEmpty() && triggerMessageId.isNotEmpty()) {
        MessageRef(Uuid.parse(triggerConversationId), Uuid.parse(triggerMessageId))
    } else {
        null
    },
    toolCallId = triggerToolCallId.ifEmpty { null },
    revertOf = revertOf.ifEmpty { null },
    createdAt = Instant.ofEpochMilli(createdAt),
)

internal fun Revision.toEntity() = RevisionEntity(
    id = id,
    subjectType = subject.name,
    subjectId = subjectId,
    parentId = parentId.orEmpty(),
    author = author.name,
    summary = summary,
    snapshot = snapshot,
    triggerConversationId = trigger?.conversationId?.toString().orEmpty(),
    triggerMessageId = trigger?.messageId?.toString().orEmpty(),
    triggerToolCallId = toolCallId.orEmpty(),
    revertOf = revertOf.orEmpty(),
    createdAt = createdAt.toEpochMilli(),
)
