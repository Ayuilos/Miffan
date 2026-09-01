package me.ayuilos.miffan.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class Migration_24_25_Test {
    private val databaseName = "migration-24-25-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationPreservesConversationAndItsSelectedMessagePath() {
        val conversationId = Uuid.random().toString()
        val firstNodeId = Uuid.random().toString()
        val secondNodeId = Uuid.random().toString()
        val favoriteId = Uuid.random().toString()
        val selectedQuestion = UIMessage.user("Selected legacy question")
        val selectedAnswer = UIMessage.assistant("Selected legacy answer")

        helper.createDatabase(databaseName, 24).apply {
            insert(
                "ConversationEntity",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", conversationId)
                    put("assistant_id", Uuid.random().toString())
                    put("title", "Legacy conversation")
                    put("nodes", "[]")
                    put("create_at", 1L)
                    put("update_at", 2L)
                    put("is_pinned", 1)
                },
            )
            insertLegacyNode(
                conversationId = conversationId,
                nodeId = firstNodeId,
                index = 0,
                messages = listOf(UIMessage.user("Unselected question"), selectedQuestion),
                selectIndex = 1,
            )
            insertLegacyNode(
                conversationId = conversationId,
                nodeId = secondNodeId,
                index = 1,
                messages = listOf(selectedAnswer, UIMessage.assistant("Unselected answer")),
                selectIndex = 0,
            )
            insert(
                "favorites",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", favoriteId)
                    put("type", "node")
                    put("ref_key", "node:$conversationId:$firstNodeId")
                    put("ref_json", "{}")
                    put("snapshot_json", "{}")
                    put("created_at", 1L)
                    put("updated_at", 1L)
                },
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(databaseName, 25, true, Migration_24_25)

        db.query(
            "SELECT title, update_at, is_pinned, selected_root_id FROM ConversationEntity WHERE id = ?",
            arrayOf(conversationId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Legacy conversation", cursor.getString(0))
            assertEquals(2L, cursor.getLong(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals(firstNodeId, cursor.getString(3))
        }

        db.query(
            """
            SELECT id, node_index, parent_id, selected_child_id, message
            FROM message_node
            WHERE conversation_id = ?
            ORDER BY node_index ASC
            """.trimIndent(),
            arrayOf(conversationId),
        ).use { cursor ->
            assertEquals(2, cursor.count)

            assertTrue(cursor.moveToNext())
            assertEquals(firstNodeId, cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals("", cursor.getString(2))
            assertEquals(secondNodeId, cursor.getString(3))
            assertEquals(selectedQuestion, JsonInstant.decodeFromString<UIMessage>(cursor.getString(4)))

            assertTrue(cursor.moveToNext())
            assertEquals(secondNodeId, cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(firstNodeId, cursor.getString(2))
            assertEquals("", cursor.getString(3))
            assertEquals(selectedAnswer, JsonInstant.decodeFromString<UIMessage>(cursor.getString(4)))
        }

        db.query("SELECT ref_key FROM favorites WHERE id = ?", arrayOf(favoriteId)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("node:$conversationId:$firstNodeId", cursor.getString(0))
        }
        db.query("PRAGMA table_info(message_node)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
            assertTrue(columns.containsAll(setOf("parent_id", "selected_child_id", "message", "revision")))
            assertTrue("messages" !in columns)
            assertTrue("select_index" !in columns)
        }
        db.close()
    }

    private fun SupportSQLiteDatabase.insertLegacyNode(
        conversationId: String,
        nodeId: String,
        index: Int,
        messages: List<UIMessage>,
        selectIndex: Int,
    ) {
        insert(
            "message_node",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("id", nodeId)
                put("conversation_id", conversationId)
                put("node_index", index)
                put("messages", JsonInstant.encodeToString(messages))
                put("select_index", selectIndex)
            },
        )
    }
}
