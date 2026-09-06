package me.ayuilos.miffan.ui.pages.onboarding

import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.MiffanMotionProfile
import me.ayuilos.miffan.ui.theme.PresetThemes
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingThemeTest {
    @Test
    fun enablingChangesOnlyAppearanceAndTheSelectedBuiltInCharacter() {
        listOf(Avatar.Miffan(), Avatar.Dummy).forEach { avatar ->
            val before = configuredSettings(avatar)
            val after = before.withOnboardingWhaleTheme(true)
            assertEquals(WHALE_THEME_ID, after.themeId)
            assertFalse(after.dynamicColor)
            val selected = after.assistants.first { it.id == before.assistantId }
            assertEquals(Avatar.WhaleGirl(), selected.avatar)
            assertTrue(selected.useAssistantAvatar)
            assertEquals(before.assistants.first().copy(avatar = selected.avatar, useAssistantAvatar = true), selected)
            // Whole-settings comparison also protects provider credentials, all model selections,
            // prompts, user avatars, and every assistant other than the selected character.
            assertEquals(before, after.copy(themeId = before.themeId, dynamicColor = before.dynamicColor,
                assistants = listOf(before.assistants.first()) + after.assistants.drop(1)))
        }
    }

    @Test
    fun customImageAndEmojiAvatarsSurviveBothSwitchDirections() {
        listOf(Avatar.Image("content://test/selected-avatar"), Avatar.Emoji("🐳")).forEach { avatar ->
            val before = configuredSettings(avatar)
            val enabled = before.withOnboardingWhaleTheme(true)
            val disabled = enabled.withOnboardingWhaleTheme(false)
            assertEquals(before.assistants, enabled.assistants)
            assertEquals(before.assistants, disabled.assistants)
            assertEquals(before, disabled)
        }
    }

    @Test
    fun disablingRestoresDefaultThemeAndBowlWithoutResettingConversationConfiguration() {
        val before = configuredSettings(Avatar.Miffan())
        val restored = before.withOnboardingWhaleTheme(true).withOnboardingWhaleTheme(false)
        assertEquals(PresetThemes.first().id, restored.themeId)
        assertTrue(restored.dynamicColor)
        assertEquals(Avatar.Miffan(), restored.assistants.first().avatar)
        assertEquals(before.copy(assistants = listOf(before.assistants.first().copy(useAssistantAvatar = true)) +
            before.assistants.drop(1)), restored)
        assertEquals(restored, restored.withOnboardingWhaleTheme(false))
    }

    @Test
    fun reapplyingKeepsLegacySerializedWhaleProfileAndIsIdempotent() {
        val before = configuredSettings(Avatar.WhaleGirl(MiffanMotionProfile.CALM))
        val enabled = before.withOnboardingWhaleTheme(true)
        assertEquals(before.assistants.first().avatar, enabled.assistants.first().avatar)
        assertEquals(enabled, enabled.withOnboardingWhaleTheme(true))
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
