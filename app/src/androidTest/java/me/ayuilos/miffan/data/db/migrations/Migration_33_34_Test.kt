package me.ayuilos.miffan.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.data.model.MessageOrigin
import me.ayuilos.miffan.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class Migration_33_34_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun oldOwnerNodesAndBranchesSurviveAndNewOriginsRoundTrip() {
        // This isolated fixture database never opens or deletes the app's real database.
        val name = "migration-33-34-origin-test"
        helper.createDatabase(name, 33).use { db ->
            db.execSQL("""INSERT INTO conversationentity (id, assistant_id, title, nodes, create_at, update_at,
                suggestions, is_pinned, selected_root_id) VALUES ('conversation', 'assistant', 'topic', '[]', 1, 2, '[]', 0, 'root')""")
            db.execSQL("""INSERT INTO message_node (id, conversation_id, node_index, parent_id, selected_child_id, message,
                revision, reply_to) VALUES ('root', 'conversation', 0, '', 'child', 'owner-json', 7, 'reply-json')""")
            db.execSQL("""INSERT INTO message_node (id, conversation_id, node_index, parent_id, selected_child_id, message,
                revision) VALUES ('child', 'conversation', 1, 'root', '', 'assistant-json', 3)""")
        }
        helper.runMigrationsAndValidate(name, 34, true, Migration_33_34).use { db ->
            db.query("SELECT origin, message, revision, reply_to, selected_child_id FROM message_node WHERE id = 'root'").use {
                assertTrue(it.moveToFirst()); assertEquals("", it.getString(0)); assertEquals("owner-json", it.getString(1))
                assertEquals(7, it.getLong(2)); assertEquals("reply-json", it.getString(3)); assertEquals("child", it.getString(4))
            }
            for (origin in listOf(MessageOrigin.Event("debug", "summary"), MessageOrigin.Partner(Uuid.random(), Uuid.random()))) {
                val json = JsonInstant.encodeToString<MessageOrigin>(origin)
                db.execSQL("UPDATE message_node SET origin = ? WHERE id = 'child'", arrayOf(json))
                db.query("SELECT origin, parent_id, message FROM message_node WHERE id = 'child'").use {
                    assertTrue(it.moveToFirst()); assertEquals(origin, JsonInstant.decodeFromString<MessageOrigin>(it.getString(0)))
                    assertEquals("root", it.getString(1)); assertEquals("assistant-json", it.getString(2))
                }
            }
        }
    }
}
