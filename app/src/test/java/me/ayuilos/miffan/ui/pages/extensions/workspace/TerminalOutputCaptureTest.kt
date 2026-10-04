package me.ayuilos.miffan.ui.pages.extensions.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOutputCaptureTest {
    @Test fun joinsSplitUtf8AndRemovesTerminalControlCodes() {
        val bytes = "\u001b]0;title\u0007\u001b[31m你好\u001b[0m\r\n".toByteArray()
        val capture = TerminalOutputCapture()
        bytes.forEach { capture.append(byteArrayOf(it)) }
        assertEquals("你好\n", capture.text())
        assertFalse(capture.truncated)
    }

    @Test fun boundsOutputWhileRetainingFailureStatus() {
        val capture = TerminalOutputCapture(8)
        capture.append("123456".toByteArray())
        capture.append("789012345".toByteArray())
        assertEquals("12345678", capture.text())
        assertTrue(capture.truncated)
        val result = Json.parseToJsonElement(terminalCommandResult("completed", 7, capture.text(), true, null)).jsonObject
        assertEquals("7", result.getValue("exitCode").jsonPrimitive.content)
        val interrupted = Json.parseToJsonElement(terminalCommandResult("interrupted", null, "", false, "Unknown outcome")).jsonObject
        assertFalse(interrupted.containsKey("exitCode"))
    }
}
