package me.ayuilos.miffan.data.db.migrations

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_37_38_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun existingHostIdentityAndDefaultsSurvive() {
        val name = "migration-37-38-addresses"
        helper.createDatabase(name, 37).use { db ->
            db.execSQL("""INSERT INTO remote_hosts (id, name, host, port, username, auth_type,
                connection_revision, trusted_host_key_sha256, created_at, updated_at)
                VALUES ('id', 'Computer', '192.168.1.5', 22, 'user', 'PASSWORD', 'revision', 'pin', 1, 2)""")
        }
        helper.runMigrationsAndValidate(name, 38, true, Migration_37_38).use { db ->
            db.query("SELECT host, port, connection_revision, trusted_host_key_sha256, alternate_addresses, last_address FROM remote_hosts").use {
                assertTrue(it.moveToFirst())
                assertEquals("192.168.1.5", it.getString(0))
                assertEquals(22, it.getInt(1))
                assertEquals("revision", it.getString(2))
                assertEquals("pin", it.getString(3))
                assertEquals("[]", it.getString(4))
                assertTrue(it.isNull(5))
            }
        }
    }

    @Test fun addressWritesPreserveTrustRevisionAndSettingsAndRejectStaleWrites() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java).build()
        try {
            val dao = db.remoteHostDao()
            val row = RemoteHostEntity("id", "Computer", "main", 22, "user", "PASSWORD", "revision",
                trustedHostKeySha256 = "pin", createdAt = 1, updatedAt = 2, screenEnabled = true)
            dao.insert(row)
            val alternate = "[{\"host\":\"alternate\",\"port\":2222}]"
            assertEquals(1, dao.compareAndSetAddresses("id", "main", 22, alternate, null,
                "main", 22, "[]", null, "revision", "pin"))
            assertEquals(1, dao.updateLastAddress("id", "alternate:2222", "revision", "main", 22, alternate))
            val saved = dao.getById("id")!!
            assertEquals(row.connectionRevision, saved.connectionRevision)
            assertEquals(row.trustedHostKeySha256, saved.trustedHostKeySha256)
            assertEquals(row.updatedAt, saved.updatedAt)
            assertTrue(saved.screenEnabled)
            assertEquals(0, dao.updateLastAddress("id", "main:22", "old-revision", "main", 22, alternate))
            assertEquals(0, dao.compareAndSetAddresses("id", "main", 22, "[]", null,
                "main", 22, "[]", null, "revision", "pin"))
            // An explicit main edit removes its duplicate alternate and resets last-address preference.
            dao.updateConnection(saved.copy(host = "alternate", port = 2222, connectionRevision = "new", trustedHostKeySha256 = null))
            assertEquals("[]", dao.getById("id")!!.alternateAddresses)
            assertNull(dao.getById("id")!!.lastAddressValue)
        } finally {
            db.close()
        }
    }
}
