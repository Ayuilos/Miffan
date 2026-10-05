package me.ayuilos.miffan.data.db.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationDAOTest : ImDatabaseTestSupport() {
    @Test
    fun observeLatestConversationOfEachAssistantSelectsMaximumUpdateAt() = runBlocking {
        val dao = database.conversationDao()
        val latestA = conversation("a-latest", "a", 40)
        val latestB = conversation("b-latest", "b", 30)
        val onlyC = conversation("c-only", "c", 20)
        listOf(latestB, latestA, onlyC,
            conversation("a-old-pinned", "a", 10, isPinned = true),
            conversation("b-old", "b", 5),
        ).forEach { dao.insert(it) }

        val result = dao.observeLatestConversationOfEachAssistant().awaitValue()
        assertEquals(listOf("a", "b", "c"), result.map { it.assistantId })
        assertEquals(listOf(latestA.id, latestB.id, onlyC.id), result.map { it.id })
        assertEquals(listOf(40L, 30L, 20L), result.map { it.updateAt })
        assertEquals(listOf(latestA.title, latestB.title, onlyC.title), result.map { it.title })
        assertEquals(listOf(latestA.folderId, latestB.folderId, onlyC.folderId), result.map { it.folderId })
        assertEquals(listOf(false, false, false), result.map { it.isPinned })
    }

    @Test
    fun observeThreadSegmentStampsReemitsWhenOnlyNodeRevisionChanges() = runBlocking {
        val dao = database.conversationDao()
        val segment = conversation("segment", updateAt = 20, summary = "Summary").copy(
            threadClosedAt = 30,
            selectedRootId = "first",
        )
        dao.insert(segment)
        dao.insert(conversation("older", updateAt = 10))
        dao.insert(conversation("other-assistant", "other", 100))
        val firstNode = node("first", segment.id, 0, revision = 2)
        database.messageNodeDao().insertAll(listOf(firstNode, node("second", segment.id, 1, revision = 3)))
        val emissions = Channel<List<ThreadSegmentStamp>>(Channel.UNLIMITED)
        val collector = launch {
            dao.observeThreadSegmentStamps("assistant", 1).collect { emissions.send(it) }
        }
        try {
            withTimeout(5_000) {
                val initial = emissions.receive()
                assertEquals(
                    listOf(ThreadSegmentStamp(segment.id, segment.title, "Summary", 30, "first", 2, 5)),
                    initial,
                )

                database.messageNodeDao().update(firstNode.copy(revision = 9))

                val updated = emissions.receive()
                assertEquals(listOf(initial.single().copy(revisionSum = 12)), updated)
                assertEquals(segment, dao.getConversationById(segment.id))
            }
        } finally {
            collector.cancelAndJoin()
            emissions.close()
        }
    }

    @Test
    fun getThreadDigestsExcludesCurrentAndEmptySummariesAndOrdersByUpdateAt() = runBlocking {
        val dao = database.conversationDao()
        val older = conversation("older", updateAt = 10, summary = "Older summary")
        val newest = conversation("newest", updateAt = 30, summary = "Newest summary")
        val middle = conversation("middle", updateAt = 20, summary = "Middle summary")
        listOf(newest, older, middle,
            conversation("excluded", updateAt = 100, summary = "Current summary"),
            conversation("empty", updateAt = 90),
            conversation("other", "other-assistant", 80, summary = "Other summary"),
        ).forEach { dao.insert(it) }

        val expected = listOf(newest, middle, older).map {
            ThreadSegmentDigest(it.id, it.title, it.threadSummary, it.updateAt)
        }
        assertEquals(expected, dao.getThreadDigests("assistant", "excluded", 10))
        assertEquals(expected.take(2), dao.getThreadDigests("assistant", "excluded", 2))
        assertEquals(emptyList<ThreadSegmentDigest>(), dao.getThreadDigests("assistant", "excluded", 0))
    }

    @Test
    fun getAssistantIdOfReturnsOwnerOrNullForMissingConversation() = runBlocking {
        val dao = database.conversationDao()
        dao.insert(conversation("first", "assistant-a"))
        dao.insert(conversation("second", "assistant-b"))

        assertEquals("assistant-a", dao.getAssistantIdOf("first"))
        assertEquals("assistant-b", dao.getAssistantIdOf("second"))
        assertNull(dao.getAssistantIdOf("missing"))
    }
}
