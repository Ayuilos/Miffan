package me.ayuilos.miffan.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.ayuilos.miffan.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_25_26_Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun legacyWorkspaceRemainsLocalAndHostTableContainsNoCredentialColumn() {
        val databaseName = "migration-25-26-test"
        helper.createDatabase(databaseName, 25).apply {
            insert("workspaces", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
                put("id", "legacy-workspace")
                put("name", "Legacy")
                put("root", "legacy-root")
                put("shell_status", "READY")
                put("created_at", 1L)
                put("updated_at", 2L)
            })
            close()
        }

        val db = helper.runMigrationsAndValidate(databaseName, 26, true, Migration_25_26)
        db.query("SELECT kind, remote_host_id, remote_path, root FROM workspaces WHERE id = 'legacy-workspace'").use {
            assertTrue(it.moveToFirst())
            assertEquals("LOCAL", it.getString(0))
            assertNull(it.getString(1))
            assertNull(it.getString(2))
            assertEquals("legacy-root", it.getString(3))
        }
        db.query("PRAGMA table_info(remote_hosts)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
            assertTrue("trusted_host_key_sha256" in columns)
            assertTrue("auth_type" in columns)
            assertTrue("password" !in columns)
            assertTrue("private_key" !in columns)
        }
        db.close()
    }
}
