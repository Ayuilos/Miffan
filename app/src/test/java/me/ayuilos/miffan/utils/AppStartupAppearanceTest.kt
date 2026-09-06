package me.ayuilos.miffan.utils

import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStartupAppearanceTest {
    @Test
    fun whaleThemeUsesWhaleEvenWhenTheIndependentLauncherIsOriginal() {
        assertEquals(AppStartupAppearance.WHALE_SYSTEM,
            resolveAppStartupAppearance(true, LauncherIcon.MIFFAN, ColorMode.SYSTEM))
        assertEquals(AppStartupAppearance.WHALE_LIGHT,
            resolveAppStartupAppearance(true, LauncherIcon.MIFFAN, ColorMode.LIGHT))
        assertEquals(AppStartupAppearance.WHALE_DARK,
            resolveAppStartupAppearance(true, LauncherIcon.MIFFAN, ColorMode.DARK))
    }

    @Test
    fun launcherChoiceWorksWithoutTheWhaleThemeAndDeepSeaKeepsItsDarkBackground() {
        assertEquals(AppStartupAppearance.WHALE_SYSTEM,
            resolveAppStartupAppearance(false, LauncherIcon.WHALE_GIRL, ColorMode.SYSTEM))
        assertEquals(AppStartupAppearance.WHALE_DARK,
            resolveAppStartupAppearance(false, LauncherIcon.WHALE_GIRL, ColorMode.DARK))
        ColorMode.entries.forEach { mode ->
            assertEquals(AppStartupAppearance.WHALE_DARK,
                resolveAppStartupAppearance(false, LauncherIcon.WHALE_GIRL_DEEP_SEA, mode))
        }
    }

    @Test
    fun disabledWhaleThemeReturnsToTheOriginalAppearanceAndRespectsColorMode() {
        assertTrue(usesWhaleStartupTheme(WHALE_THEME_ID, false))
        assertFalse(usesWhaleStartupTheme(WHALE_THEME_ID, true))
        assertFalse(usesWhaleStartupTheme("ocean", false))
        assertEquals(AppStartupAppearance.MIFFAN_SYSTEM,
            resolveAppStartupAppearance(false, LauncherIcon.MIFFAN, ColorMode.SYSTEM))
        assertEquals(AppStartupAppearance.MIFFAN_LIGHT,
            resolveAppStartupAppearance(false, LauncherIcon.MIFFAN, ColorMode.LIGHT))
        assertEquals(AppStartupAppearance.MIFFAN_DARK,
            resolveAppStartupAppearance(false, null, ColorMode.DARK))
    }
}
