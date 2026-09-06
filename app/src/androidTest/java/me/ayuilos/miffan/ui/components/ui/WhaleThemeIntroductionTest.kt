package me.ayuilos.miffan.ui.components.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.ayuilos.miffan.ui.theme.presets.WhaleThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WhaleThemeIntroductionTest {
    @get:Rule
    val compose = createComposeRule(effectContext = object : MotionDurationScale {
        // Use the supported reduced-motion path without pausing the Dialog ViewTree.
        override val scaleFactor: Float = 0f
    })

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun tryingTheThemePreservesTheIconByDefaultAndHonorsAnExplicitOptIn() {
        val choices = mutableListOf<Boolean>()
        var dismissed = 0
        preparePreview()
        compose.setContent {
            PreviewEnvironment() {
                WhaleThemeIntroduction(onTryTheme = { choices += it }, onDismiss = { dismissed++ })
            }
        }
        draw()
        compose.onNodeWithText("同时换上大肥鱼图标").performScrollTo().assertIsOff()
        compose.onNodeWithText("立即体验").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(false), choices) }

        compose.onNodeWithText("同时换上大肥鱼图标").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("立即体验").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(false, true), choices) }
        compose.onNodeWithText("以后再说").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun applyingPreventsAnotherSubmissionOrDismissalAndShowsAnActionableFailure() {
        val choices = mutableListOf<Boolean>()
        var dismissed = 0
        var busy by mutableStateOf(true)
        var error by mutableStateOf<String?>(null)
        preparePreview()
        compose.setContent {
            PreviewEnvironment() {
                WhaleThemeIntroduction(onTryTheme = { choices += it }, onDismiss = { dismissed++ },
                    busy = busy, errorMessage = error)
            }
        }
        draw()
        compose.onNodeWithText("正在应用").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithText("以后再说").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithText("同时换上大肥鱼图标").performScrollTo()
            .assertIsNotEnabled().performClick().assertIsOff()
        pressBack()
        compose.waitForIdle()
        compose.onNode(isDialog()).assertExists()
        compose.runOnIdle {
            assertTrue(choices.isEmpty())
            assertEquals(0, dismissed)
            busy = false
            error = "主题应用失败，请重试。"
        }
        draw()
        compose.onNodeWithText("主题应用失败，请重试。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("重试").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(false), choices) }
    }

    @Test
    fun introductionRendersInBothThemesAndLetsTheUserExploreThePreview() {
        var dark by mutableStateOf(false)
        preparePreview()
        compose.setContent {
            PreviewEnvironment(dark = dark) {
                WhaleThemeIntroduction(onTryTheme = {}, onDismiss = {})
            }
        }
        draw()
        saveDialog("whale-theme-introduction-light.png")
        compose.onNodeWithText("吃白饭").performScrollTo().performClick()
        draw()
        compose.onNodeWithContentDescription("蓝色大肥鱼，吃白饭").assertExists()
        compose.onNodeWithText("认真想想").performScrollTo().performClick()
        draw()
        compose.onNodeWithContentDescription("蓝色大肥鱼，认真想想").assertExists()
        compose.runOnIdle { dark = true }
        draw()
        compose.onNodeWithText("蓝色大肥鱼来啦").performScrollTo()
        saveDialog("whale-theme-introduction-dark.png")
    }

    @Test
    fun compactLandscapeHeightKeepsTheActionsReachableByScrolling() {
        val choices = mutableListOf<Boolean>()
        var dismissed = 0
        preparePreview()
        // The physical emulator can stay in portrait. Supply the same available-height
        // configuration a 600 × 320dp landscape window gives this responsive dialog.
        val landscape = Configuration(context.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
            screenWidthDp = 600
            screenHeightDp = 320
        }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides landscape) {
                PreviewEnvironment() {
                    WhaleThemeIntroduction(onTryTheme = { choices += it }, onDismiss = { dismissed++ })
                }
            }
        }
        draw()
        compose.onNodeWithText("蓝色大肥鱼来啦").assertIsDisplayed()
        step("compact scroll to try")
        compose.onNodeWithText("立即体验").performScrollTo().assertIsDisplayed().performClick()
        step("compact scroll to dismiss")
        compose.onNodeWithText("以后再说").performScrollTo().assertIsDisplayed().performClick()
        step("compact actions clicked")
        compose.runOnIdle {
            assertEquals(listOf(false), choices)
            assertEquals(1, dismissed)
        }
        val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        val maximumPixels = 288 * context.resources.displayMetrics.density
        assertTrue("The compact card must respect the available height", dialog.height <= maximumPixels + 2)
        saveDialog("whale-theme-introduction-compact-actions.png")
    }

    private fun preparePreview() {
        // Dialog creates its own ViewTree/Recomposer. A synthetic STARTED owner pauses
        // that recomposer and can deadlock test synchronization before the first scroll.
        // Keep the real resumed owner and let reduced motion freeze the portrait instead.
        compose.mainClock.autoAdvance = true
        step("preview prepared")
    }

    @Composable
    private fun PreviewEnvironment(dark: Boolean = false, content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = WhaleThemePreset.getColorScheme(dark), content = content)
    }

    private fun step(message: String) { Log.i("WhaleIntroductionTest", message) }

    private fun draw() {
        step("draw begin")
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        step("draw complete")
    }

    private fun saveDialog(name: String) {
        step("capture $name begin")
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "visual-tests").apply { mkdirs() }
        File(directory, name).outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        step("capture $name complete")
    }
}
