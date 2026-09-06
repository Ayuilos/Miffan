package me.ayuilos.miffan.ui.components.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.isNotConfigured
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.WhaleThemeAppearanceBackup
import me.ayuilos.miffan.data.model.initialWhaleThemeDiscovery
import me.ayuilos.miffan.data.model.withWhaleThemeTrial
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.hooks.rememberUserSettingsState
import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.ui.theme.MiffanTheme
import me.ayuilos.miffan.ui.theme.PresetThemes
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID
import me.ayuilos.miffan.utils.LauncherIconManager
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class WhaleThemeDiscoveryHostTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun introductionIsClaimedDurablyAndNeverReturnsAfterDismissal() {
        withExistingUser { store, _ ->
            var generation by mutableStateOf(0)
            var eligible by mutableStateOf(true)
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                val settings by rememberUserSettingsState()
                MiffanTheme(colorMode = ColorMode.LIGHT) {
                    CompositionLocalProvider(LocalSettings provides settings) {
                        key(generation) { WhaleThemeDiscoveryHost(settings, store, eligible) }
                    }
                }
            }
            awaitIntroduction(store)
            assertFalse(runBlocking { store.settingsFlowRaw.first() }.whaleThemeDiscovery.introPending)
            // Rotation restores the already-open card, without creating a second campaign claim.
            restoration.emulateSavedInstanceStateRestore()
            settle()
            compose.onNodeWithText(TITLE).assertExists()
            compose.onNodeWithText("以后再说").performScrollTo().performClick()
            settle()
            compose.onNodeWithText(TITLE).assertDoesNotExist()
            assertFalse(runBlocking { store.settingsFlowRaw.first() }.whaleThemeDiscovery.settingsSeen)

            compose.runOnIdle { eligible = false }
            settle()
            compose.runOnIdle { eligible = true }
            settle()
            compose.onNodeWithText(TITLE).assertDoesNotExist()
            restoration.emulateSavedInstanceStateRestore()
            settle()
            compose.onNodeWithText(TITLE).assertDoesNotExist()
            // A completely fresh host must consult the durable claim, not only saved UI state.
            compose.runOnIdle { generation++ }
            settle()
            compose.onNodeWithText(TITLE).assertDoesNotExist()
        }
    }

    @Test
    fun anIneligibleRouteDoesNotConsumeThePendingIntroduction() {
        withExistingUser { store, _ ->
            var eligible by mutableStateOf(false)
            compose.setContent {
                val settings by rememberUserSettingsState()
                MiffanTheme(colorMode = ColorMode.LIGHT) {
                    CompositionLocalProvider(LocalSettings provides settings) {
                        WhaleThemeDiscoveryHost(settings, store, eligible)
                    }
                }
            }
            settle()
            compose.onNodeWithText(TITLE).assertDoesNotExist()
            assertTrue(runBlocking { store.settingsFlowRaw.first() }.whaleThemeDiscovery.introPending)
            compose.runOnIdle { eligible = true }
            awaitIntroduction(store)
        }
    }

    @Test
    fun tryingTheThemeCreatesAnIndependentAssistantAndPreservesExistingOnes() {
        withExistingUser { store, before ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = LauncherIconManager(context)
            val previousIcon = manager.selectedIcon()
            var generation by mutableStateOf(0)
            var openedChats = 0
            compose.setContent {
                val settings by rememberUserSettingsState()
                MiffanTheme(colorMode = ColorMode.LIGHT) {
                    CompositionLocalProvider(LocalSettings provides settings) {
                        key(generation) { WhaleThemeDiscoveryHost(settings, store, eligible = true, onExperienced = { openedChats++ }) }
                    }
                }
            }
            awaitIntroduction(store)
            compose.onNodeWithText("同时换上大肥鱼图标").assertIsOff()
            compose.onNodeWithText("立即体验").performScrollTo().performClick()
            compose.waitUntil(5_000) { store.settingsFlow.value.themeId == WHALE_THEME_ID }
            settle()
            val persisted = runBlocking { store.settingsFlowRaw.first() }
            assertEquals(WHALE_THEME_ID, persisted.themeId)
            assertFalse(persisted.dynamicColor)
            val whaleId = persisted.whaleThemeDiscovery.dedicatedAssistantId
            assertEquals(whaleId, persisted.assistantId)
            assertEquals(before.assistants, persisted.assistants.filter { it.id != whaleId })
            assertEquals(Avatar.WhaleGirl(), persisted.assistants.first { it.id == whaleId }.avatar)
            assertTrue(persisted.assistants.first { it.id == whaleId }.systemPrompt.isNotBlank())
            assertEquals(before.providers, persisted.providers)
            assertEquals(before.chatModelId, persisted.chatModelId)
            assertEquals(WhaleThemeAppearanceBackup(before.themeId, before.dynamicColor),
                persisted.whaleThemeDiscovery.previousAppearance)
            assertFalse(persisted.whaleThemeDiscovery.introPending)
            assertTrue(persisted.whaleThemeDiscovery.settingsSeen)
            assertEquals(previousIcon, manager.selectedIcon())
            assertEquals(1, openedChats)
            compose.onNodeWithText(TITLE).assertDoesNotExist()
            compose.runOnIdle { generation++ }
            settle()
            compose.onNodeWithText(TITLE).assertDoesNotExist()
        }
    }

    @Test
    fun aTrialCompletedAfterRotationClosesTheRestoredIntroduction() {
        withExistingUser { store, _ ->
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                val settings by rememberUserSettingsState()
                MiffanTheme(colorMode = ColorMode.LIGHT) {
                    CompositionLocalProvider(LocalSettings provides settings) {
                        WhaleThemeDiscoveryHost(settings, store, eligible = true)
                    }
                }
            }
            awaitIntroduction(store)
            restoration.emulateSavedInstanceStateRestore()
            settle()
            compose.onNodeWithText(TITLE).assertExists()
            // Model the old composition's non-cancellable transaction completing after state
            // restoration. The new host must close from durable state, not its old callback.
            runBlocking { store.update { it.withWhaleThemeTrial() } }
            settle()
            compose.onNodeWithText(TITLE).assertDoesNotExist()
        }
    }

    private fun awaitIntroduction(store: SettingsStore) {
        settle()
        compose.waitUntil(5_000) { !store.settingsFlow.value.whaleThemeDiscovery.introPending }
        settle()
        compose.onNodeWithText(TITLE).assertExists()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun withExistingUser(block: (SettingsStore, Settings) -> Unit) {
        compose.mainClock.autoAdvance = false
        val store = GlobalContext.get().get<SettingsStore>()
        val original = runBlocking { store.settingsFlowRaw.first() }
        val model = Model(modelId = "discovery-test-model")
        // Disabled and credential-free: enough local configuration to represent an existing
        // user, without connecting a real provider or requesting a generation.
        val provider = ProviderSetting.OpenAI(name = "Discovery test", enabled = false,
            baseUrl = "https://example.invalid/v1", models = listOf(model))
        val selected = Assistant(name = "Discovery selected", avatar = Avatar.Miffan(),
            systemPrompt = "Keep this prompt", chatModelId = model.id)
        val other = Assistant(name = "Untouched custom avatar", avatar = Avatar.Emoji("🌱"))
        val seed = original.copy(themeId = PresetThemes.first().id, dynamicColor = true,
            providers = original.providers + provider, chatModelId = model.id,
            assistants = original.assistants + selected + other, assistantId = selected.id,
            whaleThemeDiscovery = initialWhaleThemeDiscovery(12, System.currentTimeMillis()))
        assertFalse(seed.isNotConfigured())
        try {
            runBlocking { store.update(seed) }
            block(store, seed)
        } finally {
            runBlocking { store.update(original) }
        }
    }

    private companion object {
        const val TITLE = "蓝色大肥鱼来啦"
    }
}
