package me.ayuilos.miffan.ui.pages.extensions.workspace

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteTerminalViewTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun screenRetainsOutputWhileViewIsDetachedAndReattached() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnIdle {
            val shared = RemoteTerminalScreen()
            val first = RemoteTerminalView(context).apply { screen = shared }
            first.appendOutput("BEFORE\r\n".toByteArray())
            first.detachScreen()
            shared.appendOutput("AFTER\r\n".toByteArray())

            val returned = RemoteTerminalView(context).apply {
                // Attach after the new view was already laid out (e.g. a rotated page).
                layout(0, 0, 720, 1000)
                screen = shared
            }
            assertEquals(returned.columns, shared.emulator.mColumns)
            assertEquals(returned.rows, shared.emulator.mRows)
            assertTrue(returned.visibleText().contains("BEFORE"))
            assertTrue(returned.visibleText().contains("AFTER"))
            returned.detachScreen()
        }
    }

    @Test
    fun rendersAnsiAndForwardsImeAndSpecialKeys() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val writes = mutableListOf<ByteArray>()
        lateinit var terminal: RemoteTerminalView
        compose.setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = {
                        RemoteTerminalView(it).also { view ->
                            terminal = view
                            view.sendBytes = { bytes -> writes += bytes.copyOf() }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.runOnIdle {
            terminal.appendOutput("\u001b[31mRED\u001b[0m\r\n".toByteArray())
            assertTrue(terminal.visibleText().contains("RED"))

            val input = terminal.onCreateInputConnection(EditorInfo())
            input.setComposingText("ni", 1)
            input.setComposingText("nihao", 1)
            input.deleteSurroundingText(1, 0)
            assertTrue(writes.isEmpty())
            input.commitText("你好", 1)
            input.commitText("pwd", 1)
            terminal.sendSpecialKey(KeyEvent.KEYCODE_ENTER)
            terminal.sendSpecialKey(KeyEvent.KEYCODE_DPAD_UP)
            input.deleteSurroundingText(1, 0)

            val finishInput = terminal.onCreateInputConnection(EditorInfo())
            finishInput.setComposingText("word", 1)
            val countBeforeFinish = writes.size
            finishInput.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            assertEquals(countBeforeFinish, writes.size)
            finishInput.finishComposingText()
            finishInput.finishComposingText()
            assertEquals(countBeforeFinish + 1, writes.size)
            assertEquals("wor", writes.last().toString(Charsets.UTF_8))
        }
        compose.waitForIdle()

        val sent = writes.joinToString(separator = "") { it.toString(Charsets.UTF_8) }
        assertTrue(sent.contains("pwd"))
        assertTrue(sent.startsWith("你好pwd"))
        assertTrue(sent.contains("\r"))
        assertTrue(sent.contains("\u001b[A") || sent.contains("\u001bOA"))
        assertTrue(sent.contains("\u007f"))

        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.cacheDir, "remote-terminal-ansi.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()

        compose.runOnIdle {
            terminal.resetScreen()
            assertTrue(!terminal.visibleText().contains("RED"))
        }
    }
}
