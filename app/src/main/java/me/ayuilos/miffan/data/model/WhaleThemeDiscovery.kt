package me.ayuilos.miffan.data.model

import kotlinx.serialization.Serializable
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID
import kotlin.uuid.Uuid

@Serializable
data class WhaleThemeDiscovery(
    val introPending: Boolean = false,
    val settingsSeen: Boolean = false,
    val discoveredAtMillis: Long = 0,
    val previousAppearance: WhaleThemeAppearanceBackup? = null,
    val dedicatedAssistantId: Uuid? = null,
)

@Serializable
data class WhaleThemeAppearanceBackup(
    val themeId: String,
    val dynamicColor: Boolean,
    // Legacy fields remain readable; restoring a palette never changes assistants.
    val assistantId: Uuid? = null,
    val avatar: Avatar? = null,
    val useAssistantAvatar: Boolean = false,
)

/** Called once when the independent discovery preference is first created. */
fun initialWhaleThemeDiscovery(
    launchCount: Int,
    nowMillis: Long,
    hasSavedProviders: Boolean = false,
): WhaleThemeDiscovery =
    if (launchCount > 0 || hasSavedProviders) {
        WhaleThemeDiscovery(introPending = true, discoveredAtMillis = nowMillis)
    } else {
        // A fresh installation learns about the character during onboarding.
        WhaleThemeDiscovery(settingsSeen = true)
    }

fun Settings.dismissWhaleThemeIntroduction(): Settings = copy(
    whaleThemeDiscovery = whaleThemeDiscovery.copy(introPending = false),
)

fun Settings.markWhaleThemeSeen(): Settings = copy(
    whaleThemeDiscovery = whaleThemeDiscovery.copy(introPending = false, settingsSeen = true),
)

fun Settings.hasNewWhaleTheme(nowMillis: Long): Boolean {
    val discovered = whaleThemeDiscovery.discoveredAtMillis
    return !whaleThemeDiscovery.settingsSeen && discovered > 0 && themeId != WHALE_THEME_ID &&
        nowMillis >= discovered && nowMillis - discovered < 30L * 24 * 60 * 60 * 1_000
}

fun Settings.withWhaleThemeTrial(): Settings {
    val existing = assistants.firstOrNull { it.id == whaleThemeDiscovery.dedicatedAssistantId }
    val whale = existing ?: createWhaleAssistant()
    val backup = whaleThemeDiscovery.previousAppearance ?: WhaleThemeAppearanceBackup(
        themeId = themeId,
        dynamicColor = dynamicColor,
    )
    return copy(
        themeId = WHALE_THEME_ID,
        dynamicColor = false,
        assistantId = whale.id,
        assistants = if (existing == null) assistants + whale else assistants,
        whaleThemeDiscovery = whaleThemeDiscovery.copy(
            introPending = false,
            settingsSeen = true,
            previousAppearance = backup,
            dedicatedAssistantId = whale.id,
        ),
    )
}

fun Settings.restoreWhaleThemeTrial(): Settings {
    val backup = whaleThemeDiscovery.previousAppearance ?: return this
    return copy(
        themeId = backup.themeId,
        dynamicColor = backup.dynamicColor,
        whaleThemeDiscovery = whaleThemeDiscovery.copy(previousAppearance = null),
    )
}
