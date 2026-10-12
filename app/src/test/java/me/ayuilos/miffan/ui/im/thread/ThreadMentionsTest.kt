package me.ayuilos.miffan.ui.im.thread

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import me.ayuilos.miffan.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadMentionsTest {
    private val b = Assistant(name = "小B")
    private val bot = Assistant(name = "小Bot")
    private val writer = Assistant(name = "Writing buddy")

    private fun typed(text: String) = TextFieldValue(text, TextRange(text.length))

    @Test
    fun `finds the @ being typed`() {
        assertEquals(MentionQuery(0, ""), typed("@").mentionQuery())
        assertEquals(MentionQuery(2, "小"), typed("问问@小").mentionQuery())
        assertEquals(MentionQuery(3, "Wri"), typed("hi @Wri").mentionQuery())
    }

    @Test
    fun `ignores emails, finished words and selections`() {
        assertNull(typed("me@mail").mentionQuery())
        assertNull(typed("@小B 帮我").mentionQuery())
        assertNull(TextFieldValue("@小", TextRange(0, 2)).mentionQuery())
        assertNull(typed("no mention").mentionQuery())
    }

    @Test
    fun `candidates put name prefixes first and drop a finished name`() {
        val partners = listOf(writer, bot, b)
        fun names(query: String) = mentionCandidates(partners, query, "默认助手").map { it.name }
        assertEquals(listOf("Writing buddy", "小Bot", "小B"), names(""))
        assertEquals(listOf("小Bot", "小B"), names("小"))
        assertEquals(listOf("Writing buddy"), names("wri"))
        assertEquals(listOf("小Bot"), names("小B"))
    }

    @Test
    fun `a blank-named partner is offered under its shown name`() {
        val blank = Assistant(name = "")
        assertEquals(listOf("默认助手"), mentionCandidates(listOf(blank), "", "默认助手").map { it.name })
        assertEquals(true, hasPartnerMention("@默认助手 你好", listOf(blank)))
    }

    @Test
    fun `inserting replaces the query and leaves one space`() {
        val field = TextFieldValue("问问@小 他", TextRange(4))
        val next = field.insertMention(requireNotNull(field.mentionQuery()), "小B")
        assertEquals("问问@小B 他", next.text)
        assertEquals(TextRange(6), next.selection)
    }

    @Test
    fun `sent text marks only known partners`() {
        assertEquals(true, hasPartnerMention("@小Bot 你好", listOf(b, bot)))
        assertEquals(false, hasPartnerMention("@小C 你好", listOf(b, bot)))
        val styled = mentionText("@小Bot 和 @小B", listOf(b, bot), androidx.compose.ui.text.SpanStyle())
        // The text (what copying yields) is unchanged; each @ carries an avatar and the name is styled.
        assertEquals("@小Bot 和 @小B", styled.text)
        assertEquals(listOf(1 to 5, 9 to 11), styled.spanStyles.map { it.start to it.end })
        assertEquals(listOf(mentionAvatarKey(bot.id), mentionAvatarKey(b.id)),
            styled.getStringAnnotations(0, styled.length).map { it.item })
    }

    private fun at(text: String, start: Int, end: Int = start) = TextFieldValue(text, TextRange(start, end))

    @Test
    fun `backspace at a chip's end removes the whole chip`() {
        val result = keepChipsWhole(at("@小B 你好", 3), at("@小 你好", 2), listOf(b))
        assertEquals(" 你好", result.text)
        assertEquals(TextRange(0), result.selection)
    }

    @Test
    fun `backspacing the space after a chip removes the chip with it`() {
        val result = keepChipsWhole(at("问@小B 你", 5), at("问@小B你", 4), listOf(b))
        assertEquals("问你", result.text)
        assertEquals(TextRange(1), result.selection)
    }

    @Test
    fun `deleting a selection that cuts into a chip takes the chip`() {
        val result = keepChipsWhole(at("@小B 你好", 2, 5), at("@小好", 2), listOf(b))
        assertEquals("好", result.text)
        assertEquals(TextRange(0), result.selection)
    }

    @Test
    fun `typing over part of a chip replaces the chip`() {
        val result = keepChipsWhole(at("问@小B", 3, 4), at("问@小x", 4), listOf(b))
        assertEquals("问x", result.text)
        assertEquals(TextRange(2), result.selection)
    }

    @Test
    fun `ordinary edits around a chip pass through`() {
        assertEquals(at("@小B x", 5), keepChipsWhole(at("@小B ", 4), at("@小B x", 5), listOf(b)))
        assertEquals(at("@小B  ", 5), keepChipsWhole(at("@小B ", 4), at("@小B  ", 5), listOf(b)))
        assertEquals(at("hello", 4), keepChipsWhole(at("hell", 4), at("hello", 4), listOf(b)))
    }

    @Test
    fun `the cursor never rests inside a chip`() {
        assertEquals(TextRange(0), keepChipsWhole(at("@小B 你", 5), at("@小B 你", 2), listOf(b)).selection)
        assertEquals(TextRange(3), keepChipsWhole(at("@小B 你", 0), at("@小B 你", 1), listOf(b)).selection)
        assertEquals(TextRange(0, 5), keepChipsWhole(at("@小B 你", 4), at("@小B 你", 1, 5), listOf(b)).selection)
    }
}
