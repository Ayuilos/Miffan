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
class Migration_26_27_Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun existingHostKeepsAuthenticationAndStartsWithoutManagedKey() {
        val databaseName = "migration-26-27-test"
        helper.createDatabase(databaseName, 26).apply {
            insert("remote_hosts", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
                put("id", "existing-host")
                put("name", "Existing")
                put("host", "100.64.0.1")
                put("port", 22)
                put("username", "tester")
                put("auth_type", "PRIVATE_KEY")
                put("trusted_host_key_sha256", "SHA256:existing")
                put("created_at", 1L)
                put("updated_at", 2L)
            })
            close()
        }

        val db = helper.runMigrationsAndValidate(databaseName, 27, true, Migration_26_27)
        db.query("SELECT auth_type, ssh_key_id FROM remote_hosts WHERE id = 'existing-host'").use {
            assertTrue(it.moveToFirst())
            assertEquals("PRIVATE_KEY", it.getString(0))
            assertNull(it.getString(1))
        }
        db.query("PRAGMA table_info(ssh_keys)").use { cursor ->
            val columns = buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
            assertTrue("public_key" in columns)
            assertTrue("fingerprint" in columns)
            assertTrue("private_key" !in columns)
        }
        db.close()
    }
}
