package me.ayuilos.miffan.data.db.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.db.entity.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryDAOTest : ImDatabaseTestSupport() {
    @Test
    fun insertAllReplacesExistingIdIncludingOwnerAndSourceFields() = runBlocking {
        val dao = database.memoryDao()
        val original = MemoryEntity(7, "old-assistant", "Old content", 10, "old-thread", "old-message")
        val untouched = MemoryEntity(8, "old-assistant", "Untouched", 20, "other-thread", "other-message")
        dao.insertAll(listOf(original, untouched))
        val replacement = original.copy(
            assistantId = "new-assistant",
            content = "Replacement content",
            createdAt = 30,
            sourceConversationId = "new-thread",
            sourceMessageId = "new-message",
        )
        val added = MemoryEntity(9, "new-assistant", "New content", 40, "added-thread", "added-message")

        dao.insertAll(listOf(replacement, added))

        assertEquals(replacement, dao.getMemoryById(7))
        assertEquals(untouched, dao.getMemoryById(8))
        assertEquals(added, dao.getMemoryById(9))
        assertEquals(3, dao.getAllMemories().size)
        assertEquals(listOf(untouched), dao.getMemoriesOfAssistant("old-assistant"))
    }

    @Test
    fun insertAllRoundTripsNewColumnsAndDefaults() = runBlocking {
        val dao = database.memoryDao()
        val sourced = MemoryEntity(1, "assistant", "Sourced", 123456789L, "thread", "message")
        val defaults = MemoryEntity(2, "assistant", "Without source")

        dao.insertAll(listOf(sourced, defaults))

        assertEquals(sourced, dao.getMemoryById(1))
        assertEquals(defaults, dao.getMemoryById(2))
        assertEquals(setOf(sourced, defaults), dao.getMemoriesOfAssistant("assistant").toSet())
    }
}
