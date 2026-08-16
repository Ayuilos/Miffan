package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestPathTest {
    @Test
    fun `accepts canonical absolute POSIX paths`() {
        val path = GuestPath.parse("/workspace/a file.txt")

        assertEquals("/workspace/a file.txt", path.value)
        assertEquals("a file.txt", path.name)
        assertTrue(path.isWithin(GuestPath.parse("/workspace")))
        assertFalse(path.isWithin(GuestPath.parse("/tmp")))
    }

    @Test
    fun `rejects ambiguous and traversal spellings`() {
        listOf(
            "workspace/file",
            "/workspace/../skills/evil",
            "/workspace/./file",
            "/workspace//file",
            "/workspace/file/",
            "/workspace\\file",
            "/workspace/file\u0000tail",
        ).forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) {
                GuestPath.parse(raw)
            }
        }
    }
}
