package me.ayuilos.miffan.data.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.ayuilos.miffan.data.model.ChatGroup
import me.ayuilos.miffan.data.model.ChatOrganization
import kotlin.uuid.Uuid
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.withoutProviderSecrets
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
    @Test
    fun `chat organization survives shared local WebDAV and S3 backup codec and migrations`() {
        val chat = Uuid.random()
        val organization = ChatOrganization(
            pinned = listOf(chat),
            groups = listOf(ChatGroup(name = "伙伴", chatIds = listOf(chat)), ChatGroup(name = "Empty")),
            legacyPinsMigrated = true,
        )
        val encoded = JsonInstant.encodeBackupSettings(Settings(chatOrganization = organization).withoutProviderSecrets(), 137)
        val migrated = SettingsJsonMigrator.migrate(encoded)
        assertEquals(organization, JsonInstant.decodeFromString<Settings>(migrated).chatOrganization)
        assertEquals(137, JsonInstant.backupLaunchCount(migrated))
        assertFalse(encoded.contains("collapsedGroups"))
    }

    @Test
    fun `legacy backups without chat organization restore defaults`() {
        val encoded = JsonInstant.encodeBackupSettings(Settings(), 137)
        val legacy = JsonObject(JsonInstant.parseToJsonElement(encoded).jsonObject - "chatOrganization").toString()
        val restored = JsonInstant.decodeFromString<Settings>(SettingsJsonMigrator.migrate(legacy))
        assertEquals(ChatOrganization(), restored.chatOrganization)
    }
}
