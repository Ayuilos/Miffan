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
        assertEquals(listOf(writer, bot, b), mentionCandidates(partners, ""))
        assertEquals(listOf(bot, b), mentionCandidates(partners, "小"))
        assertEquals(listOf(writer), mentionCandidates(partners, "wri"))
        assertEquals(listOf(bot), mentionCandidates(partners, "小B"))
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
        assertEquals(listOf(0 to 5, 8 to 11), styled.spanStyles.map { it.start to it.end })
    }
}
