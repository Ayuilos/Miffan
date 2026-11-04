package me.ayuilos.miffan.data.model

import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID

/**
 * The character that speaks for the app itself (update pages, app-level loading), as opposed to a
 * partner's own avatar. Only the role theme decides it; the launcher icon stays a home-screen choice.
 * Without the role theme the bowl takes the app's colours so it matches whichever theme is in use.
 */
fun appCharacterAvatar(themeId: String, dynamicColor: Boolean): Avatar =
    if (themeId == WHALE_THEME_ID && !dynamicColor) Avatar.WhaleGirl()
    else Avatar.Miffan(MiffanAppearance(colorSource = MiffanColorSource.APP_THEME))

fun Settings.appCharacterAvatar(): Avatar = appCharacterAvatar(themeId, dynamicColor)
