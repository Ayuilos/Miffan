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
class Migration_32_33_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun existingRdpAndVncSettingsSurviveAndStreamDefaultsOff() {
        val name = "migration-32-33"
        helper.createDatabase(name, 32).use { db ->
            db.execSQL("""INSERT INTO remote_hosts (id,name,host,port,username,auth_type,
                trusted_host_key_sha256,created_at,updated_at,screen_enabled,screen_endpoint,screen_auth,screen_username,
                screen_protocol,rdp_username,rdp_certificate_sha256)
                VALUES ('host','existing','localhost',22,'user','key','ssh-pin',1,2,1,'helper','vnc_password','vnc-user',
                'rdp','rdp-user','${"ab".repeat(32)}')""")
        }
        helper.runMigrationsAndValidate(name, 33, true).use { db ->
            db.query("SELECT stream_enabled,stream_certificate_sha256,screen_protocol,rdp_username,rdp_certificate_sha256,screen_auth,trusted_host_key_sha256 FROM remote_hosts").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertTrue(it.isNull(1))
                assertEquals("rdp", it.getString(2)); assertEquals("rdp-user", it.getString(3))
                assertEquals("ab".repeat(32), it.getString(4)); assertEquals("vnc_password", it.getString(5)); assertEquals("ssh-pin", it.getString(6))
            }
        }
    }
    @Test fun pairedPinIsConditionalAndStaleEditsPreserveStreamSettings() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val dao = db.remoteHostDao()
            val host = RemoteHostEntity("stream-pin", "existing", "localhost", 22, "user", "key",
                trustedHostKeySha256 = "ssh-pin", createdAt = 1, updatedAt = 1)
            dao.insert(host)
            val pin = "ab".repeat(32)
            assertEquals(0, dao.pinPairedStreamCertificate(host.id, pin, "changed", null, 2))
            assertEquals(1, dao.pinPairedStreamCertificate(host.id, pin, host.connectionRevision, null, 2))
            assertEquals(0, dao.pinPairedStreamCertificate(host.id, "cd".repeat(32), host.connectionRevision, null, 3))
            dao.updateScreenConfig(host.id, true, "helper", "none", "", "auto", null, 4, true)
            dao.updateConnection(host.copy(name = "renamed", updatedAt = 5))
            dao.updateDetectedScreen(host.id, "linux", null)
            assertTrue(dao.getById(host.id)!!.streamEnabled)
            assertEquals(pin, dao.getById(host.id)!!.streamCertificateSha256)
            val beforeToggle = dao.getById(host.id)!!
            assertEquals(0, dao.setStreamEnabled("missing-host", true, 6))
            assertEquals(1, dao.setStreamEnabled(host.id, false, 6))
            assertEquals(beforeToggle.copy(streamEnabled = false, updatedAt = 6), dao.getById(host.id))
            assertEquals(1, dao.setStreamEnabled(host.id, true, 7))
            // Editing VNC/RDP settings must preserve the separately saved streaming preference.
            dao.updateScreenConfig(host.id, false, "helper", "none", "vnc-user", "vnc", null, 8)
            assertTrue(dao.getById(host.id)!!.streamEnabled)
            assertEquals(pin, dao.getById(host.id)!!.streamCertificateSha256)
            dao.pinStreamCertificate(host.id, "ef".repeat(32), 6)
            assertEquals(0, dao.pinPairedStreamCertificate(host.id, pin, host.connectionRevision, pin, 7))
        } finally { db.close() }
    }
}
