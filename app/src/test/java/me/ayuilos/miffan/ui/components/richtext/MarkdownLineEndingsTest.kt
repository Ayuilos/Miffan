package me.ayuilos.miffan.ui.components.richtext

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownLineEndingsTest {
    @Test
    fun `code fences close with CRLF and CR line endings`() {
        val source = "````\n文字 A\n\n````\n文字 B\n```\n\n文字 C\n```"
        val flavour = GFMFlavourDescriptor()
        for (ending in listOf("\r\n", "\r", "\n")) {
            val text = normalizeMarkdownLineEndings(source.replace("\n", ending))
            assertEquals(source, text)
            val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(text)
            val html = Jsoup.parse(HtmlGenerator(text, tree, flavour).generateHtml())
            assertEquals(2, html.select("pre code").size)
            assertEquals("文字 A", html.select("pre code")[0].wholeText().trimEnd())
            assertEquals("文字 B", html.select("p").text())
            assertEquals("\n文字 C", html.select("pre code")[1].wholeText().trimEnd())
        }
    }
}
