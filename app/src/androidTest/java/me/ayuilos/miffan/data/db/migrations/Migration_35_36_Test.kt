package me.ayuilos.miffan.data.db.migrations

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.data.model.*
import me.ayuilos.miffan.data.partner.*
import java.time.Instant
import kotlin.uuid.Uuid
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_35_36_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun privateChatsMessagesAndPerspectivePointersSurvive() {
        val name = "migration-35-36-group-fixture"
        helper.createDatabase(name, 35).use { db ->
            db.execSQL("INSERT INTO partner_chat VALUES ('chat', 'DIRECT', 'old', 1)")
            db.execSQL("INSERT INTO partner_chat_member VALUES ('chat', 'a', 2, 'segment', 'msg')")
            db.execSQL("INSERT INTO partner_chat_message VALUES ('msg', 'chat', 'a', 'hello', 3, 'exchange', NULL, '[\"a\"]')")
            db.execSQL("""INSERT INTO conversationentity (id, assistant_id, title, nodes, create_at, update_at,
                suggestions, is_pinned, thread_summary, chat_id) VALUES ('segment', 'a', 'private', '[]', 1, 2, '[]', 1, 'summary', 'chat')""")
            db.execSQL("""INSERT INTO message_node (id, conversation_id, node_index, message, revision, origin)
                VALUES ('node', 'segment', 0, 'json', 3, 'partner-json')""")
        }
        helper.runMigrationsAndValidate(name, 36, true, Migration_35_36).use { db ->
            db.query("SELECT title, host_id FROM partner_chat").use {
                assertTrue(it.moveToFirst()); assertEquals("old", it.getString(0)); assertTrue(it.isNull(1))
            }
            db.query("SELECT text, chain, mentions FROM partner_chat_message").use {
                assertTrue(it.moveToFirst()); assertEquals("hello", it.getString(0)); assertEquals("[\"a\"]", it.getString(1)); assertEquals("[]", it.getString(2))
            }
            db.query("SELECT perspective_segment_id, synced_message_id FROM partner_chat_member").use {
                assertTrue(it.moveToFirst()); assertEquals("segment", it.getString(0)); assertEquals("msg", it.getString(1))
            }
            db.query("SELECT origin, revision FROM message_node WHERE id = 'node'").use {
                assertTrue(it.moveToFirst()); assertEquals("partner-json", it.getString(0)); assertEquals(3, it.getInt(1))
            }
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM partner_chat WHERE id = 'chat'")
            listOf("partner_chat_member", "partner_chat_message").forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
        }
    }

    @Test fun roomPersistsHostMentionsAndJoinCursorAndAllowsEightGroupReplies() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val store = RoomPartnerChatStore(db) { error("No perspective files in fixture") }
            val a = Uuid.random(); val b = Uuid.random(); val c = Uuid.random()
            val chat = PartnerChat(Uuid.random(), PartnerChatKind.GROUP, listOf(a, b), "group", Instant.now(), a)
            store.saveGroup(chat)
            val exchange = Uuid.random()
            repeat(8) { i ->
                assertTrue(store.append(ChatMessage(PartnerChatMessage(Uuid.random(), chat.id, a, "reply $i", Instant.now(), exchange,
                    mentions = listOf(b, OWNER_PARTICIPANT_ID)))))
            }
            store.replaceMembers(chat.copy(memberIds = listOf(b, a, c), hostId = b))
            assertEquals(listOf(b, a, c), store.chat(chat.id)?.memberIds)
            assertEquals(b, store.chat(chat.id)?.hostId)
            assertEquals(store.messages(chat.id).last().message.id, store.member(chat.id, c).syncedMessageId)
            assertEquals(listOf(b, OWNER_PARTICIPANT_ID), store.messages(chat.id).first().message.mentions)
            val reopened = RoomPartnerChatStore(db) { error("No perspective files in fixture") }
            assertEquals(store.chat(chat.id), reopened.chat(chat.id))
            assertEquals(store.messages(chat.id), reopened.messages(chat.id))
            val orphan = Uuid.random()
            db.conversationDao().insert(me.ayuilos.miffan.data.db.entity.ConversationEntity(
                orphan.toString(), Uuid.random().toString(), "orphan", "[]", 1, 1, "[]", false, chatId = chat.id.toString()))
            assertEquals(listOf(orphan.toString()), db.conversationDao().getPartnerSegmentIds(chat.id.toString()))
            assertTrue(db.conversationDao().getPartnerSegmentIds(chat.id.toString(), a.toString()).isEmpty())
        } finally { db.close() }
    }
}
