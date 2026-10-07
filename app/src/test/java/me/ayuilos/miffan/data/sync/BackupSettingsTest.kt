package me.ayuilos.miffan.data.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.migration.SettingsJsonMigrator
import me.ayuilos.miffan.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupSettingsTest {
    @Test
    fun `launch count survives backup migration and decode`() {
        val encoded = JsonInstant.encodeBackupSettings(Settings(miffanHelpEnabled = false), 137)
        val migrated = SettingsJsonMigrator.migrate(encoded)

        assertEquals(137, JsonInstant.backupLaunchCount(migrated))
        assertFalse(JsonInstant.decodeFromString<Settings>(migrated).miffanHelpEnabled)
    }

    @Test
    fun `older backups without launch count restore zero`() {
        val encoded = JsonInstant.encodeBackupSettings(Settings(), 137)
        val legacy = JsonObject(JsonInstant.parseToJsonElement(encoded).jsonObject - "launchCount").toString()

        assertEquals(0, JsonInstant.backupLaunchCount(SettingsJsonMigrator.migrate(legacy)))
    }
}
