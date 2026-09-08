package me.ayuilos.miffan.ui.pages.assistant.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.sonner.rememberToasterState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.WhaleThemeDiscovery
import me.ayuilos.miffan.data.model.createWhaleAssistant
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.context.LocalSharedTransitionScope
import me.ayuilos.miffan.ui.context.LocalToaster
import me.ayuilos.miffan.ui.context.Navigator
import me.ayuilos.miffan.ui.hooks.rememberUserSettingsState
import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.ui.theme.MiffanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WhaleAssistantResetTest {
    @get:Rule
    val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor = 0f
    })

    @Test
    fun resetSurvivesRenamingAndAvatarEditsRequiresConfirmationAndPreservesIdentity() {
        val store = GlobalContext.get().get<SettingsStore>()
        val original = runBlocking { store.settingsFlowRaw.first() }
        val preset = createWhaleAssistant()
        val edited = preset.copy(name = "我修改过的名字", avatar = Avatar.Emoji("🌱"),
            systemPrompt = "My edited personality", useAssistantAvatar = false,
            chatModelId = original.chatModelId, temperature = 0.2f, enableMemory = true,
            streamOutput = false, enableWebSearch = true)
        try {
            runBlocking {
                store.update(original.copy(
                    assistants = original.assistants + edited,
                    whaleThemeDiscovery = WhaleThemeDiscovery(settingsSeen = true, dedicatedAssistantId = edited.id),
                ))
            }
            compose.setContent {
                val settings by rememberUserSettingsState()
                MiffanTheme(colorMode = ColorMode.LIGHT) {
                    SharedTransitionLayout {
                        val sharedScope = this
                        AnimatedContent(targetState = edited.id, label = "whale-reset-test") { id ->
                            CompositionLocalProvider(
                                LocalSettings provides settings,
                                LocalNavController provides remember { Navigator(mutableListOf()) },
                                LocalToaster provides rememberToasterState(),
                                LocalSharedTransitionScope provides sharedScope,
                                LocalNavAnimatedContentScope provides this,
                            ) {
                                AssistantBasicPage(id.toString())
                            }
                        }
                    }
                }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("恢复大肥鱼默认设定").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("恢复大肥鱼默认设定").performScrollTo().performClick()
            compose.onNodeWithText("恢复大肥鱼默认设定？").assertExists()
            assertEquals(edited, runBlocking { store.settingsFlowRaw.first() }.assistants.first { it.id == edited.id })
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("恢复大肥鱼默认设定？").assertDoesNotExist()
            assertEquals(edited, runBlocking { store.settingsFlowRaw.first() }.assistants.first { it.id == edited.id })

            compose.onNodeWithText("恢复大肥鱼默认设定").performScrollTo().performClick()
            compose.onNodeWithText("恢复默认").performClick()
            compose.waitUntil(5_000) {
                store.settingsFlow.value.assistants.firstOrNull { it.id == edited.id } == preset
            }
            val saved = runBlocking { store.settingsFlowRaw.first() }
            assertEquals(preset, saved.assistants.first { it.id == edited.id })
            assertEquals(original.assistants, saved.assistants.filter { it.id != edited.id })
            assertEquals(edited.id, saved.whaleThemeDiscovery.dedicatedAssistantId)
            compose.onNodeWithText("恢复大肥鱼默认设定？").assertDoesNotExist()
            compose.onNodeWithText("恢复大肥鱼默认设定").assertExists()
        } finally {
            runBlocking { store.update(original) }
        }
    }
}
