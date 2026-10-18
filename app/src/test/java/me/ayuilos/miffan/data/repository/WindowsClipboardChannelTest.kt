package me.ayuilos.miffan.data.repository

import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import me.rerere.workspace.RemoteChannelStream
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowsClipboardChannelTest {
    private fun write(reply: String): Boolean {
        val stream = mockk<RemoteChannelStream>(relaxed = true)
        every { stream.input } returns "$reply\n".byteInputStream()
        every { stream.output } returns ByteArrayOutputStream()
        every { stream.isConnected } returns true
        val channel = WindowsClipboardChannel { stream }
        return channel.write("text").also { channel.close() }
    }

    @Test fun `only a write that was read back counts`() {
        assertEquals(true, write("""{"success":true,"verified":true}"""))
        assertEquals(false, write("""{"success":true,"verified":false}"""))
        assertEquals(false, write("""{"success":true}"""))
        assertEquals(false, write("""{"success":false,"verified":false,"transport":"cua-bridge-session"}"""))
        assertEquals(false, write("not json"))
    }
}
