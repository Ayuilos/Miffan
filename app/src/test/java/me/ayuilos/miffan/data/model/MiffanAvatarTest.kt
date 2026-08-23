package me.ayuilos.miffan.data.model

import kotlinx.serialization.encodeToString
import me.ayuilos.miffan.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiffanAvatarTest {
    @Test
    fun everyPresetRoundTrips() {
        MiffanKind.entries.forEach { kind ->
            MiffanPalette.entries.forEach { palette ->
                MiffanMotionProfile.entries.forEach { motionProfile ->
                    val original: Avatar = Avatar.Miffan(
                        appearance = MiffanAppearance(
                            palette = palette,
                            kind = kind,
                        ),
                        motionProfile = motionProfile,
                    )

                    val encoded = JsonInstant.encodeToString(original)
                    val decoded = JsonInstant.decodeFromString<Avatar>(encoded)

                    assertEquals(original, decoded)
                    assertTrue(encoded.contains("\"type\":\"miffan\""))
                }
            }
        }
    }

    @Test
    fun legacyDummyResolvesToClassicWithoutChangingStoredValue() {
        assertTrue(Avatar.Dummy.isMiffanAvatar())
        assertEquals(MiffanPalette.CLASSIC, Avatar.Dummy.miffanAppearanceOrDefault().palette)
        assertEquals(MiffanMotionProfile.CURIOUS, Avatar.Dummy.miffanMotionProfileOrDefault())
    }

    @Test
    fun miffanWithoutAppearanceUsesClassicDefaults() {
        val decoded = JsonInstant.decodeFromString<Avatar>("""{"type":"miffan"}""")

        assertEquals(Avatar.Miffan(), decoded)
    }

    @Test
    fun miffanAppearanceWithoutKindUsesRice() {
        val decoded = JsonInstant.decodeFromString<Avatar>(
            """{"type":"miffan","appearance":{"palette":"moonlight"}}""",
        )

        assertEquals(
            Avatar.Miffan(
                appearance = MiffanAppearance(
                    palette = MiffanPalette.MOONLIGHT,
                    kind = MiffanKind.RICE,
                ),
            ),
            decoded,
        )
    }

    @Test
    fun newAssistantDefaultsToExplicitMiffan() {
        assertEquals(Avatar.Miffan(), Assistant().avatar)
    }

    @Test
    fun appearanceAndMotionUpdatesPreserveTheOtherCharacterAxis() {
        val original: Avatar = Avatar.Miffan(
            appearance = MiffanAppearance(
                palette = MiffanPalette.MATCHA,
                kind = MiffanKind.DUMPLING,
            ),
            motionProfile = MiffanMotionProfile.CALM,
        )

        assertEquals(
            Avatar.Miffan(
                appearance = MiffanAppearance(
                    palette = MiffanPalette.SAKURA,
                    kind = MiffanKind.STARGAZER,
                ),
                motionProfile = MiffanMotionProfile.CALM,
            ),
            original.withMiffanAppearance(
                MiffanAppearance(
                    palette = MiffanPalette.SAKURA,
                    kind = MiffanKind.STARGAZER,
                ),
            ),
        )
        assertEquals(
            Avatar.Miffan(
                appearance = MiffanAppearance(
                    palette = MiffanPalette.MATCHA,
                    kind = MiffanKind.DUMPLING,
                ),
                motionProfile = MiffanMotionProfile.LIVELY,
            ),
            original.withMiffanMotionProfile(MiffanMotionProfile.LIVELY),
        )
    }
}
