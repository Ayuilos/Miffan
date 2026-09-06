package me.ayuilos.miffan.ui.pages.onboarding

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterAuthState
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterSavedKeyState
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.WhaleThemeDiscovery
import me.ayuilos.miffan.data.model.createWhaleAssistant
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.hooks.rememberUserSettingsState
import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.ui.theme.MiffanTheme
import me.ayuilos.miffan.ui.theme.PresetThemes
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID
import me.ayuilos.miffan.ui.theme.presets.WhaleThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OnboardingThemeVisualTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun switchingRecolorsTheVisiblePageAndPersistsAcrossCompositionRecreation() {
        withSavedSettings { store, original ->
            val selected = Assistant(name = "Onboarding selected", systemPrompt = "Keep this prompt")
            val other = Assistant(name = "Untouched", avatar = Avatar.Emoji("🌱"))
            val baseline = original.copy(themeId = PresetThemes.first().id, dynamicColor = true,
                assistants = original.assistants + selected + other, assistantId = selected.id,
                whaleThemeDiscovery = WhaleThemeDiscovery(settingsSeen = true))
            runBlocking { store.update(baseline) }
            var colorMode by mutableStateOf(ColorMode.LIGHT)
            var generation by mutableStateOf(0)
            compose.setContent {
                // Recreate both the store subscription and theme, so no remembered switch value
                // can stand in for the value that was actually saved to disk.
                key(generation) {
                    val settings by rememberUserSettingsState()
                    val scope = rememberCoroutineScope()
                    MiffanTheme(colorMode = colorMode) {
                        CompositionLocalProvider(LocalSettings provides settings) {
                            OnboardingContent(
                                authState = OpenRouterAuthState.Idle,
                                savedKeyState = OpenRouterSavedKeyState.Missing,
                                whaleTheme = settings.themeId == WHALE_THEME_ID,
                                themeEnabled = true,
                                onWhaleThemeChange = { enabled -> scope.launch {
                                    store.update { it.withOnboardingWhaleTheme(enabled) }
                                } },
                                onConnect = {}, onCancel = {}, onManualSetup = {},
                            )
                        }
                    }
                }
            }
            for (mode in listOf(ColorMode.LIGHT, ColorMode.DARK)) {
                compose.runOnIdle { colorMode = mode }
                settle()
                compose.onNodeWithTag("onboarding_whale_theme").assertIsOff()
                compose.onNodeWithContentDescription("Miffan mascot").assertExists()
                val defaultBackground = backgroundPixel()
                val defaultCard = cardPixel()

                toggle(store, true)
                compose.onNode(hasContentDescription("蓝色大肥鱼", substring = true)).assertExists()
                val scheme = WhaleThemePreset.getColorScheme(mode == ColorMode.DARK)
                assertEquals(scheme.background.toArgb(), backgroundPixel())
                assertEquals(scheme.primaryContainer.toArgb(), cardPixel())
                assertNotEquals("The page background must change immediately", defaultBackground, backgroundPixel())
                assertNotEquals("The connection card must change immediately", defaultCard, cardPixel())
                val persisted = runBlocking { store.settingsFlowRaw.first() }
                assertEquals(WHALE_THEME_ID, persisted.themeId)
                assertEquals(false, persisted.dynamicColor)
                val whaleId = persisted.whaleThemeDiscovery.dedicatedAssistantId!!
                assertEquals(whaleId, persisted.assistantId)
                assertEquals(createWhaleAssistant(whaleId), persisted.assistants.first { it.id == whaleId })
                assertEquals(baseline.assistants, persisted.assistants.filter { it.id != whaleId })
                assertEquals(baseline.providers, persisted.providers)

                compose.runOnIdle { generation++ }
                settle()
                compose.onNodeWithTag("onboarding_whale_theme").assertIsOn()
                compose.onNode(hasContentDescription("蓝色大肥鱼", substring = true)).assertExists()
                assertEquals(scheme.background.toArgb(), backgroundPixel())
                assertEquals(scheme.primaryContainer.toArgb(), cardPixel())
                save("onboarding-whale-${mode.name.lowercase()}.png", capture("onboarding_page"))

                toggle(store, false)
                compose.onNodeWithContentDescription("Miffan mascot").assertExists()
                assertEquals(defaultBackground, backgroundPixel())
                assertEquals(defaultCard, cardPixel())
                val restored = runBlocking { store.settingsFlowRaw.first() }
                assertEquals(PresetThemes.first().id, restored.themeId)
                assertTrue(restored.dynamicColor)
                assertEquals(persisted.assistants, restored.assistants)
                assertEquals(whaleId, restored.assistantId)
                assertEquals(whaleId, restored.whaleThemeDiscovery.dedicatedAssistantId)
                save("onboarding-default-${mode.name.lowercase()}.png", capture("onboarding_page"))
            }
        }
    }

    @Test
    fun switchingWhileAuthorizationIsBusyNeverConnectsCancelsOrOpensManualSetup() {
        withSavedSettings { store, original ->
            runBlocking { store.update(original.withOnboardingWhaleTheme(false)) }
            val connects = AtomicInteger()
            val cancels = AtomicInteger()
            val manualSetups = AtomicInteger()
            compose.setContent {
                val settings by rememberUserSettingsState()
                val scope = rememberCoroutineScope()
                MiffanTheme(colorMode = ColorMode.LIGHT) {
                    CompositionLocalProvider(LocalSettings provides settings) {
                        OnboardingContent(
                            authState = OpenRouterAuthState.Authorizing,
                            savedKeyState = OpenRouterSavedKeyState.Missing,
                            whaleTheme = settings.themeId == WHALE_THEME_ID,
                            themeEnabled = true,
                            onWhaleThemeChange = { enabled -> scope.launch {
                                store.update { it.withOnboardingWhaleTheme(enabled) }
                            } },
                            onConnect = { connects.incrementAndGet() },
                            onCancel = { cancels.incrementAndGet() },
                            onManualSetup = { manualSetups.incrementAndGet() },
                        )
                    }
                }
            }
            settle()
            toggle(store, true)
            compose.onNode(hasContentDescription("蓝色大肥鱼", substring = true)).assertExists()
            save("onboarding-whale-authorizing.png", capture("onboarding_page"))
            toggle(store, false)
            assertEquals(0, connects.get())
            assertEquals(0, cancels.get())
            assertEquals(0, manualSetups.get())
        }
    }

    private fun toggle(store: SettingsStore, enabled: Boolean) {
        compose.onNodeWithTag("onboarding_whale_theme").performScrollTo().performClick()
        compose.waitUntil(5_000) { (store.settingsFlow.value.themeId == WHALE_THEME_ID) == enabled }
        settle()
        if (enabled) compose.onNodeWithTag("onboarding_whale_theme").assertIsOn()
        else compose.onNodeWithTag("onboarding_whale_theme").assertIsOff()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun backgroundPixel(): Int = capture("onboarding_page").getPixel(2, 2)

    private fun cardPixel(): Int {
        compose.onNodeWithTag("onboarding_connection_card").performScrollTo()
        val bitmap = capture("onboarding_connection_card")
        // The center of the top inset avoids the rounded corners, border, and padded content.
        return bitmap.getPixel(bitmap.width / 2, 8)
    }

    private fun capture(tag: String): Bitmap = compose.onNodeWithTag(tag).captureToImage()
        .asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)

    private fun save(name: String, bitmap: Bitmap) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null), "visual-tests").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun withSavedSettings(block: (SettingsStore, Settings) -> Unit) {
        compose.mainClock.autoAdvance = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("miffan.preferences", Context.MODE_PRIVATE)
        val hadAmoled = preferences.contains("amoledDark")
        val originalAmoled = preferences.getBoolean("amoledDark", false)
        val store = GlobalContext.get().get<SettingsStore>()
        val original = runBlocking { store.settingsFlowRaw.first() }
        try {
            preferences.edit().putBoolean("amoledDark", false).commit()
            block(store, original)
        } finally {
            runBlocking { store.update(original) }
            preferences.edit().apply {
                if (hadAmoled) putBoolean("amoledDark", originalAmoled) else remove("amoledDark")
            }.commit()
        }
    }
}
