package me.rerere.rdp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import me.rerere.workspace.screen.*
import org.junit.Assert.*
import org.junit.Test

class RdpTextTest {
    @Test fun onlyReliableCharactersAreAccepted() {
        assertTrue(RdpText.canTypeDirectly((32..126).map(Int::toChar).joinToString("") + "\t\r\n"))
        for (text in listOf("prefix中文", "é", "😀", "\u0000", "\b", "\u007f", "a".repeat(1025)))
            assertFalse(text, RdpText.canTypeDirectly(text))
    }
    @Test fun rejectedInputSendsNoPrefixAndDoesNotFailSession() {
        val session = RdpSession(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), Closeable {},
            RdpCredentials("user", "password"), RdpOptions(), object : RemoteScreenFrameSink {
                override fun onSize(width: Int, height: Int, scale: Int) {}
                override fun onPixels(rect: RfbRect, pixels: IntArray) {}
                override fun onFrameComplete() {}
            })
        // Rejected prefixes must not consume slots, even after many calls.
        repeat(5000) { assertFalse(session.typeText("prefix中文")) }
        assertFalse(session.typeText("a".repeat(4097)))
        repeat(4) { assertTrue(session.typeText("a".repeat(1024))) }
        assertFalse(session.typeText("a"))
        assertEquals(RemoteScreenState.Connecting, session.state.value)
        session.close()
        assertNull((session.state.value as RemoteScreenState.Closed).error)
        assertFalse(session.typeText(""))
    }
    @Test fun clipboardBoundIncludesUtf16Terminator() {
        RdpText.requireClipboardSize("中文😀")
        RdpText.requireClipboardSize("a".repeat(RdpText.MAX_CLIPBOARD_BYTES / 2 - 1))
        assertThrows(IllegalArgumentException::class.java) { RdpText.requireClipboardSize("a".repeat(RdpText.MAX_CLIPBOARD_BYTES / 2)) }
        assertThrows(IllegalArgumentException::class.java) { RdpText.requireClipboardSize("a\u0000b") }
    }
}
