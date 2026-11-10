package me.ayuilos.miffan.ui.components.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A streamed reply is parsed by continuing the previous parse ([MarkdownParseResult.continuedBy]). Whatever the
 * text and wherever the chunks fall, the blocks must be the ones a parse of the whole text gives.
 */
class MarkdownIncrementalParseTest {
    private fun dump(node: ASTNode, content: String, out: StringBuilder, depth: Int = 0) {
        out.append("  ".repeat(depth)).append(node.type)
        if (node.children.isEmpty()) out.append(' ').append(content.substring(node.startOffset, node.endOffset).replace("\n", "\\n"))
        out.append('\n')
        node.children.forEach { dump(it, content, out, depth + 1) }
    }

    /** Everything the renderer reads: each block's tree and text, and whether a top-level paragraph is the last node. */
    private fun MarkdownParseResult.dump(): String = buildString {
        blocks.forEach { block ->
            dump(block.node, block.content, this)
            if (block.node.type == MarkdownElementTypes.PARAGRAPH) {
                append(if (block.node.parent?.children?.last() === block.node) "(last)\n" else "(followed)\n")
            }
        }
        append("html=").append(hasHtml)
    }

    private fun assertStreamsLikeAWholeParse(text: String, step: Int) {
        var streamed = parseMarkdown("")
        var end = 0
        while (end < text.length) {
            end = minOf(text.length, end + step)
            val prefix = text.substring(0, end)
            streamed = streamed.continuedBy(prefix)
            assertEquals("after ${prefix.length} of ${text.length} characters, step $step", parseMarkdown(prefix).dump(), streamed.dump())
        }
    }

    private val documents = listOf(
        // What the performance fixture's fake model streams: headings, lists, a table, code, math, a quote, tasks.
        """
        # 下面是我的整理

        先说结论：**可以做，而且不复杂**。我把思路、步骤和注意事项分开写，方便你对照着看。

        ## 一、思路

        核心就是三件事：先把范围*收窄*，再把重复的部分合并，最后留出检查的时间。

        1. 先列出所有要处理的条目，不要急着分类
        2. 按「影响大、改动小」的顺序排优先级
        3. 每完成一项就打勾，别攒到最后
        4. 预留 `20%` 的时间处理意外

        ## 二、对比

        | 方案 | 耗时 | 风险 | 适合场景 |
        | --- | --- | --- | --- |
        | 直接改 | 1 小时 | 中 | 改动很小 |
        | 先加测试再改 | 3 小时 | 低 | 逻辑复杂 |

        ```kotlin
        fun plan(items: List<String>): List<String> =
            items.distinct()
                .sortedBy { it.length }

        fun main() {
            println(plan(listOf("写草稿", "改草稿")).joinToString("\n"))
        }
        ```

        Here is the short English version: start small, validate each step.

        ## 三、估算

        总耗时可以粗略写成

        $$
        T = \sum_{i=1}^{n} t_i \cdot (1 + r_i)
        $$

        其中 ${'$'}t_i$ 是第 ${'$'}i$ 项的基础耗时，把 \(r_i\) 压到 ${'$'}0.1$ 以下。

        \[
        E = mc^2
        \]

        > 小提醒：估算只是估算。遇到和预期差得很远的情况，
        > 先停下来看看是不是前提错了。

        ## 四、检查清单

        - [x] 范围确认
        - [ ] 同步给相关的人

        如果你愿意，我可以把第三、第四项也帮你排进日程。要这样吗？
        """.trimIndent(),
        // Block structure that depends on what follows: setext headings, lazy continuation, loose and nested lists,
        // indented code, thematic breaks, a paragraph a list interrupts.
        """
        Title
        =====

        A paragraph
        that goes on
        - then a list
        - of two

        Second
        ------

        1. one

           still one

        2. two
           - nested a
           - nested b

             nested paragraph
        3. three

        ***

            indented code
            more code

        > quote
        continued lazily

        > another quote
        >
        > - with a list

        after
        a hard break

        7. a list that starts at seven
        8. eight
        """.trimIndent(),
        // Fences: one never closed until late, tildes, a fence inside a list, text right after a fence.
        """
        Before

        ```
        # not a heading

        - not a list
        ```
        right after

        ~~~python
        print("```")
        ~~~

        - item

          ```sh
          ls -la
          ```

        - next

        ```json
        {"unclosed": true}
        """.trimIndent(),
        // Links, bare URLs, autolinks, images, inline code, emphasis that closes late, a table after text.
        """
        详见 https://example.com/a，然后点登录。也可以看 [文档](https://example.com/docs "标题") 和 <https://example.com/b>。

        ![图](https://example.com/x.png)

        这一段有 `行内代码`、**加粗
        跨行**、~~删除~~ 和 *斜体*。

        名称 | 值
        --- | ---
        a | 1
        b | 2

        最后一行 www.example.com
        """.trimIndent(),
        // HTML switches the whole message to the other renderer; its blocks end by their own rules.
        """
        Text first

        <div>
        <b>bold</b>

        still html?
        </div>

        <!-- a comment

        that spans -->

        Inline <span>html</span> here.

        <details>
        <summary>More</summary>

        Hidden text

        </details>

        The end
        """.trimIndent(),
        // Link reference definitions change how earlier text reads.
        """
        See [the guide] and [another][ref].

        More text here.

        [the guide]: https://example.com/guide
        [ref]: https://example.com/ref "Ref"

        And [the guide] again.
        """.trimIndent(),
        // Lines that start like a paragraph and end up in the block before them, or the other way round.
        """
        - one
        - two

        - three, still the same list

        * another bullet, another list

        2024 年的计划：

        1. 第一项

        2024. 这其实是列表项

        10 个苹果

        + plus list

        +1 不是列表

        - [ ] task

        -5 度

        > quote

        > second quote
        lazy line

        | a | b |
        | - | - |
        | 1 | 2 |
        a line right after the table

        | c | d |
        | - | - |

        1) paren list
        2) second

        end
        """.trimIndent(),
        // Nested containers and a long list followed by text.
        """
        > - a list in a quote
        >   1. nested
        >   2. nested two
        >
        > ```
        > code in a quote
        > ```

        - a
          - b
            - c

              deep paragraph

        - d

        text

          - indented list after text

        final **bold
        """.trimIndent(),
        // Link reference definitions inside a quote and a list item, after the text that uses them.
        """
        See [the guide] and [another][ref] and [third].

        More text here.

        > [the guide]: https://example.com/guide
        > quoted text

        - item
          [ref]: https://example.com/ref "Ref"
        - [third]: https://example.com/third

        And [the guide] again.
        """.trimIndent(),
        // Windows line endings and trailing blank lines.
        "line one\r\nline two\r\n\r\n- a\r\n- b\r\n\r\n```\r\ncode\r\n```\r\n\r\nend\r\n\r\n\r\n",
    )

    @Test
    fun streamedParseMatchesWholeParseAtEveryCharacter() {
        documents.forEach { assertStreamsLikeAWholeParse(it, step = 1) }
    }

    @Test
    fun streamedParseMatchesWholeParseForLargerChunks() {
        documents.forEach { text -> listOf(2, 3, 7, 16, 61).forEach { assertStreamsLikeAWholeParse(text, it) } }
    }

    /** Lines of every kind in every order: the block rules interact in more ways than hand-written documents cover. */
    @Test
    fun streamedParseMatchesWholeParseForRandomDocuments() {
        val lines = listOf(
            "", "", "", "plain text", "more text with **bold** and `code`", "# heading", "## heading two", "#no space",
            "- bullet", "- [ ] task", "* star bullet", "+ plus bullet", "  - nested bullet", "    - deeper bullet",
            "1. first", "2. second", "10. tenth", "3) paren", "   continued item text", "2024 年", "-5 度", "+1",
            "| a | b |", "| --- | --- |", "| 1 | 2 |", "a | b", "--- | ---", "x | y",
            "```", "```kotlin", "~~~", "val x = 1", "    indented code", "\tindented with a tab",
            "> quote", "> - quoted list", ">", "lazy continuation",
            "---", "***", "===", "___", "Setext title",
            "<div>", "</div>", "<!-- comment", "-->", "<b>inline html</b> text", "<details>",
            "[ref]: https://example.com", "see [ref] and https://example.com/x。", "![img](https://example.com/i.png)",
            "${'$'}${'$'}", "x = \\frac{1}{2}", "inline \\(a + b\\) math", "\\[", "\\]", "text with ${'$'}x${'$'} dollar",
            "  two leading spaces", "trailing spaces  ", "&gt; entity", "\\# escaped",
        )
        val random = java.util.Random(20261110)
        repeat(500) { round ->
            val text = (1..(4 + random.nextInt(12))).joinToString("\n") { lines[random.nextInt(lines.size)] }
            try {
                assertStreamsLikeAWholeParse(text, step = 1)
                assertStreamsLikeAWholeParse(text, step = 1 + random.nextInt(9))
            } catch (e: AssertionError) {
                throw AssertionError("document $round:\n$text\n\n${e.message}", e)
            }
        }
    }

    @Test
    fun finishedBlocksKeepTheirNodes() {
        val first = parseMarkdown("# Title\n\nFirst paragraph.\n\nSecond para")
        val second = first.continuedBy("# Title\n\nFirst paragraph.\n\nSecond paragraph, now longer.")
        // Compose skips a block whose node and text are the same objects.
        assertSame(first.blocks[0].node, second.blocks[0].node)
        assertSame(first.blocks[0].content, second.blocks[0].content)
        val firstParagraph = first.blocks.indexOfFirst { it.node.type == MarkdownElementTypes.PARAGRAPH }
        assertSame(first.blocks[firstParagraph].node, second.blocks[firstParagraph].node)
        assertNotSame(first.blocks.last().node, second.blocks.last().node)
        assertEquals("Second paragraph, now longer.", second.blocks.last().text)
    }

    @Test
    fun unrelatedTextIsParsedWhole() {
        val first = parseMarkdown("# Title\n\nFirst paragraph.\n\nSecond")
        val other = "# Another title\n\nDifferent text.\n\nSecond"
        assertEquals(parseMarkdown(other).dump(), first.continuedBy(other).dump())
        assertSame(first, first.continuedBy(first.source))
    }

    @Test
    fun aLongReplyOnlyParsesItsLastBlock() {
        val paragraph = "这是一段比较长的文字，用来模拟一条很长的回复里的一个段落。".repeat(4)
        val body = (1..200).joinToString("\n\n") { "第 $it 段：$paragraph" }
        var result = parseMarkdown(body)
        val kept = result.blocks.first().node
        repeat(50) { result = result.continuedBy(result.source + " 再多几个字") }
        assertSame(kept, result.blocks.first().node)
        assertTrue(result.blocks.size >= 200)
        assertEquals(parseMarkdown(result.source).dump(), result.dump())
    }
}
