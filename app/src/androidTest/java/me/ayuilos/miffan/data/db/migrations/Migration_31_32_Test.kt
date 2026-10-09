package me.ayuilos.miffan.data.db.migrations

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
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
class Migration_31_32_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun oldVncHostKeepsSettingsAndRdpStartsUnpinned() {
        val name = "migration-31-32-test"
        helper.createDatabase(name, 31).use { db ->
            db.execSQL("""INSERT INTO remote_hosts (id,name,host,port,username,auth_type,
                trusted_host_key_sha256,created_at,updated_at,screen_enabled,screen_endpoint,screen_auth,screen_username)
                VALUES ('host','existing','localhost',22,'user','key','ssh-pin',1,2,1,'unix:/tmp/vnc.sock','vnc_password','vnc-user')""")
        }
        helper.runMigrationsAndValidate(name, 32, true).use { db ->
            db.query("SELECT screen_endpoint,screen_auth,screen_username,screen_protocol,rdp_username,rdp_certificate_sha256,trusted_host_key_sha256 FROM remote_hosts").use {
                assertTrue(it.moveToFirst())
                assertEquals("unix:/tmp/vnc.sock", it.getString(0)); assertEquals("vnc_password", it.getString(1))
                assertEquals("vnc-user", it.getString(2)); assertEquals("auto", it.getString(3))
                assertEquals("", it.getString(4)); assertTrue(it.isNull(5)); assertEquals("ssh-pin", it.getString(6))
            }
            db.execSQL("UPDATE remote_hosts SET screen_protocol='rdp',rdp_username='miffan-1001',rdp_certificate_sha256='${"ab".repeat(32)}'")
            db.query("SELECT rdp_certificate_sha256 FROM remote_hosts").use {
                assertTrue(it.moveToFirst()); assertEquals("ab".repeat(32), it.getString(0))
            }
        }
    }
    @Test fun unrelatedStaleUpdatesCannotUndoConfirmedCertificate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.remoteHostDao()
            val stale = RemoteHostEntity("pin-test", "name", "localhost", 22, "user", "key",
                trustedHostKeySha256 = "ssh-pin", createdAt = 1, updatedAt = 1)
            dao.insert(stale)
            val pin = "ab".repeat(32)
            dao.pinRdpCertificate(stale.id, pin, 2)
            dao.updateConnection(stale.copy(name = "renamed", updatedAt = 3))
            dao.updateScreenConfig(stale.id, true, "helper", "none", "", "rdp", null, 4)
            dao.updateDetectedScreen(stale.id, "linux", "miffan-1001")
            assertEquals(pin, dao.getById(stale.id)?.rdpCertificateSha256)
            assertEquals("renamed", dao.getById(stale.id)?.name)
        } finally { db.close() }
    }

}
