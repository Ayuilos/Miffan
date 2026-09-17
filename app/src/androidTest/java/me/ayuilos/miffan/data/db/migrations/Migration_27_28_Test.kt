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
class Migration_27_28_Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun existingHostRetainsIdentityAndReceivesInitialRevision() {
        val name = "migration-27-28-test"
        helper.createDatabase(name, 27).apply {
            insert("remote_hosts", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
                put("id", "existing-host")
                put("name", "Existing")
                put("host", "100.64.0.1")
                put("port", 22)
                put("username", "tester")
                put("auth_type", "PRIVATE_KEY")
                put("ssh_key_id", "key-id")
                put("trusted_host_key_sha256", "SHA256:existing")
                put("created_at", 1L)
                put("updated_at", 2L)
            })
            close()
        }
        helper.runMigrationsAndValidate(name, 28, true, Migration_27_28).use { db ->
            db.query("SELECT host, ssh_key_id, trusted_host_key_sha256, connection_revision FROM remote_hosts").use {
                assertTrue(it.moveToFirst())
                assertEquals("100.64.0.1", it.getString(0))
                assertEquals("key-id", it.getString(1))
                assertEquals("SHA256:existing", it.getString(2))
                assertEquals("legacy", it.getString(3))
            }
        }
    }
}
