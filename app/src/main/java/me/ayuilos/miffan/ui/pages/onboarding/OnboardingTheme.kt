package me.ayuilos.miffan.ui.pages.onboarding

import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.restoreWhaleThemeTrial
import me.ayuilos.miffan.data.model.withWhaleThemeTrial
import me.ayuilos.miffan.ui.theme.PresetThemes
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID

/** Turning the switch on is explicit adoption; turning it off only restores the palette. */
internal fun Settings.withOnboardingWhaleTheme(enabled: Boolean): Settings = when {
    enabled -> withWhaleThemeTrial()
    whaleThemeDiscovery.previousAppearance != null -> restoreWhaleThemeTrial()
    themeId == WHALE_THEME_ID -> copy(themeId = PresetThemes.first().id, dynamicColor = true)
    else -> this
}
