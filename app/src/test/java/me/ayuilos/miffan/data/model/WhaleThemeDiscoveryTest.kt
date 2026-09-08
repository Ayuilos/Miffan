package me.ayuilos.miffan.data.model

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.WhaleThemeDiscoveryMigration
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleThemeDiscoveryTest {
    private val now = 1_800_000_000_000L

    @Test
    fun existingUsersDiscoverOnceWhileNewInstallationsAreAlreadyIntroduced() {
        assertEquals(WhaleThemeDiscovery(introPending = true, discoveredAtMillis = now),
            initialWhaleThemeDiscovery(7, now))
        assertEquals(WhaleThemeDiscovery(settingsSeen = true), initialWhaleThemeDiscovery(0, now))
        assertEquals(WhaleThemeDiscovery(settingsSeen = true), initialWhaleThemeDiscovery(-1, now))
        // Older releases may have saved providers before the launch counter existed.
        assertTrue(initialWhaleThemeDiscovery(0, now, hasSavedProviders = true).introPending)
    }

    @Test
    fun missingPreferenceMigrationPersistsTheOriginalDiscoveryTimeAndNeverResetsIt() = runBlocking {
        val migration = WhaleThemeDiscoveryMigration { now }
        val original = preferencesOf(SettingsStore.LAUNCH_COUNT to 8, SettingsStore.THEME_ID to "original")
        assertTrue(migration.shouldMigrate(original))
        val migrated = migration.migrate(original)
        val discovery = JsonInstant.decodeFromString<WhaleThemeDiscovery>(
            migrated[SettingsStore.WHALE_THEME_DISCOVERY]!!)
        assertEquals(initialWhaleThemeDiscovery(8, now), discovery)
        assertEquals(8, migrated[SettingsStore.LAUNCH_COUNT])
        assertEquals("original", migrated[SettingsStore.THEME_ID])
        val nextLaunch = WhaleThemeDiscoveryMigration { now + 86_400_000L }
        assertFalse(nextLaunch.shouldMigrate(migrated))
        assertEquals(migrated, nextLaunch.migrate(migrated))
        val fresh = migration.migrate(emptyPreferences())
        assertEquals(WhaleThemeDiscovery(settingsSeen = true),
            JsonInstant.decodeFromString<WhaleThemeDiscovery>(fresh[SettingsStore.WHALE_THEME_DISCOVERY]!!))
        val legacy = migration.migrate(preferencesOf(SettingsStore.PROVIDERS to "[]"))
        assertTrue(JsonInstant.decodeFromString<WhaleThemeDiscovery>(
            legacy[SettingsStore.WHALE_THEME_DISCOVERY]!!).introPending)
    }

    @Test
    fun confirmationCreatesAndSelectsIndependentAssistantWithoutChangingExistingSettings() {
        for (avatar in listOf(Avatar.Miffan(), Avatar.Dummy, Avatar.Emoji("🐳"), Avatar.Image("content://test/avatar"))) {
            val before = configuredSettings(avatar)
            assertNull(before.whaleThemeDiscovery.dedicatedAssistantId)
            assertEquals(before.assistants, before.dismissWhaleThemeIntroduction().assistants)
            assertEquals(before.assistants, before.markWhaleThemeSeen().assistants)
            val trial = before.withWhaleThemeTrial()
            val whale = trial.assistants.last()
            assertEquals(createWhaleAssistant(whale.id), whale)
            assertEquals(whale.id, trial.assistantId)
            assertEquals(whale.id, trial.whaleThemeDiscovery.dedicatedAssistantId)
            assertEquals(before.assistants, trial.assistants.dropLast(1))
            assertEquals(WHALE_THEME_ID, trial.themeId)
            assertFalse(trial.dynamicColor)
            assertEquals(before, trial.copy(themeId = before.themeId, dynamicColor = before.dynamicColor,
                assistantId = before.assistantId, assistants = before.assistants,
                whaleThemeDiscovery = before.whaleThemeDiscovery))
        }
    }

    @Test
    fun repeatedTrialReusesDedicatedIdentityAndPreservesAllUserEdits() {
        val before = configuredSettings(Avatar.Miffan())
        val trial = before.withWhaleThemeTrial()
        val id = trial.assistantId
        val editedWhale = trial.assistants.last().copy(name = "Renamed", avatar = Avatar.Emoji("🌱"),
            systemPrompt = "My prompt", temperature = 0.8f, chatModelId = before.chatModelId)
        val edited = trial.copy(assistantId = before.assistantId,
            assistants = trial.assistants.dropLast(1) + editedWhale)
        val retried = edited.withWhaleThemeTrial()
        assertEquals(id, retried.assistantId)
        assertEquals(edited.assistants, retried.assistants)
        assertEquals(trial.whaleThemeDiscovery.previousAppearance, retried.whaleThemeDiscovery.previousAppearance)
        assertEquals(retried, retried.withWhaleThemeTrial())
    }

    @Test
    fun deletionCreatesANewDedicatedAssistantWithoutAdoptingLookalikes() {
        val trial = configuredSettings(Avatar.Miffan()).withWhaleThemeTrial()
        val lookalike = createWhaleAssistant()
        val deleted = trial.copy(assistants = trial.assistants.dropLast(1) + lookalike)
        val recreated = deleted.withWhaleThemeTrial()
        assertEquals(deleted.assistants, recreated.assistants.dropLast(1))
        assertTrue(recreated.assistantId != trial.assistantId)
        assertTrue(recreated.assistantId != lookalike.id)
        assertEquals(recreated.assistantId, recreated.whaleThemeDiscovery.dedicatedAssistantId)
    }

    @Test
    fun restoreOnlyChangesPaletteAndKeepsThePersistentAssistantAndSelection() {
        val before = configuredSettings(Avatar.Miffan())
        val trial = before.withWhaleThemeTrial()
        val restored = trial.restoreWhaleThemeTrial()
        assertEquals(before.themeId, restored.themeId)
        assertEquals(before.dynamicColor, restored.dynamicColor)
        assertEquals(trial.assistants, restored.assistants)
        assertEquals(trial.assistantId, restored.assistantId)
        assertEquals(trial.whaleThemeDiscovery.dedicatedAssistantId, restored.whaleThemeDiscovery.dedicatedAssistantId)
        assertNull(restored.whaleThemeDiscovery.previousAppearance)
        assertEquals(restored, restored.restoreWhaleThemeTrial())
        assertEquals(trial.assistants, restored.withWhaleThemeTrial().assistants)
        // Old serialized avatar backups also never modify an existing assistant on restore.
        val legacy = trial.copy(whaleThemeDiscovery = trial.whaleThemeDiscovery.copy(
            previousAppearance = WhaleThemeAppearanceBackup("old", true, trial.assistantId, Avatar.Miffan(), false)))
        assertEquals(trial.assistants, legacy.restoreWhaleThemeTrial().assistants)
    }

    @Test
    fun resettingPresetRestoresAllConfigurationAndRetainsStableHistoryIdentity() {
        val preset = createWhaleAssistant()
        val modified = preset.copy(name = "Custom", avatar = Avatar.Emoji("🐟"), useAssistantAvatar = false,
            systemPrompt = "Custom prompt", chatModelId = kotlin.uuid.Uuid.random(), temperature = 1.2f,
            topP = 0.2f, streamOutput = false, enableMemory = true, useGlobalMemory = true,
            enableWebSearch = true, background = "custom", workspaceId = kotlin.uuid.Uuid.random(),
            enableRecentChatsReference = true, maxTokens = 12, tags = listOf(kotlin.uuid.Uuid.random()))
        assertEquals(preset, createWhaleAssistant(modified.id))
        assertNull(preset.chatModelId)
        assertTrue(preset.systemPrompt.contains("白饭"))
        assertTrue(preset.systemPrompt.contains("同一个乐观"))
        assertTrue(preset.useAssistantAvatar)
    }

    @Test
    fun dismissKeepsTheSettingsBadgeAndSeeingSettingsClearsIt() {
        val before = configuredSettings(Avatar.Miffan())
        val dismissed = before.dismissWhaleThemeIntroduction()
        assertFalse(dismissed.whaleThemeDiscovery.introPending)
        assertTrue(dismissed.hasNewWhaleTheme(now))
        assertEquals(before.copy(whaleThemeDiscovery = before.whaleThemeDiscovery.copy(introPending = false)), dismissed)
        assertFalse(dismissed.markWhaleThemeSeen().hasNewWhaleTheme(now))
        assertFalse(before.withWhaleThemeTrial().whaleThemeDiscovery.introPending)
        assertFalse(before.withWhaleThemeTrial().hasNewWhaleTheme(now))
    }

    @Test
    fun badgeExpiresAtThirtyDaysAndDoesNotAppearForNewOrAlreadyWhaleUsers() {
        val oldUser = configuredSettings(Avatar.Miffan())
        val thirtyDays = 30L * 24 * 60 * 60 * 1_000
        assertTrue(oldUser.hasNewWhaleTheme(now + thirtyDays - 1))
        assertFalse(oldUser.hasNewWhaleTheme(now + thirtyDays))
        assertFalse(oldUser.hasNewWhaleTheme(now - 1))
        assertFalse(oldUser.copy(themeId = WHALE_THEME_ID).hasNewWhaleTheme(now))
        assertFalse(oldUser.copy(whaleThemeDiscovery = initialWhaleThemeDiscovery(0, now)).hasNewWhaleTheme(now))
        assertFalse(oldUser.copy(whaleThemeDiscovery = WhaleThemeDiscovery()).hasNewWhaleTheme(now))
    }

    @Test
    fun discoveryAndAppearanceBackupRoundTripInSettingsSerialization() {
        val before = configuredSettings(Avatar.Miffan(
            MiffanAppearance(palette = MiffanPalette.INK_JADE), MiffanMotionProfile.CALM))
        val trial = before.withWhaleThemeTrial()
        val decoded = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(trial))
        assertEquals(trial.whaleThemeDiscovery, decoded.whaleThemeDiscovery)
        assertEquals(trial.assistants, decoded.restoreWhaleThemeTrial().assistants)
        assertEquals(trial.assistantId, decoded.assistantId)
        assertEquals(trial.assistants, decoded.withWhaleThemeTrial().assistants)
        assertEquals(before.themeId, decoded.restoreWhaleThemeTrial().themeId)
        assertEquals(WhaleThemeDiscovery(), JsonInstant.decodeFromString<WhaleThemeDiscovery>("{}"))
    }

    private fun configuredSettings(avatar: Avatar): Settings {
        val model = Model(modelId = "existing-model")
        val current = Assistant(name = "Current", avatar = avatar, systemPrompt = "Original prompt",
            temperature = 0.35f, chatModelId = model.id, enableMemory = true)
        return Settings(themeId = "custom-original", dynamicColor = true,
            assistantId = current.id, assistants = listOf(current, Assistant(name = "Other", avatar = Avatar.Miffan())),
            chatModelId = model.id, fastModelId = model.id, titlePrompt = "Keep title prompt",
            providers = listOf(ProviderSetting.OpenAI(name = "Existing provider", apiKey = "test-key",
                baseUrl = "https://example.invalid/v1", models = listOf(model))),
            whaleThemeDiscovery = initialWhaleThemeDiscovery(12, now))
    }
}
