package me.ayuilos.miffan.data.db.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.data.db.entity.ConversationEntity
import me.ayuilos.miffan.data.db.entity.MessageNodeEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageNodeStatsTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: MessageNodeDAO
    private var nodeIndex = 0

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        dao = database.messageNodeDao()
        database.conversationDao().insert(
            ConversationEntity(
                id = "stats-test",
                assistantId = "assistant",
                title = "Stats test",
                nodes = "[]",
                createAt = 0,
                updateAt = 0,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun malformedRowsAreSkippedWhileValidMessagesAreAggregated() = runBlocking {
        insertMessages("not json")
        insertMessages("{broken")
        insertMessages("""{"role":"user","createdAt":"2026-09-14T09:00:00"}""")
        insertMessages("""{"role":"assistant","createdAt":"2026-09-14T09:01:00","usage":{"promptTokens":100,"completionTokens":40,"cachedTokens":20}}""")
        insertMessages("""{"role":"user","createdAt":"2026-09-13T09:00:00"}""")
        assertEquals(MessageTokenStats(3, 100, 40, 20), dao.getTokenStats())
        assertEquals(listOf(MessageDayCount("2026-09-14", 1)), dao.getMessageCountPerDay("2026-09-14"))
        assertEquals(5, dao.getNodesOfConversation("stats-test").size)
    }

    @Test
    fun onlyMalformedRowsProduceEmptyStats() = runBlocking {
        insertMessages("")
        insertMessages("[")
        insertMessages("not json")
        assertEquals(MessageTokenStats(), dao.getTokenStats())
        assertEquals(emptyList<MessageDayCount>(), dao.getMessageCountPerDay("2026-09-14"))
    }

    @Test
    fun emptyDatabaseProducesEmptyStats() = runBlocking {
        assertEquals(MessageTokenStats(), dao.getTokenStats())
        assertEquals(emptyList<MessageDayCount>(), dao.getMessageCountPerDay("2026-09-14"))
    }

    private suspend fun insertMessages(messages: String) {
        val index = nodeIndex++
        dao.insert(
            MessageNodeEntity(
                id = "node-$index",
                conversationId = "stats-test",
                nodeIndex = index,
                message = messages,
                parentId = "",
                selectedChildId = "",
                revision = 0,
            )
        )
    }
}
