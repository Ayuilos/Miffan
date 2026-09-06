package me.ayuilos.miffan.ui.pages.onboarding

import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.ui.theme.PresetThemes
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID

/** The first-run shortcut changes appearance only; provider configuration stays untouched. */
internal fun Settings.withOnboardingWhaleTheme(enabled: Boolean): Settings = copy(
    themeId = if (enabled) WHALE_THEME_ID else PresetThemes.first().id,
    dynamicColor = !enabled,
    assistants = assistants.map { assistant ->
        if (assistant.id != assistantId) return@map assistant
        when {
            enabled && (assistant.avatar is Avatar.Miffan || assistant.avatar is Avatar.Dummy ||
                assistant.avatar is Avatar.WhaleGirl) -> assistant.copy(
                avatar = assistant.avatar as? Avatar.WhaleGirl ?: Avatar.WhaleGirl(),
                useAssistantAvatar = true,
            )
            !enabled && assistant.avatar is Avatar.WhaleGirl -> assistant.copy(avatar = Avatar.Miffan())
            else -> assistant
        }
    },
)
