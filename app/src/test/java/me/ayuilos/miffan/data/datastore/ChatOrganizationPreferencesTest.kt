package me.ayuilos.miffan.data.datastore

import me.ayuilos.miffan.data.model.ChatGroup
import me.ayuilos.miffan.data.model.ChatOrganization
import me.ayuilos.miffan.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ChatOrganizationPreferencesTest {
    @Test
    fun organizationJsonRoundTripPreservesOrderAndMembership() {
        val a = Uuid.random()
        val b = Uuid.random()
        val organization = ChatOrganization(
            pinned = listOf(b, a),
            groups = listOf(ChatGroup(name = "伙伴", chatIds = listOf(a)), ChatGroup(name = "Empty")),
            legacyPinsMigrated = true,
        )
        assertEquals(organization, decodeChatOrganization(JsonInstant.encodeToString(organization)))
    }

    @Test
    fun missingPreferenceAndMissingJsonFieldsUseDefaults() {
        assertEquals(ChatOrganization(), decodeChatOrganization(null))
        assertEquals(ChatOrganization(), decodeChatOrganization("{}"))
        assertEquals(ChatOrganization(), JsonInstant.decodeFromString<Settings>("{}").chatOrganization)
    }

    @Test
    fun damagedJsonIncludingInvalidUuidFallsBackWithoutThrowing() {
        listOf("", "not json", "{", "null", "[]", "{\"pinned\":[\"bad-id\"]}",
            "{\"groups\":[{\"name\":\"Bad\",\"id\":\"invalid\"}]}").forEach {
            assertEquals(ChatOrganization(), decodeChatOrganization(it))
        }
    }

    @Test
    fun settingsJsonIncludesOrganization() {
        val organization = ChatOrganization().pin(Uuid.random()).createGroup("Group").copy(legacyPinsMigrated = true)
        val settings = Settings(chatOrganization = organization)
        assertEquals(organization, JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(settings)).chatOrganization)
        assertEquals("chat_organization", SettingsStore.CHAT_ORGANIZATION.name)
    }
}
