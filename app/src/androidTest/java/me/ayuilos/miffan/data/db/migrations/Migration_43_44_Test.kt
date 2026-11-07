package me.ayuilos.miffan.data.db.migrations

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

/** 43 → 44 only adds and drops indexes; rows must survive and the hot queries must use them. */
@RunWith(AndroidJUnit4::class)
class Migration_43_44_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun rowsSurviveAndHotQueriesUseTheNewIndexes() {
        val name = "migration-43-44-test"
        helper.createDatabase(name, 43).use { db ->
            db.execSQL(
                """INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned)
                   VALUES ('c1', 'a1', 'First', '[]', 1, 20, '[]', 0), ('c2', 'a1', 'Second', '[]', 2, 30, '[]', 1)"""
            )
            db.execSQL(
                """INSERT INTO message_node (id, conversation_id, node_index, message, revision)
                   VALUES ('n1', 'c1', 0, '{}', 1), ('n2', 'c1', 1, '{}', 2)"""
            )
            db.execSQL(
                """INSERT INTO revision (id, subject_type, subject_id, author, snapshot, created_at)
                   VALUES ('r1', 'ASSISTANT', 'a1', 'agent', '{}', 5)"""
            )
            db.execSQL("INSERT INTO partner_chat (id, kind, title, created_at) VALUES ('p1', 'GROUP', 'g', 1)")
            db.execSQL(
                """INSERT INTO partner_chat_message (id, chat_id, sender_id, text, created_at)
                   VALUES ('m1', 'p1', 's', 'hi', 9)"""
            )
            db.execSQL(
                """INSERT INTO favorites (id, type, ref_key, ref_json, snapshot_json, created_at, updated_at)
                   VALUES ('f1', 'NODE', 'node:c1:n1', '{}', '{}', 1, 1), ('f2', 'NODE', 'node:c10:n9', '{}', '{}', 1, 1)"""
            )
        }

        helper.runMigrationsAndValidate(name, 44, true).use { db ->
            assertEquals(listOf("c2", "c1"), strings(db, "SELECT id FROM ConversationEntity ORDER BY update_at DESC"))
            assertEquals(listOf("n1", "n2"), strings(db, "SELECT id FROM message_node ORDER BY node_index"))
            assertEquals(listOf("r1"), strings(db, "SELECT id FROM revision"))
            assertEquals(listOf("m1"), strings(db, "SELECT id FROM partner_chat_message"))

            val indexes = strings(db, "SELECT name FROM sqlite_master WHERE type = 'index'").toSet()
            assertTrue("index_ConversationEntity_assistant_id_chat_id_update_at" in indexes)
            assertTrue("index_ConversationEntity_folder_id" in indexes)
            assertTrue("index_message_node_conversation_id_node_index" in indexes)
            assertTrue("index_revision_author_subject_id" in indexes)
            assertTrue("index_partner_chat_message_chat_id_created_at" in indexes)
            assertFalse("index_message_node_conversation_id" in indexes)

            assertPlan(
                db, "index_ConversationEntity_assistant_id_chat_id_update_at",
                "SELECT id FROM ConversationEntity WHERE chat_id IS NULL AND assistant_id = 'a1' ORDER BY update_at DESC LIMIT 5",
            )
            assertPlan(
                db, "index_message_node_conversation_id_node_index",
                "SELECT * FROM message_node WHERE conversation_id = 'c1' ORDER BY node_index DESC LIMIT 40",
            )
            assertPlan(
                db, "index_revision_author_subject_id",
                "SELECT * FROM revision WHERE author = 'agent' AND subject_id IN ('a1', 'a2') ORDER BY created_at ASC, rowid ASC",
            )
            assertPlan(
                db, "index_favorites_ref_key",
                "SELECT substr(ref_key, 11) FROM favorites WHERE ref_key >= 'node:c1:' AND ref_key < 'node:c1;'",
            )
            assertEquals(listOf("n1"), strings(db, "SELECT substr(ref_key, 9) FROM favorites WHERE ref_key >= 'node:c1:' AND ref_key < 'node:c1;'"))
        }
    }

    private fun strings(db: SupportSQLiteDatabase, sql: String): List<String> =
        db.query(sql).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun assertPlan(db: SupportSQLiteDatabase, index: String, sql: String) {
        val plan = db.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }.joinToString("\n")
        assertTrue("expected $index in plan for: $sql\n$plan", index in plan)
        assertFalse("full scan in plan for: $sql\n$plan", plan.lines().any { it.startsWith("SCAN ") && "USING" !in it })
    }
}
