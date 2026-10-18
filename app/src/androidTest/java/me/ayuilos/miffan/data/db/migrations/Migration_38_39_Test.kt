package me.ayuilos.miffan.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.ayuilos.miffan.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_38_39_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun existingSegmentsDefaultToNullAndNewReasonsRoundTrip() {
        val name = "migration-38-39-topic-start"
        helper.createDatabase(name, 38).use {
            it.execSQL("""INSERT INTO ConversationEntity
                (id, title, nodes, create_at, update_at, suggestions, is_pinned)
                VALUES ('legacy', 'Earlier topic', '[]', 1, 2, '[]', 0)""")
        }
        helper.runMigrationsAndValidate(name, 39, true, Migration_38_39).use { db ->
            db.query("SELECT title, topic_start FROM ConversationEntity WHERE id = 'legacy'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Earlier topic", it.getString(0))
                assertTrue(it.isNull(1))
            }
            for (start in listOf("AUTO", "IDLE", "OWNER", "WELCOME")) {
                db.execSQL("UPDATE ConversationEntity SET topic_start = ? WHERE id = 'legacy'", arrayOf(start))
                db.query("SELECT topic_start FROM ConversationEntity WHERE id = 'legacy'").use {
                    assertTrue(it.moveToFirst()); assertEquals(start, it.getString(0))
                }
            }
        }
    }
}
