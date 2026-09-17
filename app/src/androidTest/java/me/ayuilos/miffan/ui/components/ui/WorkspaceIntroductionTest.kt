package me.ayuilos.miffan.ui.components.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceIntroductionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun waitsForEligibleLaunchAndDoesNotRepeatAfterDismissal() {
        val eligible = mutableStateOf(false)
        val seen = mutableStateOf(false)
        val launch = mutableStateOf(0)
        var acknowledgements = 0
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                key(launch.value) {
                    WorkspaceDiscoveryHost(seen.value, eligible.value, {
                        acknowledgements++
                        seen.value = true
                    }, {})
                }
            }
        }
        compose.onNodeWithText("远程工作空间").assertDoesNotExist()
        compose.runOnIdle { eligible.value = true }
        compose.onNodeWithText("远程工作空间").assertIsDisplayed()
        capture("workspace-introduction-light.png")
        compose.onNodeWithText("稍后再说").performScrollTo().performClick()
        compose.runOnIdle { launch.value++ }
        compose.onNodeWithText("远程工作空间").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, acknowledgements) }
    }

    @Test fun visibleDialogSurvivesRestorationAndOpenActionRunsOnce() {
        val restoration = StateRestorationTester(compose)
        val seen = mutableStateOf(false)
        var acknowledgements = 0
        var opens = 0
        restoration.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WorkspaceDiscoveryHost(seen.value, true, {
                    acknowledgements++
                    seen.value = true
                }, { opens++ })
            }
        }
        compose.onNodeWithText("远程工作空间").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("远程工作空间").assertIsDisplayed()
        capture("workspace-introduction-dark.png")
        compose.onNodeWithText("打开工作空间").performScrollTo().performClick()
        compose.onNodeWithText("远程工作空间").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, acknowledgements)
            assertEquals(1, opens)
        }
    }

    @Test fun actionsRemainReachableOnShortScreens() {
        var dismissed = 0
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { screenHeightDp = 320 }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme {
                    WorkspaceIntroduction(onDismiss = { dismissed++ }, onOpenWorkspaces = {})
                }
            }
        }
        compose.onNodeWithText("打开工作空间").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("稍后再说").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
