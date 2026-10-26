package me.ayuilos.miffan.ui.components.richtext

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownMathTest {
    private fun maths(content: String) = Jsoup.parse(generateMarkdownHtml(content)).select("span.math")
        .map { it.text() to it.attr("inline") }

    @Test
    fun `math keeps less than signs`() {
        val cases = """\begin{cases}1/2, & 0<x<1\\ \infty, & 1\le x<2\end{cases}"""
        assertEquals(listOf(cases to "false"), maths("<b>html</b>\n\n${'$'}${'$'}\n$cases\n${'$'}${'$'}"))
        assertEquals(
            listOf("a<b" to "true", "x<y>z" to "true", "a&b" to "true"),
            maths("""if ${'$'}a<b$ and ${'$'}x<y>z$ or ${'$'}a&b$"""),
        )
    }

    @Test
    fun `a line break with spacing is not a display math delimiter`() {
        assertEquals(
            listOf("""a\\[4pt]b""" to "false", "c" to "false"),
            maths("""${'$'}${'$'}a\\[4pt]b${'$'}${'$'} and \[c\]"""),
        )
    }
}
