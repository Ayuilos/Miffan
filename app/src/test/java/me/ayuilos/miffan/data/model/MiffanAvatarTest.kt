package me.ayuilos.miffan.data.model

import kotlinx.serialization.encodeToString
import me.ayuilos.miffan.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiffanAvatarTest {
    @Test
    fun everyPresetRoundTrips() {
        MiffanPalette.entries.forEach { palette ->
            val original: Avatar = Avatar.Miffan(
                appearance = MiffanAppearance(palette = palette),
            )

            val encoded = JsonInstant.encodeToString(original)
            val decoded = JsonInstant.decodeFromString<Avatar>(encoded)

            assertEquals(original, decoded)
            assertTrue(encoded.contains("\"type\":\"miffan\""))
        }
    }

    @Test
    fun legacyDummyResolvesToClassicWithoutChangingStoredValue() {
        assertTrue(Avatar.Dummy.isMiffanAvatar())
        assertEquals(MiffanPalette.CLASSIC, Avatar.Dummy.miffanAppearanceOrDefault().palette)
    }

    @Test
    fun miffanWithoutAppearanceUsesClassicDefaults() {
        val decoded = JsonInstant.decodeFromString<Avatar>("""{"type":"miffan"}""")

        assertEquals(Avatar.Miffan(), decoded)
    }

    @Test
    fun newAssistantDefaultsToExplicitMiffan() {
        assertEquals(Avatar.Miffan(), Assistant().avatar)
    }
}
