package me.ayuilos.miffan.data.model

import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID
import org.junit.Assert.assertEquals
import org.junit.Test

class AppCharacterTest {
    private val bowl = Avatar.Miffan(MiffanAppearance(colorSource = MiffanColorSource.APP_THEME))

    @Test
    fun onlyTheAppliedRoleThemeTurnsTheAppCharacterIntoTheWhale() {
        assertEquals(Avatar.WhaleGirl(), appCharacterAvatar(WHALE_THEME_ID, dynamicColor = false))
        // Wallpaper colours replace the role theme, so the bowl is back.
        assertEquals(bowl, appCharacterAvatar(WHALE_THEME_ID, dynamicColor = true))
    }

    @Test
    fun otherThemesKeepTheBowlInTheAppsOwnColours() {
        assertEquals(bowl, appCharacterAvatar("default", dynamicColor = false))
        assertEquals(bowl, appCharacterAvatar("default", dynamicColor = true))
    }
}
