package me.ayuilos.miffan.data.partner

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.data.db.entity.ConversationEntity
import me.ayuilos.miffan.data.db.fts.MessageFtsManager
import me.ayuilos.miffan.data.db.fts.SimpleDictManager
import me.ayuilos.miffan.data.model.Conversation
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.uuid.Uuid

/** Isolated fixture databases: never clears or opens the user's chat database. */
@RunWith(AndroidJUnit4::class)
class PartnerRecallQueryTest {
    @Test fun realFtsScopesPerspectivesBeforeLimitAndOwnerSearchStaysIsolated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Requery bypasses custom extension options for unnamed in-memory databases.
        val fixtureName = "partner-recall-fts-${Uuid.random()}"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, fixtureName)
            .openHelperFactory(RequerySQLiteOpenHelperFactory(listOf(
                RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                    options.customExtensions.add(SQLiteCustomExtension(context.applicationInfo.nativeLibraryDir + "/libsimple", null))
                    options
                })))
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.query("SELECT jieba_dict(?)", arrayOf(SimpleDictManager.extractDict(context).absolutePath)).use { it.moveToFirst() }
                    db.execSQL("CREATE VIRTUAL TABLE message_fts USING fts5(text, node_id UNINDEXED, message_id UNINDEXED, conversation_id UNINDEXED, title UNINDEXED, update_at UNINDEXED, tokenize='simple')")
                }
            }).build()
        try {
            val a = Uuid.random(); val b = Uuid.random()
            var im = true
            val fts = MessageFtsManager(db) { im }
            suspend fun add(assistant: Uuid, chatId: Uuid? = null): Conversation {
                val c = Conversation(assistantId = assistant, chatId = chatId, title = "fixture", updateAt = Instant.ofEpochMilli(2), messageNodes = emptyList()).appendMessage(UIMessage.user("recallneedle 秋日暗号"))
                db.conversationDao().insert(ConversationEntity(c.id.toString(), assistant.toString(), c.title, "[]", 1, 2, "[]", false, chatId = chatId?.toString()))
                fts.indexConversation(c)
                return c
            }
            val ownerA = add(a); val ownerB = add(b)
            val direct = add(a, Uuid.random()); val group = add(a, Uuid.random())
            repeat(55) { add(b, Uuid.random()) }
            val ownerIds = setOf(ownerA.id.toString(), ownerB.id.toString())
            assertEquals(ownerIds, fts.search("recallneedle").map { it.conversationId }.toSet())
            val recalled = fts.search("recallneedle", perspectiveAssistantId = a.toString())
            assertEquals(ownerIds + setOf(direct.id.toString(), group.id.toString()), recalled.map { it.conversationId }.toSet())
            assertEquals(direct.chatId.toString(), recalled.single { it.conversationId == direct.id.toString() }.chatId)
            assertTrue(recalled.all { it.snippet.contains("recallneedle") })
            im = false
            assertEquals(50, fts.search("recallneedle").size)
            assertEquals(50, fts.search("recallneedle", perspectiveAssistantId = a.toString()).size)
        } finally { db.close(); context.deleteDatabase(fixtureName) }
    }

    @Test fun ownerRecentQuerySupportsActivityOrderWithoutChangingDefault() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.conversationDao()
            dao.insert(ConversationEntity("old-pinned", "a", "old", "[]", 1, 1, "[]", true))
            dao.insert(ConversationEntity("new", "a", "new", "[]", 1, 3, "[]", false))
            dao.insert(ConversationEntity("perspective", "a", "hidden", "[]", 1, 9, "[]", false, chatId = "chat"))
            assertEquals(listOf("old-pinned"), dao.getRecentConversationsOfAssistant("a", 1).map { it.id })
            assertEquals(listOf("new"), dao.getRecentConversationsOfAssistant("a", 1, newestFirst = true).map { it.id })
        } finally { db.close() }
    }
}
