package me.ayuilos.miffan.data.datastore.migration

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonMigratorTest {
    @Test
    fun `migrates RikkaHub and first Miffan avatar type names`() {
        val backupJson = """
            {
              "displaySetting": {
                "userAvatar": {
                  "type": "me.rerere.rikkahub.data.model.Avatar.Emoji",
                  "content": "rabbit"
                }
              },
              "assistants": [
                {
                  "avatar": {
                    "type": "me.rerere.rikkahub.data.model.Avatar.Dummy"
                  }
                },
                {
                  "avatar": {
                    "type": "me.ayuilos.miffan.data.model.Avatar.Image",
                    "url": "file:///avatar.png"
                  }
                }
              ]
            }
        """.trimIndent()

        val migrated = SettingsJsonMigrator.migrate(backupJson)
        val settings = JsonInstant.decodeFromString<Settings>(migrated)

        assertEquals(Avatar.Emoji("rabbit"), settings.displaySetting.userAvatar)
        assertEquals(Avatar.Dummy, settings.assistants[0].avatar)
        assertEquals(Avatar.Image("file:///avatar.png"), settings.assistants[1].avatar)
        assertFalse(migrated.contains("me.rerere.rikkahub.data.model.Avatar"))
        assertFalse(migrated.contains("me.ayuilos.miffan.data.model.Avatar"))
    }

    @Test
    fun `uses namespace independent avatar serial names`() {
        val encoded = JsonInstant.encodeToString<Avatar>(Avatar.Dummy)
        val element = Json.parseToJsonElement(encoded)

        assertTrue(element.toString().contains("\"type\":\"dummy\""))
        assertFalse(encoded.contains("me.ayuilos.miffan"))
    }
}
