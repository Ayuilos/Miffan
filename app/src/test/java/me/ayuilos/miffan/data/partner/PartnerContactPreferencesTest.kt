package me.ayuilos.miffan.data.partner

import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.PartnerPair
import me.ayuilos.miffan.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class PartnerContactPreferencesTest {
    @Test fun settingsBackupRoundTripKeepsGrantsAndOldBackupsDefaultToNone() {
        val grants = listOf(PartnerPair.of(Uuid.random(), Uuid.random()))
        val settings = Settings(partnerContactGrants = grants)
        assertEquals(grants, JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(settings)).partnerContactGrants)
        assertTrue(JsonInstant.decodeFromString<Settings>("{}").partnerContactGrants.isEmpty())
        assertEquals("partner_contact_grants", SettingsStore.PARTNER_CONTACT_GRANTS.name)
    }
}
