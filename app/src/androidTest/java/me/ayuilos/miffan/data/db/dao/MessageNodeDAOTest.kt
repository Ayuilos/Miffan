package me.ayuilos.miffan.data.db.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageNodeDAOTest : ImDatabaseTestSupport() {
    @Test
    fun getRecentMessagesOrdersByNodeIndexDescendingAndHonorsLimit() = runBlocking {
        database.conversationDao().insert(conversation("thread"))
        database.conversationDao().insert(conversation("other"))
        val newest = node("newest", "thread", 40)
        val middle = node("middle", "thread", 20)
        val oldest = node("oldest", "thread", 5)
        val dao = database.messageNodeDao()
        dao.insertAll(listOf(middle, newest, node("other-message", "other", 100), oldest))

        val expected = listOf(newest.message, middle.message, oldest.message)
        assertEquals(expected.take(2), dao.getRecentMessages("thread", 2))
        assertEquals(expected.take(1), dao.getRecentMessages("thread", 1))
        assertEquals(expected, dao.getRecentMessages("thread", 10))
        assertEquals(emptyList<String>(), dao.getRecentMessages("thread", 0))
    }

    @Test
    fun getRecentMessagesReturnsEmptyForMissingOrEmptyConversation() = runBlocking {
        database.conversationDao().insert(conversation("empty"))

        assertEquals(emptyList<String>(), database.messageNodeDao().getRecentMessages("empty", 5))
        assertEquals(emptyList<String>(), database.messageNodeDao().getRecentMessages("missing", 5))
    }
}
