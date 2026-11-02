package me.ayuilos.miffan.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    private val body = """
        > 4.1.4 只打了 tag。

        更新内容：

        ### Miffan 自有改动

        - 出门在外也能用电脑：不在电脑所在的网络时，伙伴可以经中转连回电脑。
        - 电脑详情新增「出门在外」：
          - 今天经中转用了多少分钟；
          - 换一个中转。
        - **截图更省流量**：几秒一刷。
        - 修复连接 Windows 电脑时一直连不上、提示安装失败的问题，现在在网络较慢的时候也能装好助手。

        ### 同步自 RikkaHub

        - 本次无面向用户的改动。

        Updates:

        > 4.1.4 is a tag only.

        ### Miffan changes

        - Away from home: partners reach your computer through the relay.

        ### Synced from RikkaHub

        - No user-facing changes in this release.
    """.trimIndent()

    @Test
    fun `picks the language part, drops placeholders and empty sections`() {
        val zh = parseReleaseNotes(body, chinese = true)
        assertEquals("4.1.4 只打了 tag。", zh.notice)
        assertEquals(listOf("Miffan 自有改动"), zh.sections.map { it.title })
        assertEquals(4, zh.items.size)

        val en = parseReleaseNotes(body, chinese = false)
        assertEquals("4.1.4 is a tag only.", en.notice)
        assertEquals(listOf("Miffan changes"), en.sections.map { it.title })
        assertEquals("Away from home", en.items.single().title)
    }

    @Test
    fun `headlines come from a bold lead, a short colon lead, or not at all`() {
        val items = parseReleaseNotes(body, chinese = true).items
        assertEquals("出门在外也能用电脑", items[0].title)
        assertEquals("不在电脑所在的网络时，伙伴可以经中转连回电脑。", items[0].body)
        assertEquals("电脑详情新增「出门在外」", items[1].title)
        assertEquals(listOf("今天经中转用了多少分钟；", "换一个中转。"), items[1].details)
        assertEquals("截图更省流量", items[2].title)
        assertEquals("几秒一刷。", items[2].body)
        assertNull(items[3].title)
    }

    @Test
    fun `a body without the marker or sections is not mistaken for notes`() {
        assertTrue(parseReleaseNotes("<p>HTML from the Atom feed</p>", chinese = true).isEmpty)
        assertEquals(1, parseReleaseNotes("- One change", chinese = false).items.size)
    }
}
