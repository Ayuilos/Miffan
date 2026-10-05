package me.ayuilos.miffan.data.thread

import kotlinx.datetime.LocalDateTime
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageRef
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid

class ThreadTimelineTest {
    private val assistant = Uuid.random()
    private val utc = ZoneOffset.UTC

    private fun at(hour: Int, minute: Int, second: Int = 0, day: Int = 5) =
        LocalDateTime(2026, 10, day, hour, minute, second)

    private fun msg(role: MessageRole, text: String, time: LocalDateTime) =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)), createdAt = time)

    private fun segment(createdMinute: Int, vararg messages: UIMessage): Conversation =
        messages.fold(
            Conversation(
                assistantId = assistant,
                messageNodes = emptyList(),
                createAt = Instant.parse("2026-10-05T21:00:00Z").plusSeconds(createdMinute * 60L),
            )
        ) { conversation, message -> conversation.appendMessage(message) }

    private fun List<TimelineItem>.messages() = filterIsInstance<TimelineItem.Message>()
    private fun List<TimelineItem>.text(text: String) =
        messages().single { it.message.previewText() == text }

    @Test
    fun parallelBatchQuotesEveryReplyEvenWhenOneFollowsItsQuestion() {
        val rainQ = msg(MessageRole.USER, "明天杭州会下雨吗", at(21, 3, 0))
        val catQ = msg(MessageRole.USER, "帮我想个猫的名字", at(21, 3, 10))
        val rainA = msg(MessageRole.ASSISTANT, "明天可能有雨", at(21, 3, 20))
        val tomatoQ = msg(MessageRole.USER, "番茄炒蛋先放哪个", at(21, 3, 30))
        val tomatoA = msg(MessageRole.ASSISTANT, "先炒鸡蛋", at(21, 3, 40))
        val catA = msg(MessageRole.ASSISTANT, "叫团子", at(21, 3, 50))
        val rain = segment(0, rainQ, rainA)
        val cat = segment(1, catQ, catA)
        val tomato = segment(2, tomatoQ, tomatoA)

        val items = ThreadTimeline.build(listOf(tomato, cat, rain), zone = utc)

        assertTrue(items.first() is TimelineItem.DateSeparator)
        assertEquals(1, items.count { it is TimelineItem.DateSeparator })
        assertEquals(
            listOf("明天杭州会下雨吗", "帮我想个猫的名字", "明天可能有雨", "番茄炒蛋先放哪个", "先炒鸡蛋", "叫团子"),
            items.messages().map { it.message.previewText() },
        )
        assertEquals("明天杭州会下雨吗", items.text("明天可能有雨").quote?.preview)
        assertEquals("番茄炒蛋先放哪个", items.text("先炒鸡蛋").quote?.preview)
        assertEquals("帮我想个猫的名字", items.text("叫团子").quote?.preview)
        assertNull(items.text("明天杭州会下雨吗").quote)
        assertFalse(items.text("先炒鸡蛋").quote!!.explicit)
        // Each segment keeps its own topic index for coloring.
        assertEquals(setOf(0, 1, 2), items.messages().map { it.topicIndex }.toSet())
        assertEquals(items.text("叫团子").topicIndex, items.text("帮我想个猫的名字").topicIndex)
        // Consecutive messages from the same sender are grouped.
        assertTrue(items.text("帮我想个猫的名字").groupedWithPrevious)
        assertFalse(items.text("先炒鸡蛋").groupedWithPrevious)
        assertTrue(items.text("叫团子").groupedWithPrevious)
    }

    @Test
    fun singleLineConversationShowsNoQuotes() {
        val conversation = segment(
            0,
            msg(MessageRole.USER, "你好", at(9, 0)),
            msg(MessageRole.ASSISTANT, "你好呀", at(9, 0, 5)),
            msg(MessageRole.USER, "今天做什么", at(9, 1)),
            msg(MessageRole.ASSISTANT, "散步吧", at(9, 1, 5)),
        )
        val items = ThreadTimeline.build(listOf(conversation), zone = utc)
        assertTrue(items.messages().all { it.quote == null })
        assertTrue(items.text("散步吧").canRegenerate)
        assertFalse(items.text("你好呀").canRegenerate)
    }

    @Test
    fun sequentialTopicsInDifferentSegmentsDoNotQuote() {
        val first = segment(0, msg(MessageRole.USER, "a", at(9, 0)), msg(MessageRole.ASSISTANT, "b", at(9, 0, 5)))
        val second = segment(1, msg(MessageRole.USER, "c", at(9, 2)), msg(MessageRole.ASSISTANT, "d", at(9, 2, 5)))
        val items = ThreadTimeline.build(listOf(first, second), zone = utc)
        assertTrue(items.messages().all { it.quote == null })
    }

    @Test
    fun explicitReplyQuotesItsTargetAcrossSegments() {
        val answer = msg(MessageRole.ASSISTANT, "芝麻，适合小黑猫", at(9, 0, 5))
        val first = segment(0, msg(MessageRole.USER, "猫名", at(9, 0)), answer)
        val second = Conversation(assistantId = assistant, messageNodes = emptyList()).appendMessage(
            msg(MessageRole.USER, "再来一个", at(9, 5)),
            replyTo = MessageRef(first.id, answer.id),
        )
        val items = ThreadTimeline.build(listOf(first, second), zone = utc)
        val quote = items.text("再来一个").quote!!
        assertTrue(quote.explicit)
        assertEquals("芝麻，适合小黑猫", quote.preview)
        assertEquals(items.text("芝麻，适合小黑猫").topicIndex, quote.topicIndex)
    }

    @Test
    fun longPausesAndNewDaysStartTimeLabels() {
        val conversation = segment(
            0,
            msg(MessageRole.USER, "1", at(9, 0)),
            msg(MessageRole.ASSISTANT, "2", at(9, 10)),
            msg(MessageRole.USER, "3", at(9, 50)),
            msg(MessageRole.USER, "4", at(9, 51, day = 6)),
        )
        val items = ThreadTimeline.build(listOf(conversation), zone = utc)
        assertEquals(3, items.count { it is TimelineItem.DateSeparator })
        assertFalse(items.text("2").groupedWithPrevious)
        assertFalse(items.text("4").groupedWithPrevious)
    }

    @Test
    fun generatingSegmentsShowTypingUntilTheReplyStreams() {
        val waiting = segment(0, msg(MessageRole.USER, "在吗", at(9, 0)))
        val streamingReply = msg(MessageRole.ASSISTANT, "我在", at(9, 0, 3))
        val streaming = segment(1, msg(MessageRole.USER, "嗨", at(9, 0, 1)), streamingReply)

        val items = ThreadTimeline.build(listOf(waiting, streaming), generatingSegmentIds = setOf(waiting.id, streaming.id), zone = utc)

        assertEquals(TimelineItem.Typing(setOf(waiting.id)), items.last())
        assertTrue(items.text("我在").streaming)
        assertFalse(items.text("我在").canRegenerate)
    }

    @Test
    fun topicFilterKeepsOneSegmentWithoutAutomaticQuotes() {
        val rain = segment(0, msg(MessageRole.USER, "rain?", at(9, 0)), msg(MessageRole.ASSISTANT, "yes", at(9, 0, 20)))
        val cat = segment(1, msg(MessageRole.USER, "cat?", at(9, 0, 10)), msg(MessageRole.ASSISTANT, "Tuanzi", at(9, 0, 30)))
        val items = ThreadTimeline.build(listOf(rain, cat), filterSegmentId = cat.id, zone = utc)
        assertEquals(listOf("cat?", "Tuanzi"), items.messages().map { it.message.previewText() })
        assertTrue(items.messages().all { it.quote == null })
    }

    @Test
    fun presetMessagesSharedBySegmentsAppearOnce() {
        val preset = msg(MessageRole.ASSISTANT, "我是写作搭子", at(8, 0))
        val first = segment(0, preset, msg(MessageRole.USER, "a", at(9, 0)))
        val second = segment(1, preset, msg(MessageRole.USER, "b", at(10, 0)))
        val items = ThreadTimeline.build(listOf(first, second), zone = utc)
        assertEquals(1, items.messages().count { it.message.id == preset.id })
    }

    @Test
    fun noticesFollowTheirTriggerAndBreakGrouping() {
        val trigger = msg(MessageRole.USER, "以后简短一点", at(9, 0))
        val conversation = segment(0, trigger, msg(MessageRole.ASSISTANT, "好", at(9, 0, 5)))
        val notice = ThreadNotice(
            id = "r1",
            at = Instant.parse("2026-10-05T09:00:03Z"),
            kind = ThreadNoticeKind.SETTINGS,
            summary = "回答更简短",
            trigger = MessageRef(conversation.id, trigger.id),
            revisionId = "r1",
            undoable = true,
        )
        val loose = notice.copy(id = "r2", trigger = null, at = Instant.parse("2026-10-05T10:00:00Z"))
        val items = ThreadTimeline.build(listOf(conversation), notices = listOf(loose, notice), zone = utc)
        val keys = items.map { it.key }
        assertEquals(keys.indexOf("msg-${trigger.id}") + 1, keys.indexOf("notice-r1"))
        assertEquals("notice-r2", keys.last())
    }
}
