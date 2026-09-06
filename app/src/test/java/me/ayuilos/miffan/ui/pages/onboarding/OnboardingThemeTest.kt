package me.ayuilos.miffan.ui.pages.onboarding

import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.MiffanMotionProfile
import me.ayuilos.miffan.data.model.createWhaleAssistant
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingThemeTest {
    @Test
    fun enablingCreatesAndSelectsIndependentAssistantWithoutChangingExistingConfiguration() {
        listOf(Avatar.Miffan(), Avatar.Dummy, Avatar.Image("content://test/avatar"), Avatar.Emoji("🐳")).forEach { avatar ->
            val before = configuredSettings(avatar)
            val after = before.withOnboardingWhaleTheme(true)
            assertEquals(WHALE_THEME_ID, after.themeId)
            assertFalse(after.dynamicColor)
            val whale = after.assistants.last()
            assertEquals(createWhaleAssistant(whale.id), whale)
            assertEquals(whale.id, after.assistantId)
            assertEquals(whale.id, after.whaleThemeDiscovery.dedicatedAssistantId)
            assertEquals(before.assistants, after.assistants.dropLast(1))
            assertEquals(before, after.copy(themeId = before.themeId, dynamicColor = before.dynamicColor,
                assistantId = before.assistantId, assistants = before.assistants,
                whaleThemeDiscovery = before.whaleThemeDiscovery))
        }
    }

    @Test
    fun disablingOnlyRestoresPreviousPaletteAndKeepsDedicatedAssistant() {
        val before = configuredSettings(Avatar.Miffan()).copy(themeId = "my-theme", dynamicColor = false)
        val enabled = before.withOnboardingWhaleTheme(true)
        val restored = enabled.withOnboardingWhaleTheme(false)
        assertEquals(before.themeId, restored.themeId)
        assertEquals(before.dynamicColor, restored.dynamicColor)
        assertEquals(enabled.assistants, restored.assistants)
        assertEquals(enabled.assistantId, restored.assistantId)
        assertEquals(enabled.whaleThemeDiscovery.dedicatedAssistantId, restored.whaleThemeDiscovery.dedicatedAssistantId)
        assertEquals(before.assistants, restored.assistants.dropLast(1))
        assertEquals(restored, restored.withOnboardingWhaleTheme(false))
    }

    @Test
    fun togglingOnAgainPreservesDedicatedAssistantEditsAndDoesNotCreateDuplicates() {
        val before = configuredSettings(Avatar.WhaleGirl(MiffanMotionProfile.CALM))
        val enabled = before.withOnboardingWhaleTheme(true)
        val edited = enabled.copy(assistants = enabled.assistants.map {
            if (it.id == enabled.assistantId) it.copy(name = "My name", avatar = Avatar.Emoji("🌱"), systemPrompt = "My prompt") else it
        })
        val reenabled = edited.withOnboardingWhaleTheme(false).withOnboardingWhaleTheme(true)
        assertEquals(edited.assistants, reenabled.assistants)
        assertEquals(edited.assistantId, reenabled.assistantId)
        assertEquals(reenabled, reenabled.withOnboardingWhaleTheme(true))
        assertEquals(before.assistants, reenabled.assistants.dropLast(1))
    }

    @Test
    fun turningOffBeforeAdoptionNeverCreatesOrModifiesAnAssistant() {
        val before = configuredSettings(Avatar.Emoji("🐳"))
        assertEquals(before, before.withOnboardingWhaleTheme(false))
        assertTrue(before.whaleThemeDiscovery.dedicatedAssistantId == null)
    }

    private fun configuredSettings(avatar: Avatar): Settings {
        val model = Model(modelId = "test-chat-model", displayName = "Existing model")
        val assistant = Assistant(name = "Existing assistant", avatar = avatar, chatModelId = model.id,
            systemPrompt = "Preserve my system prompt", temperature = 0.35f, enableMemory = true)
        return Settings(
            providers = listOf(ProviderSetting.OpenAI(name = "Existing provider", apiKey = "test-key",
                baseUrl = "https://example.invalid/v1", models = listOf(model))),
            assistantId = assistant.id,
            assistants = listOf(assistant,
                Assistant(avatar = Avatar.Image("content://test/other-avatar"), systemPrompt = "Other prompt"),
                Assistant(avatar = Avatar.WhaleGirl(MiffanMotionProfile.LIVELY))),
            chatModelId = model.id, fastModelId = model.id, titleModelId = model.id,
            titlePrompt = "My title instructions", translatePrompt = "My translation instructions",
        ).let { it.copy(displaySetting = it.displaySetting.copy(userAvatar = Avatar.Emoji("🌱"))) }
    }
}
