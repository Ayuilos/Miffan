package me.ayuilos.miffan.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownLinksTest {
    @Test
    fun cjkPunctuationEndsTheLink() {
        assertEquals("详见 <https://example.com/a>，然后点登录", linkifyBareUrls("详见 https://example.com/a，然后点登录"))
        assertEquals("官网：<https://example.com/a>。", linkifyBareUrls("官网：https://example.com/a。"))
        assertEquals("地址（<https://example.com/a>）里有", linkifyBareUrls("地址（https://example.com/a）里有"))
        assertEquals("“<https://example.com/a>”", linkifyBareUrls("“https://example.com/a”"))
    }

    @Test
    fun linksAUrlRightAfterAChineseWord() {
        assertEquals("详见<https://example.com/a>", linkifyBareUrls("详见https://example.com/a"))
    }

    @Test
    fun keepsWhatBelongsToTheUrl() {
        assertEquals("<https://zh.wikipedia.org/wiki/北京> 很好", linkifyBareUrls("https://zh.wikipedia.org/wiki/北京 很好"))
        assertEquals("<https://example.com/path?q=中文&x=1> 就行", linkifyBareUrls("https://example.com/path?q=中文&x=1 就行"))
        assertEquals("see <https://en.wikipedia.org/wiki/Rust_(language)>.",
            linkifyBareUrls("see https://en.wikipedia.org/wiki/Rust_(language)."))
        assertEquals("**<https://example.com/a>**，好", linkifyBareUrls("**https://example.com/a**，好"))
    }

    @Test
    fun leavesExistingLinksAndCodeAlone() {
        for (text in listOf(
            "[文档](https://example.com/a)",
            "[https://example.com/a](https://example.com/a)",
            "<https://example.com/a>",
            """<a href="https://example.com/a">x</a>""",
            "`curl https://example.com/a`",
            "```\nhttps://example.com/a，\n```",
            "abchttps://example.com/a",
            "(https://example.com/a)",
            "https://",
        )) assertEquals(text, linkifyBareUrls(text))
    }

    @Test
    fun bareDomainsOpenOverHttps() {
        assertEquals("https://www.example.com", linkTarget("www.example.com"))
        assertEquals("https://example.com/a", linkTarget("<https://example.com/a>"))
        assertEquals("mailto:a@b.com", linkTarget("mailto:a@b.com"))
        assertEquals("report.html", linkTarget("report.html"))
        assertEquals("/workspace/report.html", linkTarget("/workspace/report.html"))
    }
}
