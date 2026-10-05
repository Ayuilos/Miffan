package me.ayuilos.miffan.data.revision

import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.data.db.dao.RevisionDAO
import java.time.Instant
import kotlin.uuid.Uuid

/** Persistence used by [RevisionRepository]; a fake implements it in JVM tests. */
interface RevisionStore {
    suspend fun head(subject: RevisionSubject, subjectId: String): Revision?
    suspend fun get(id: String): Revision?
    suspend fun insert(revision: Revision)
    suspend fun <T> transaction(block: suspend () -> T): T
    fun history(subject: RevisionSubject, subjectId: String): Flow<List<Revision>>
    fun byAuthor(author: RevisionAuthor, subjectIds: List<String>): Flow<List<Revision>>
}

class RoomRevisionStore(private val database: AppDatabase, private val dao: RevisionDAO) : RevisionStore {
    override suspend fun head(subject: RevisionSubject, subjectId: String) = dao.getHead(subject.name, subjectId)?.toRevision()
    override suspend fun get(id: String) = dao.getById(id)?.toRevision()
    override suspend fun insert(revision: Revision) = dao.insert(revision.toEntity())
    override suspend fun <T> transaction(block: suspend () -> T): T = database.withTransaction { block() }
    override fun history(subject: RevisionSubject, subjectId: String) =
        dao.observeHistory(subject.name, subjectId).map { list -> list.map { it.toRevision() } }
    override fun byAuthor(author: RevisionAuthor, subjectIds: List<String>) =
        dao.observeByAuthor(author.name, subjectIds).map { list -> list.map { it.toRevision() } }
}

/**
 * Linear, append-only version history per subject. Every change becomes a new revision whose
 * parent is the previous head; restoring never rewrites history.
 */
class RevisionRepository(
    private val store: RevisionStore,
    private val clock: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()

    /**
     * Records [after] as the new head of the subject. When the subject has no history yet, [before]
     * is stored first as its baseline. Returns null when nothing changed. The author and trigger
     * come from the [RevisionOrigin] in the caller's coroutine context (default: the user).
     */
    suspend fun record(
        subject: RevisionSubject,
        subjectId: String,
        before: String?,
        after: String,
        defaultSummary: String = "",
    ): Revision? {
        val origin = currentCoroutineContext()[RevisionOrigin] ?: RevisionOrigin(RevisionAuthor.USER)
        return mutex.withLock {
            store.transaction {
                var head = store.head(subject, subjectId)
                if (head?.snapshot == after) return@transaction null
                if (head == null && before != null && before != after) {
                    head = newRevision(subject, subjectId, null, RevisionOrigin(RevisionAuthor.BASELINE), before, "")
                        .also { store.insert(it) }
                }
                newRevision(subject, subjectId, head?.id, origin, after, origin.summary ?: defaultSummary)
                    .also { store.insert(it) }
            }
        }
    }

    suspend fun head(subject: RevisionSubject, subjectId: String): Revision? = store.head(subject, subjectId)

    suspend fun get(id: String): Revision? = store.get(id)

    /** Newest first. */
    fun history(subject: RevisionSubject, subjectId: String): Flow<List<Revision>> = store.history(subject, subjectId)

    /** Oldest first. */
    fun agentRevisions(subjectIds: List<String>): Flow<List<Revision>> = store.byAuthor(RevisionAuthor.AGENT, subjectIds)

    private fun newRevision(
        subject: RevisionSubject,
        subjectId: String,
        parentId: String?,
        origin: RevisionOrigin,
        snapshot: String,
        summary: String,
    ) = Revision(
        id = Uuid.random().toString(),
        subject = subject,
        subjectId = subjectId,
        parentId = parentId,
        author = origin.author,
        summary = summary,
        snapshot = snapshot,
        trigger = origin.trigger,
        toolCallId = origin.toolCallId,
        revertOf = origin.revertOf,
        createdAt = clock(),
    )
}
