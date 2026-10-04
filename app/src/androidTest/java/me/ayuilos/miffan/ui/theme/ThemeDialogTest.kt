package me.ayuilos.miffan.ui.theme

import android.view.Window
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun darkTerminalDialogOpensAndClosesWithoutChangingLightHostWindow() {
        lateinit var hostWindow: Window
        lateinit var dialogWindow: Window
        compose.setContent {
            MiffanTheme(colorMode = ColorMode.LIGHT) {
                val hostView = LocalView.current
                SideEffect { hostWindow = requireNotNull(hostView.findThemeWindow()) }
                var visible by remember { mutableStateOf(false) }
                TextButton(onClick = { visible = true }) { Text("Open terminal") }
                if (visible) {
                    Dialog(
                        onDismissRequest = { visible = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
                    ) {
                        MiffanTheme(colorMode = ColorMode.DARK) {
                            val dialogView = LocalView.current
                            SideEffect { dialogWindow = requireNotNull(dialogView.findThemeWindow()) }
                            TextButton(onClick = { visible = false }) { Text("Return to chat") }
                        }
                    }
                }
            }
        }

        repeat(2) {
            compose.onNodeWithText("Open terminal").performClick()
            compose.onNodeWithText("Return to chat").assertIsDisplayed()
            compose.runOnIdle {
                assertNotSame(hostWindow, dialogWindow)
                assertTrue(WindowCompat.getInsetsController(hostWindow, hostWindow.decorView).isAppearanceLightStatusBars)
                assertFalse(WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView).isAppearanceLightStatusBars)
            }
            compose.onNodeWithText("Return to chat").performClick()
            compose.onNodeWithText("Open terminal").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(WindowCompat.getInsetsController(hostWindow, hostWindow.decorView).isAppearanceLightStatusBars)
            }
        }
    }
}
