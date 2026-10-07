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

@RunWith(AndroidJUnit4::class)
class Migration_29_30_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun existingHostKeepsItsConnectionAndGetsScreenDisabled() {
        val name = "migration-29-30-test"
        helper.createDatabase(name, 29).use { db ->
            db.insert("remote_hosts", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
                put("id", "00000000-0000-0000-0000-000000000001")
                put("name", "mac")
                put("host", "100.64.0.5")
                put("port", 22)
                put("username", "me")
                put("auth_type", "PASSWORD")
                put("connection_revision", "rev")
                put("trusted_host_key_sha256", "SHA256:x")
                put("created_at", 1L)
                put("updated_at", 2L)
            })
        }

        helper.runMigrationsAndValidate(name, 30, true, Migration_29_30).use { db ->
            db.query(
                "SELECT host, connection_revision, screen_enabled, screen_endpoint, screen_auth, " +
                    "screen_username, screen_platform FROM remote_hosts"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("100.64.0.5", it.getString(0))
                assertEquals("rev", it.getString(1))
                assertEquals(0, it.getInt(2))
                assertEquals("tcp:5900", it.getString(3))
                assertEquals("none", it.getString(4))
                assertEquals("", it.getString(5))
                assertEquals("", it.getString(6))
            }
        }
    }
}
