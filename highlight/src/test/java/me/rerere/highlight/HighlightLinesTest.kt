package me.rerere.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Highlighting a text in pieces of lines ([CodeHighlighter.highlightLines]) must color it as highlighting it whole
 * does: a streamed code block is shown piece by piece and must not change color when its pieces are joined.
 */
class HighlightLinesTest {
    private val highlighter = CodeHighlighter()

    private val fixtures: List<Pair<String, File>> by lazy {
        val root = File(HighlightLinesTest::class.java.getResource("/hljs")!!.toURI())
        root.listFiles { file -> file.isDirectory }!!.sortedBy { it.name }.flatMap { directory ->
            directory.listFiles { file -> file.extension == "txt" }!!.sortedBy { it.name }.map { directory.name to it }
        }
    }

    /**
     * The scope of every character that shows: where tokens are cut does not matter, only what color each
     * character gets, and white space has none.
     */
    private fun scopes(code: String, tokens: List<HighlightToken>): List<String?> {
        val scopes = tokens.flatMap { token ->
            val scope = (token as? HighlightToken.Styled)?.type
            List(token.content.length) { scope }
        }
        assertEquals(code.length, scopes.size)
        return scopes.mapIndexed { index, scope -> scope.takeUnless { code[index].isWhitespace() } }
    }

    private fun describe(code: String, whole: List<String?>, pieces: List<String?>): String {
        val at = whole.indices.first { whole[it] != pieces[it] }
        val line = code.substring(0, at).count { it == '\n' } + 1
        return "line $line, character ${at}: whole=${whole[at]} pieces=${pieces[at]}, near \"${
            code.substring((at - 30).coerceAtLeast(0), (at + 30).coerceAtMost(code.length)).replace("\n", "\\n")
        }\""
    }

    @Test
    fun `pieces spell out the text and split it at line ends`() {
        fixtures.forEach { (language, file) ->
            val code = file.readText()
            val pieces = highlighter.highlightLines(code, language)
            assertEquals(code, pieces.joinToString("") { it.text })
            pieces.forEach { piece ->
                assertEquals(piece.text, piece.tokens.joinToString("") { it.content })
                assertFalse(piece.pending)
            }
            pieces.dropLast(1).forEach { piece ->
                assertTrue(piece.text.endsWith('\n'))
                assertEquals(CodeHighlighter.LINES_PER_PIECE, piece.text.count { it == '\n' })
            }
        }
    }

    @Test
    fun `pieces are colored as the whole text is`() {
        val differences = fixtures.mapNotNull { (language, file) ->
            val code = file.readText()
            val whole = scopes(code, highlighter.highlight(code, language))
            val pieces = scopes(code, highlighter.highlightLines(code, language).flatMap { it.tokens })
            if (whole == pieces) null else "$language/${file.name}: ${describe(code, whole, pieces)}"
        }
        assertEquals("", differences.joinToString("\n"))
    }

    /**
     * Pieces of one, two, three... lines put a piece boundary at every line of every fixture. Rules that need the
     * next line lose their color at such a boundary; everything else must be colored as in the whole text.
     */
    @Test
    fun `a piece may end at any line unless a rule reads across lines`() {
        val readAcrossLines = setOf(
            "c/edge.txt", // a macro that goes on after a backslash
            "csharp/sample.txt", // a function is told by the brace on the next line; a raw string by its closing line
            "dockerfile/sample.txt", // RUN goes on after a backslash
            "markdown/edge.txt", // a setext heading is told by the line under it
            "properties/sample.txt", // a value goes on after a backslash
            "ruby/sample.txt", // a heredoc is one match up to its closing line
        )
        val differing = HashSet<String>()
        val unexpected = fixtures.flatMap { (language, file) ->
            val name = "$language/${file.name}"
            val code = file.readText()
            val whole = scopes(code, highlighter.highlight(code, language))
            listOf(1, 2, 3, 5, 7).mapNotNull { lines ->
                val pieces = scopes(code, highlighter.highlightLines(code, language, emptyList(), Int.MAX_VALUE, lines).flatMap { it.tokens })
                if (whole == pieces) return@mapNotNull null
                differing += name
                "$name in pieces of $lines: ${describe(code, whole, pieces)}".takeIf { name !in readAcrossLines }
            }
        }
        assertEquals("", unexpected.joinToString("\n"))
        // The list above stays honest: a fixture that no longer differs must leave it.
        assertEquals(readAcrossLines, differing)
    }

    @Test
    fun `a one pass result in pieces is the one pass result`() {
        fixtures.forEach { (language, file) ->
            val code = file.readText()
            val pieces = highlighter.highlightWholeInLines(code, language)
            assertEquals(code, pieces.joinToString("") { it.text })
            pieces.forEach { assertEquals(it.text, it.tokens.joinToString("") { token -> token.content }) }
            assertEquals(
                highlighter.highlight(code, language).flatMap { token -> token.content.map { it to (token as? HighlightToken.Styled)?.type } },
                pieces.flatMap { it.tokens }.flatMap { token -> token.content.map { it to (token as? HighlightToken.Styled)?.type } },
            )
            assertEquals(highlighter.highlightLines(code, language).map { it.text }, pieces.map { it.text })
        }
    }

    @Test
    fun `a one pass result is not continued`() {
        val (language, file) = fixtures.first { it.first == "kotlin" }
        val code = file.readText().repeat(2)
        val whole = highlighter.highlightWholeInLines(code, language)
        val grown = highlighter.highlightLines(code + "\nval more = 1\n", language, whole)
        assertNotSame(whole[0], grown[0])
        assertEquals(
            scopes(code + "\nval more = 1\n", highlighter.highlightLines(code + "\nval more = 1\n", language).flatMap { it.tokens }),
            scopes(code + "\nval more = 1\n", grown.flatMap { it.tokens }),
        )
    }

    @Test
    fun `a text that grows keeps its finished pieces`() {
        val (language, file) = fixtures.first { it.first == "kotlin" }
        val code = file.readText().repeat(3)
        var pieces = emptyList<HighlightedLines>()
        var end = 0
        while (end < code.length) {
            end = (end + 37).coerceAtMost(code.length)
            val grown = highlighter.highlightLines(code.substring(0, end), language, pieces)
            // Everything but the piece being written (and a new one started after it) is the same object.
            grown.dropLast(2).forEachIndexed { index, piece -> assertSame(pieces[index], piece) }
            pieces = grown
        }
        assertEquals(scopes(code, highlighter.highlightLines(code, language).flatMap { it.tokens }), scopes(code, pieces.flatMap { it.tokens }))
        assertTrue(pieces.size > 3)
    }

    @Test
    fun `a changed line highlights its piece and the ones after it again`() {
        val (language, file) = fixtures.first { it.first == "kotlin" }
        val code = file.readText().repeat(3)
        val before = highlighter.highlightLines(code, language)
        val changedAt = before[0].text.length + 3
        val changed = code.substring(0, changedAt) + "/* " + code.substring(changedAt)
        val after = highlighter.highlightLines(changed, language, before)
        assertSame(before[0], after[0])
        assertNotSame(before[1], after[1])
        assertEquals(scopes(changed, highlighter.highlightLines(changed, language).flatMap { it.tokens }), scopes(changed, after.flatMap { it.tokens }))
    }

    @Test
    fun `work beyond the budget is postponed and finished later`() {
        val (language, file) = fixtures.first { it.first == "kotlin" }
        val code = file.readText().repeat(6)
        val started = highlighter.highlightLines(code, language, budget = 600)
        assertFalse(started.first().pending)
        assertTrue(started.last().pending)
        // Once a piece waits, the ones after it wait too: they have no context to be highlighted in.
        assertEquals(started.indexOfFirst { it.pending }, started.size - started.count { it.pending })
        assertEquals(code, started.joinToString("") { it.tokens.joinToString("") { token -> token.content } })

        val finished = highlighter.highlightLines(code, language, started)
        assertSame(started.first(), finished.first())
        assertTrue(finished.none { it.pending })
        assertEquals(scopes(code, highlighter.highlight(code, language)), scopes(code, finished.flatMap { it.tokens }))
    }

    @Test
    fun `an unknown language and an endless line stay plain`() {
        val unknown = highlighter.highlightLines("a\nb\n".repeat(20), "no-such-language")
        assertTrue(unknown.size > 1)
        assertTrue(unknown.all { !it.pending && it.tokens.single() is HighlightToken.Plain })

        val minified = "var a=1;".repeat(2000)
        val pieces = highlighter.highlightLines("let x = 1\n$minified\nlet y = 2\n", "javascript")
        assertEquals("let x = 1\n$minified\nlet y = 2\n", pieces.joinToString("") { it.text })
        assertTrue(pieces.none { it.pending })
    }

    @Test
    fun `pieces can be highlighted from two threads at once`() {
        val (language, file) = fixtures.first { it.first == "kotlin" }
        val code = file.readText().repeat(4)
        val expected = scopes(code, highlighter.highlightLines(code, language).flatMap { it.tokens })
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = List(4) {
            Thread {
                runCatching {
                    repeat(20) { assertEquals(expected, scopes(code, highlighter.highlightLines(code, language).flatMap { it.tokens })) }
                }.onFailure { failures += it }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        assertEquals(emptyList<Throwable>(), failures.toList())
    }
}
