package me.rerere.workspace

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class PersistentShellProtocolTest {
    @Test fun markersAndUtf8MayBeSplitAtEveryByte() {
        val protocol = PersistentShellProtocol("token")
        val output = ByteArrayOutputStream()
        var code: Int? = null
        val stream = "banner\r\n\u001eMIFFAN_token_BEGIN\u001f你好🍚\r\n\u001eMIFFAN_token_END:127\u001fprompt"
        stream.toByteArray().forEach {
            val chunk = protocol.feed(byteArrayOf(it))
            output.write(chunk.output)
            chunk.exitCode?.let { code = it }
        }
        assertEquals("你好🍚\r\n", output.toString("UTF-8"))
        assertEquals(127, code)
    }

    @Test fun noNewlinePromptIsEmittedImmediately() {
        val protocol = PersistentShellProtocol("t")
        assertEquals("Password: ", protocol.feed("\u001eMIFFAN_t_BEGIN\u001fPassword: ".toByteArray()).output.toString(Charsets.UTF_8))
    }

    @Test fun uploadAcknowledgementDoesNotMeanInvocationHasStarted() {
        val protocol = PersistentShellProtocol("token")
        val upload = protocol.feed("\u001eMIFFAN_token_READY\u001f".toByteArray())
        assertTrue(upload.ready)
        assertFalse(upload.started)
        val begin = "\u001eMIFFAN_token_BEGIN\u001f".toByteArray()
        begin.dropLast(1).forEach {
            assertFalse(protocol.feed(byteArrayOf(it)).started)
        }
        val started = protocol.feed(byteArrayOf(begin.last()))
        assertTrue(started.started)
        assertFalse(started.ready)
        assertTrue(started.output.isEmpty())
    }

    @Test fun fastCommandAcknowledgesStartEvenWhenEndArrivesInTheSameRead() {
        val protocol = PersistentShellProtocol("token")
        val chunk = protocol.feed("\u001eMIFFAN_token_BEGIN\u001f\u001eMIFFAN_token_END:0\u001f".toByteArray())
        assertTrue(chunk.started)
        assertEquals(0, chunk.exitCode)
        assertTrue(chunk.output.isEmpty())
    }
}
