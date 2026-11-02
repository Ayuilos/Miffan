package me.ayuilos.miffan.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SnapshotPicturesTest {
    private val sent = ByteArrayOutputStream()

    private fun serve(vararg parts: ByteArray) =
        SnapshotPictures(ByteArrayInputStream(parts.fold(ByteArray(0)) { all, part -> all + part }), sent)

    private fun text(value: String) = value.toByteArray()

    @Test fun `the greeting names the capture file, spaces and all`() {
        val pictures = serve(text("ready /Users/陈 先生/.miffan/snapshot/desktop.png\n"))
        assertEquals("/Users/陈 先生/.miffan/snapshot/desktop.png", pictures.ready())
    }

    @Test fun `a picture is its raw bytes after the header, and requests name quality and since`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), '\n'.code.toByte(), 0, '\r'.code.toByte(), 0x7F)
        val parts = arrayOf(
            text("ready /tmp/desktop.png\n"),
            text("jpeg 1234-99 ${jpeg.size}\n"), jpeg,
            text("same 1234-99\n"),
            text("png abcdef 2\r\n"), byteArrayOf(1, 2),
        )
        val pictures = serve(*parts)
        pictures.ready()
        val first = pictures.request(55, null) as SnapshotPictures.Reply.Picture
        assertEquals("jpeg", first.format)
        assertEquals("1234-99", first.digest)
        assertArrayEquals(jpeg, first.bytes)
        assertEquals(SnapshotPictures.Reply.Same("1234-99"), pictures.request(55, "1234-99"))
        val png = pictures.request(70, "1234-99") as SnapshotPictures.Reply.Picture
        assertArrayEquals(byteArrayOf(1, 2), png.bytes)
        assertEquals("55\n55 1234-99\n70 1234-99\n", sent.toString())
        assertEquals(parts.sumOf { it.size }.toLong(), pictures.bytesRead)
    }

    @Test fun `an error reply fails that picture only`() {
        val pictures = serve(text("ready /p\n"), text("error no_snapshot\n"), text("same 1-2\n"))
        pictures.ready()
        assertThrows(IllegalStateException::class.java) { pictures.request(55, null) }
        assertEquals(SnapshotPictures.Reply.Same("1-2"), pictures.request(55, "1-2"))
    }

    @Test fun `a helper that cannot start says so instead of ready`() {
        assertThrows(IllegalStateException::class.java) { serve(text("error io_error\n")).ready() }
        assertThrows(IllegalArgumentException::class.java) { serve(text("usage: miffan snapshot serve\n")).ready() }
        assertThrows(EOFException::class.java) { serve().ready() }
    }

    @Test fun `malformed headers, bad digests and short pictures are refused`() {
        for (reply in listOf("jpeg 1-2\n", "jpeg 1-2 0\n", "jpeg 1-2 999999999\n", "jpeg 1-2 x\n", "jpeg BAD 2\n", "same\n", "gif 1-2 2\n")) {
            val pictures = serve(text("ready /p\n"), text(reply), byteArrayOf(1, 2))
            pictures.ready()
            assertThrows(reply, IllegalStateException::class.java) { pictures.request(55, null) }
        }
        val short = serve(text("ready /p\n"), text("png 1-2 10\n"), byteArrayOf(1, 2))
        short.ready()
        assertThrows(EOFException::class.java) { short.request(55, null) }
        assertThrows(IllegalArgumentException::class.java) { serve(text("ready /p\n")).request(55, "1 2\n3") }
    }

    @Test fun `an endless header line is refused`() {
        val pictures = serve(ByteArray(10_000) { 'a'.code.toByte() })
        assertThrows(IllegalStateException::class.java) { pictures.ready() }
    }
}
