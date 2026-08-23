package me.ayuilos.miffan.data.model

import kotlinx.serialization.encodeToString
import me.ayuilos.miffan.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiffanAvatarTest {
    @Test
    fun everyAppearanceAndMotionCombinationRoundTrips() {
        MiffanKind.entries.forEach { kind ->
            MiffanPalette.entries.forEach { palette ->
                MiffanColorSource.entries.forEach { colorSource ->
                    MiffanMotionProfile.entries.forEach { motionProfile ->
                        val original: Avatar = Avatar.Miffan(
                            appearance = MiffanAppearance(
                                palette = palette,
                                kind = kind,
                                colorSource = colorSource,
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
    }

    @Test
    fun legacyDummyResolvesToClassicWithoutChangingStoredValue() {
        assertTrue(Avatar.Dummy.isMiffanAvatar())
        assertEquals(MiffanPalette.CLASSIC, Avatar.Dummy.miffanAppearanceOrDefault().palette)
        assertEquals(MiffanColorSource.PALETTE, Avatar.Dummy.miffanAppearanceOrDefault().colorSource)
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
    fun miffanAppearanceWithoutColorSourceUsesPalette() {
        val decoded = JsonInstant.decodeFromString<Avatar>(
            """{"type":"miffan","appearance":{"palette":"moonlight","kind":"stargazer"}}""",
        )

        assertEquals(
            MiffanColorSource.PALETTE,
            (decoded as Avatar.Miffan).appearance.colorSource,
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
                colorSource = MiffanColorSource.APP_THEME,
            ),
            motionProfile = MiffanMotionProfile.CALM,
        )

        assertEquals(
            Avatar.Miffan(
                appearance = MiffanAppearance(
                    palette = MiffanPalette.SAKURA,
                    kind = MiffanKind.STARGAZER,
                    colorSource = MiffanColorSource.PALETTE,
                ),
                motionProfile = MiffanMotionProfile.CALM,
            ),
            original.withMiffanAppearance(
                MiffanAppearance(
                    palette = MiffanPalette.SAKURA,
                    kind = MiffanKind.STARGAZER,
                    colorSource = MiffanColorSource.PALETTE,
                ),
            ),
        )
        assertEquals(
            Avatar.Miffan(
                appearance = MiffanAppearance(
                    palette = MiffanPalette.MATCHA,
                    kind = MiffanKind.DUMPLING,
                    colorSource = MiffanColorSource.APP_THEME,
                ),
                motionProfile = MiffanMotionProfile.LIVELY,
            ),
            original.withMiffanMotionProfile(MiffanMotionProfile.LIVELY),
        )
    }

    @Test
    fun themeSyncToggleKeepsTheManualPaletteAndCharacterKind() {
        val manual = MiffanAppearance(
            palette = MiffanPalette.INK_JADE,
            kind = MiffanKind.SPROUT,
        )

        val restored = manual
            .copy(colorSource = MiffanColorSource.APP_THEME)
            .copy(colorSource = MiffanColorSource.PALETTE)

        assertEquals(manual, restored)
    }
}
