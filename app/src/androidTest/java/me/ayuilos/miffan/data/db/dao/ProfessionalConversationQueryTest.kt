package me.ayuilos.miffan.data.db.dao

import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import me.ayuilos.miffan.data.db.entity.FavoriteEntity

/** All fixtures live in an isolated in-memory database, never the installed user's database. */
@RunWith(AndroidJUnit4::class)
class ProfessionalConversationQueryTest : ImDatabaseTestSupport() {
    private suspend fun <T : Any> page(source: PagingSource<Int, T>): List<T> =
        (source.load(PagingSource.LoadParams.Refresh(null, 20, false)) as PagingSource.LoadResult.Page).data

    @Test fun everyDisplayedListAndPageExcludesPerspectivesBeforeLimit() = runBlocking {
        val dao = database.conversationDao()
        val ordinary = conversation("ordinary", updateAt = 20, isPinned = true).copy(folderId = "folder", title = "match")
        val unfiled = conversation("unfiled", updateAt = 10).copy(folderId = "", title = "match")
        val other = conversation("other", "other-assistant", 5).copy(title = "match")
        val private = ordinary.copy(id = "private", chatId = "direct", updateAt = 100)
        val group = unfiled.copy(id = "group", chatId = "group-chat", updateAt = 200, isPinned = true)
        listOf(ordinary, unfiled, other, private, group).forEach { dao.insert(it) }
        val ownIds = listOf("ordinary", "unfiled")
        assertEquals(ownIds, dao.getConversationsOfAssistant("assistant").awaitValue().map { it.id })
        assertEquals(ownIds, page(dao.getConversationsOfAssistantPaging("assistant")).map { it.id })
        assertEquals(listOf("unfiled"), page(dao.getUnfiledConversationsOfAssistantPaging("assistant")).map { it.id })
        assertEquals(listOf("ordinary"), page(dao.getConversationsOfFolderPaging("folder")).map { it.id })
        assertEquals(ownIds + "other", page(dao.getAllPaging()).map { it.id })
        assertEquals(ownIds + "other", dao.searchConversations("match").awaitValue().map { it.id })
        assertEquals(ownIds + "other", page(dao.searchConversationsPaging("match")).map { it.id })
        assertEquals(ownIds, dao.searchConversationsOfAssistant("assistant", "match").awaitValue().map { it.id })
        assertEquals(ownIds, page(dao.searchConversationsOfAssistantPaging("assistant", "match")).map { it.id })
        assertEquals(listOf("ordinary"), dao.observeRecentConversations(1).awaitValue().map { it.id })
        assertEquals(listOf("ordinary"), dao.getRecentConversationsOfAssistant("assistant", 1).map { it.id })
        assertEquals(listOf("ordinary"), dao.getPinnedConversations().awaitValue().map { it.id })
        assertEquals(3, dao.countVisible())
        assertEquals(3, dao.getConversationCountPerDay(0).sumOf { it.count })
        // Backup/export, index rebuild, id lookup, assistant deletion and partner runtime stay raw.
        assertEquals(5, dao.countAll())
        assertEquals(5, dao.getAll().awaitValue().size)
        assertEquals(5, dao.getAllIds().size)
        assertEquals(4, dao.getAllConversationsOfAssistant("assistant").size)
        assertEquals(private, dao.getConversationById("private"))
        assertEquals(private, dao.getConversationFlowById("private").awaitValue())
        assertEquals(listOf("private"), dao.getPartnerSegmentIds("direct"))
    }

    @Test fun professionalMessageStatsExcludePerspectivesWhileRawStatsKeepThem() = runBlocking {
        val dao = database.conversationDao()
        dao.insert(conversation("ordinary"))
        dao.insert(conversation("private").copy(chatId = "chat"))
        val json = """{"role":"user","createdAt":"2026-10-12T12:00:00","usage":{"promptTokens":4,"completionTokens":2,"cachedTokens":1}}"""
        val messages = database.messageNodeDao()
        messages.insert(node("one", "ordinary", 0).copy(message = json))
        messages.insert(node("two", "private", 0).copy(message = json))
        assertEquals(MessageTokenStats(1, 4, 2, 1), messages.getTokenStats(excludePerspectives = true))
        assertEquals(MessageTokenStats(2, 8, 4, 2), messages.getTokenStats())
        assertEquals(1, messages.getMessageCountPerDay("2026-01-01", excludePerspectives = true).single().count)
        assertEquals(2, messages.getMessageCountPerDay("2026-01-01").single().count)
    }

    @Test fun professionalFavoritesHidePerspectivesWithoutDeletingSavedEntries() = runBlocking {
        database.conversationDao().insert(conversation("ordinary"))
        database.conversationDao().insert(conversation("private").copy(chatId = "chat"))
        val favorites = database.favoriteDao()
        listOf("ordinary", "private").forEach { id ->
            favorites.upsert(FavoriteEntity(id = id, type = "node", refKey = "node:$id:node", refJson = "{}", snapshotJson = "{}", createdAt = 1, updatedAt = 1))
        }
        assertEquals(listOf("ordinary"), favorites.listVisibleByType("node").awaitValue().map { it.id })
        assertEquals(2, favorites.listByType("node").awaitValue().size)
        assertEquals(2, favorites.listAll().awaitValue().size)
    }
}
