package me.ayuilos.miffan.ui.im.computer

import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceFilesNamingTest {
    @Test
    fun `uploads never replace a file of the same name`() {
        assertEquals("a.pdf", uniqueName("a.pdf", setOf("b.pdf")))
        assertEquals("a (2).pdf", uniqueName("a.pdf", setOf("a.pdf")))
        assertEquals("a (3).pdf", uniqueName("a.pdf", setOf("a.pdf", "a (2).pdf")))
        assertEquals("x (2).tar.gz", uniqueName("x.tar.gz", setOf("x.tar.gz")))
        assertEquals(".env (2)", uniqueName(".env", setOf(".env")))
        assertEquals("notes (2)", uniqueName("notes", setOf("notes")))
    }

    @Test
    fun `upload names are plain file names`() {
        assertEquals("照片.jpg", spaceUploadName("照片.jpg"))
        assertEquals("b.txt", spaceUploadName("dir/b.txt"))
        assertEquals("a_b", spaceUploadName("a\nb"))
        assertEquals("file", spaceUploadName(""))
        assertEquals("file", spaceUploadName(".."))
        assertEquals("file", spaceUploadName(null))
    }
}
