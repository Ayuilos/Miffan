package me.ayuilos.miffan.ui.components.ai

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceSelectRowUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longRemoteNameRemainsSelectableInNarrowDarkSheetRow() {
        val selected = mutableStateOf(0)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding().width(320.dp)) {
                        WorkspaceSelectRow(
                            title = "A remote workspace with a particularly long project name",
                            rowTag = "workspace-select-long",
                            selected = true,
                            onClick = { selected.value++ },
                            isRemote = true,
                            statusLines = listOf("远程服务器 · Build server", "按需连接 · 本次启动尚未检查"),
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("workspace-select-long").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, selected.value) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, "workspace-select-row-narrow-dark.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
