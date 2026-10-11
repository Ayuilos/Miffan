package me.ayuilos.miffan.data.db.migrations

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.data.db.entity.ConversationEntity
import me.ayuilos.miffan.data.db.entity.PartnerChatEntity
import me.ayuilos.miffan.data.db.entity.PartnerChatMemberEntity
import me.ayuilos.miffan.data.db.entity.PartnerChatMessageEntity
import me.ayuilos.miffan.data.model.PartnerChatMessage
import me.ayuilos.miffan.data.partner.ChatMessage
import me.ayuilos.miffan.data.partner.RoomPartnerChatStore
import java.time.Instant
import kotlin.uuid.Uuid
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_34_35_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun oldDataSurvivesAndPartnerTablesCascade() {
        val name = "migration-34-35-partner-test" // Never open/clear the user's rikka_hub database.
        helper.createDatabase(name, 34).use { db ->
            db.execSQL("""INSERT INTO conversationentity (id, assistant_id, title, nodes, create_at, update_at,
                suggestions, is_pinned, thread_summary) VALUES ('old', 'a', 'topic', '[]', 1, 2, '[]', 1, 'summary')""")
            db.execSQL("""INSERT INTO message_node (id, conversation_id, node_index, message, revision, origin)
                VALUES ('node', 'old', 0, 'json', 3, 'partner-json')""")
        }
        helper.runMigrationsAndValidate(name, 35, true, Migration_34_35).use { db ->
            db.query("SELECT chat_id, title, is_pinned, thread_summary FROM conversationentity WHERE id = 'old'").use {
                assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertEquals("topic", it.getString(1))
                assertEquals(1, it.getInt(2)); assertEquals("summary", it.getString(3))
            }
            db.query("SELECT origin, revision FROM message_node WHERE id = 'node'").use {
                assertTrue(it.moveToFirst()); assertEquals("partner-json", it.getString(0)); assertEquals(3, it.getInt(1))
            }
            // MigrationTestHelper's raw connection does not enable Room's foreign-key pragma.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("INSERT INTO partner_chat VALUES ('chat', 'DIRECT', '', 1)")
            db.execSQL("INSERT INTO partner_chat_member VALUES ('chat', 'a', 1, 'old', 'msg')")
            db.execSQL("INSERT INTO partner_chat_message VALUES ('msg', 'chat', 'a', 'hello', 1, 'exchange', NULL, '[\"a\"]')")
            db.execSQL("UPDATE conversationentity SET chat_id = 'chat' WHERE id = 'old'")
            db.query("SELECT perspective_segment_id, synced_message_id FROM partner_chat_member").use {
                assertTrue(it.moveToFirst()); assertEquals("old", it.getString(0)); assertEquals("msg", it.getString(1))
            }
            db.execSQL("DELETE FROM partner_chat WHERE id = 'chat'")
            for (table in listOf("partner_chat_member", "partner_chat_message")) db.query("SELECT COUNT(*) FROM $table").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test fun roomQueriesExcludePerspectivesFromImButProfessionalListKeepsThem() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.conversationDao()
            fun conversation(id: String, at: Long, chatId: String? = null) = ConversationEntity(id, "a", id, "[]", 1,
                at, "[]", false, threadSummary = "digest-$id", chatId = chatId)
            dao.insert(conversation("im", 1))
            dao.insert(conversation("perspective", 10, "chat"))
            assertEquals(listOf("im"), dao.observeThreadSegmentStamps("a", 10).first().map { it.id })
            assertEquals(listOf("im"), dao.observeLatestConversationOfEachAssistant().first().map { it.id })
            assertEquals(listOf("im"), dao.getRecentConversationsOfAssistant("a", 10).map { it.id })
            assertEquals(listOf("im"), dao.getThreadDigests("a", "new", 10).map { it.id })
            assertEquals(setOf("im", "perspective"), dao.getConversationsOfAssistant("a").first().map { it.id }.toSet())
            val partner = db.partnerChatDao()
            partner.insert(PartnerChatEntity("chat", "DIRECT", "", 1))
            partner.insert(PartnerChatMemberEntity("chat", "a", 1))
            partner.insert(PartnerChatMessageEntity("one", "chat", "a", "first", 1))
            partner.insert(PartnerChatMessageEntity("two", "chat", "b", "second", 1))
            assertEquals(listOf("one", "two"), partner.getMessages("chat").map { it.id })
        } finally { db.close() }
    }

    @Test fun directPairIsUniqueAndNestedRepliesReserveTheSixMessageBudget() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store = RoomPartnerChatStore(db) { error("No files involved in this fixture") }
            val a = Uuid.random(); val b = Uuid.random(); val chat = store.direct(a, b)
            val reopened = RoomPartnerChatStore(db) { error("No files involved in this fixture") }
            assertEquals(chat, reopened.direct(b, a))
            assertEquals(1, db.partnerChatDao().getIds().size)
            val exchange = Uuid.random()
            val requests = (1..3).map { PartnerChatMessage(Uuid.random(), chat.id, a, "request $it", Instant.now(), exchange) }
            requests.forEach { assertTrue(store.append(ChatMessage(it, listOf(a)), reserveReply = true)) }
            assertEquals(6, store.exchangeUsage(exchange))
            assertFalse(store.append(ChatMessage(PartnerChatMessage(Uuid.random(), chat.id, a, "over budget", Instant.now(), exchange)), true))
            requests.reversed().forEach { request ->
                assertTrue(store.append(ChatMessage(PartnerChatMessage(Uuid.random(), chat.id, b, "reply", Instant.now(), exchange, request.id))))
            }
            assertEquals(6, store.messages(chat.id).size)
            assertEquals(listOf(a), store.messages(chat.id).first().chain)
            assertFalse(store.append(ChatMessage(PartnerChatMessage(Uuid.random(), chat.id, a, "seventh", Instant.now(), exchange)), true))
        } finally { db.close() }
    }
}
