package me.ayuilos.miffan.data.db.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.db.entity.RevisionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RevisionDAOTest : ImDatabaseTestSupport() {
    @Test
    fun getHeadUsesCreatedAtBeforeInsertionOrderAndFiltersSubject() = runBlocking {
        val dao = database.revisionDao()
        val newest = revision("newest", 30)
        dao.insert(newest)
        dao.insert(revision("older-inserted-last", 10))
        dao.insert(revision("other-subject", 100, subjectId = "other"))
        dao.insert(revision("other-type", 200, subjectType = "memory"))

        assertEquals(newest, dao.getHead("assistant", "subject"))
        assertNull(dao.getHead("assistant", "missing"))
    }

    @Test
    fun getHeadBreaksCreatedAtTiesByLatestRowid() = runBlocking {
        val dao = database.revisionDao()
        dao.insert(revision("z-first", 30))
        val lastAtSameTime = revision("a-second", 30)
        dao.insert(lastAtSameTime)
        dao.insert(revision("older-inserted-last", 10))

        assertEquals(lastAtSameTime, dao.getHead("assistant", "subject"))
    }

    @Test
    fun observeHistoryReturnsNewestFirstWithRowidTieBreakAndSubjectIsolation() = runBlocking {
        val dao = database.revisionDao()
        val older = revision("older", 10)
        val firstAtSameTime = revision("z-first", 30)
        val lastAtSameTime = revision("a-second", 30)
        listOf(firstAtSameTime, lastAtSameTime, older,
            revision("other-subject", 100, subjectId = "other"),
            revision("other-type", 200, subjectType = "memory"),
        ).forEach { dao.insert(it) }

        assertEquals(
            listOf(lastAtSameTime, firstAtSameTime, older),
            dao.observeHistory("assistant", "subject").awaitValue(),
        )
        assertEquals(emptyList<RevisionEntity>(), dao.observeHistory("assistant", "missing").awaitValue())
    }

    @Test
    fun observeByAuthorIncludesOnlyRequestedAuthorAndSubjectIds() = runBlocking {
        val dao = database.revisionDao()
        val first = revision("first", 10, author = "agent")
        val second = revision("second", 20, author = "agent", subjectId = "second-subject")
        listOf(second, first,
            revision("wrong-author", 15, author = "user"),
            revision("wrong-subject", 15, author = "agent", subjectId = "other"),
        ).forEach { dao.insert(it) }

        assertEquals(
            listOf(first, second),
            dao.observeByAuthor("agent", listOf("subject", "second-subject")).awaitValue(),
        )
        assertEquals(emptyList<RevisionEntity>(), dao.observeByAuthor("agent", emptyList()).awaitValue())
    }

    private fun revision(
        id: String,
        createdAt: Long,
        subjectType: String = "assistant",
        subjectId: String = "subject",
        author: String = "user",
    ) = RevisionEntity(
        id = id,
        subjectType = subjectType,
        subjectId = subjectId,
        parentId = "",
        author = author,
        summary = "Summary $id",
        snapshot = "{}",
        triggerConversationId = "",
        triggerMessageId = "",
        triggerToolCallId = "",
        revertOf = "",
        createdAt = createdAt,
    )
}
