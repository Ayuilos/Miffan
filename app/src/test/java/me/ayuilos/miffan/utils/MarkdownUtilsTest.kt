package me.ayuilos.miffan.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownUtilsTest {
    @Test fun snakeCaseAndEmbeddedDoubleUnderscoresRemainIntact() {
        listOf("P4_MEMBER_OK", "snake_case", "snake_case_name", "version_2_ready", "2_value_3",
            "foo__bar__baz", "foo__bar__", "__bar__baz", "foo_bar_", "_bar_baz",
            "中文_内容_名称", "中文__内容__名称").forEach { text ->
            assertEquals(text, text.stripMarkdown())
        }
    }

    @Test fun standaloneUnderscoreEmphasisStripsMarkersAtWhitespaceAndPunctuationBoundaries() {
        assertEquals("italic bold", "_italic_ __bold__".stripMarkdown())
        assertEquals("(italic), [bold]!", "(_italic_), [__bold__]!".stripMarkdown())
        assertEquals("斜体 粗体", "_斜体_ __粗体__".stripMarkdown())
        assertEquals("_word_z and italic", "_word_z and _italic_".stripMarkdown())
        assertEquals("__word__z and bold", "__word__z and __bold__".stripMarkdown())
    }

    @Test fun underscoresNextToLettersOrNumbersAreNotEmphasisEvenAtOneBoundary() {
        listOf("a_word_", "_word_z", "1_word_", "_word_2", "a__word__", "__word__z",
            "1__word__", "__word__2", "é_word_", "_word_文", "٣_word_").forEach { text ->
            assertEquals(text, text.stripMarkdown())
        }
    }

    @Test fun asteriskFormattingKeepsItsExistingBehaviorAndSnakeCaseSurvivesOtherMarkdown() {
        assertEquals("italic bold", "*italic* **bold**".stripMarkdown())
        assertEquals("foobarbaz foobarbaz", "foo*bar*baz foo**bar**baz".stripMarkdown())
        assertEquals("P4_MEMBER_OK\nsnake_case\nP4_MEMBER_OK", "# **P4_MEMBER_OK**\n- [snake_case](https://example.com)\n> **P4_MEMBER_OK**".stripMarkdown())
    }
}
