package me.ayuilos.miffan.ui.pages.extensions.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelimitedTextParserTest {
    @Test
    fun `csv parser handles quoted delimiters and escaped quotes`() {
        val table = parseDelimitedText(
            "name,note\nAlice,\"hello, world\"\nBob,\"said \"\"hi\"\"\"",
            delimiter = ',',
        )

        assertEquals(listOf("name", "note"), table.headers)
        assertEquals(listOf("Alice", "hello, world"), table.rows[0])
        assertEquals(listOf("Bob", "said \"hi\""), table.rows[1])
        assertFalse(table.truncated)
    }

    @Test
    fun `preview bounds rows`() {
        val table = parseDelimitedText(
            "h\n1\n2\n3",
            delimiter = ',',
            maxRows = 2,
        )

        assertEquals(2, table.rows.size)
        assertTrue(table.truncated)
    }
}
