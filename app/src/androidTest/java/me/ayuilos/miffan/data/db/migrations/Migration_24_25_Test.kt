package me.ayuilos.miffan.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.ayuilos.miffan.data.db.AppDatabase
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
    fun migrationReplacesLegacyBranchesAndClearsIncompatibleChatData() {
        val conversationId = Uuid.random().toString()
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
                    put("update_at", 1L)
                },
            )
            insert(
                "message_node",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", Uuid.random().toString())
                    put("conversation_id", conversationId)
                    put("node_index", 0)
                    put("messages", "[]")
                    put("select_index", 0)
                },
            )
            insert(
                "favorites",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", Uuid.random().toString())
                    put("type", "node")
                    put("ref_key", "node:$conversationId")
                    put("ref_json", "{}")
                    put("snapshot_json", "{}")
                    put("created_at", 1L)
                    put("updated_at", 1L)
                },
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(databaseName, 25, true, Migration_24_25)

        db.query("SELECT id FROM ConversationEntity").use { cursor ->
            assertEquals(0, cursor.count)
        }
        db.query("SELECT id FROM message_node").use { cursor ->
            assertEquals(0, cursor.count)
        }
        db.query("SELECT id FROM favorites WHERE type IN ('node', 'message')").use { cursor ->
            assertEquals(0, cursor.count)
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
}
