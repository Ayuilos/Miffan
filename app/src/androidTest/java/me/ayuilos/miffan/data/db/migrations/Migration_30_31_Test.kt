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
class Migration_30_31_Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migrationKeepsExistingDataAndAuditIsIndependent() {
        val name = "migration-30-31-test"
        helper.createDatabase(name, 30).use { db ->
            db.execSQL("INSERT INTO memoryentity (id, assistant_id, content) VALUES (1, 'partner', 'remember')")
        }
        helper.runMigrationsAndValidate(name, 31, true, Migration_30_31).use { db ->
            db.query("SELECT content FROM memoryentity WHERE id = 1").use {
                assertTrue(it.moveToFirst()); assertEquals("remember", it.getString(0))
            }
            db.execSQL("INSERT INTO audit_event (id, at, kind, tool_call_id, summary) VALUES ('a', 1, 'APPROVAL', 'call', 'Run command: uname -a')")
            db.execSQL("INSERT OR IGNORE INTO audit_event (id, at, kind, tool_call_id, summary) VALUES ('b', 2, 'APPROVAL', 'call', 'duplicate')")
            db.execSQL("DELETE FROM conversationentity")
            db.query("SELECT COUNT(*) FROM audit_event").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            db.query("PRAGMA foreign_key_list(audit_event)").use { assertEquals(0, it.count) }
        }
    }
}
