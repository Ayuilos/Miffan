package me.ayuilos.miffan.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.ayuilos.miffan.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_28_29_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun existingDataSurvivesWithImDefaultsAndRevisionSchemaIsCreated() {
        val name = "migration-28-29-test"
        helper.createDatabase(name, 28).use { db ->
            db.insert("ConversationEntity", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
                put("id", "existing-thread")
                put("assistant_id", "assistant")
                put("title", "Existing conversation")
                put("nodes", "[]")
                put("create_at", 10L)
                put("update_at", 20L)
                put("selected_root_id", "existing-node")
            })
            db.insert("message_node", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
                put("id", "existing-node")
                put("conversation_id", "existing-thread")
                put("node_index", 3)
                put("message", """{"role":"user","content":"Existing message"}""")
                put("revision", 7L)
            })
            db.insert("MemoryEntity", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
                put("id", 42)
                put("assistant_id", "assistant")
                put("content", "Existing memory")
            })
        }

        helper.runMigrationsAndValidate(name, 29, true, Migration_28_29).use { db ->
            assertTrue(columns(db, "ConversationEntity").containsAll(listOf("thread_summary", "thread_closed_at")))
            assertTrue("reply_to" in columns(db, "message_node"))
            assertTrue(columns(db, "MemoryEntity").containsAll(listOf("created_at", "source_conversation_id", "source_message_id")))
            db.query("SELECT assistant_id, title, create_at, update_at, selected_root_id, thread_summary, thread_closed_at FROM ConversationEntity WHERE id = 'existing-thread'").use {
                assertTrue(it.moveToFirst())
                assertEquals("assistant", it.getString(0))
                assertEquals("Existing conversation", it.getString(1))
                assertEquals(10L, it.getLong(2))
                assertEquals(20L, it.getLong(3))
                assertEquals("existing-node", it.getString(4))
                assertEquals("", it.getString(5))
                assertEquals(0L, it.getLong(6))
                assertFalse(it.moveToNext())
            }
            db.query("SELECT conversation_id, node_index, message, revision, reply_to FROM message_node WHERE id = 'existing-node'").use {
                assertTrue(it.moveToFirst())
                assertEquals("existing-thread", it.getString(0))
                assertEquals(3, it.getInt(1))
                assertEquals("""{"role":"user","content":"Existing message"}""", it.getString(2))
                assertEquals(7L, it.getLong(3))
                assertEquals("", it.getString(4))
                assertFalse(it.moveToNext())
            }
            db.query("SELECT assistant_id, content, created_at, source_conversation_id, source_message_id FROM MemoryEntity WHERE id = 42").use {
                assertTrue(it.moveToFirst())
                assertEquals("assistant", it.getString(0))
                assertEquals("Existing memory", it.getString(1))
                assertEquals(0L, it.getLong(2))
                assertEquals("", it.getString(3))
                assertEquals("", it.getString(4))
                assertFalse(it.moveToNext())
            }
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'revision'").use {
                assertTrue(it.moveToFirst())
                assertEquals("revision", it.getString(0))
            }
            db.query("PRAGMA index_list(revision)").use { cursor ->
                val indexes = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue("index_revision_subject_type_subject_id_created_at" in indexes)
            }
            db.query("PRAGMA index_info(index_revision_subject_type_subject_id_created_at)").use { cursor ->
                val indexColumns = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertEquals(listOf("subject_type", "subject_id", "created_at"), indexColumns)
            }
            db.query("SELECT COUNT(*) FROM revision").use {
                assertTrue(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
        }
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info($table)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
}
