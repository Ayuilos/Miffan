package me.ayuilos.miffan.data.thread

import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.db.dao.ThreadSegmentDigest
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid

class SmartSegmentRouterTest {
    private val assistant = Assistant()
    private val now = Instant.parse("2026-10-05T21:03:00Z")

    private fun segment(minutesAgo: Long, title: String = "", text: String = "q", closed: Boolean = false) =
        Conversation(
            assistantId = assistant.id,
            title = title,
            messageNodes = emptyList(),
            updateAt = now.minusSeconds(minutesAgo * 60),
            threadClosedAt = if (closed) 1L else 0L,
        ).appendMessage(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text))))

    private fun request(text: String, vararg segments: Conversation) =
        RouteRequest(assistant, listOf(UIMessagePart.Text(text)), segments.toList(), now)

    private class FakeClassifier(private val result: TopicClassification?) : TopicClassifier {
        var calls = 0
        var lastCandidates: List<TopicCandidate> = emptyList()
        override suspend fun classify(message: String, candidates: List<TopicCandidate>): TopicClassification? {
            calls++
            lastCandidates = candidates
            return result
        }
    }

    @Test
    fun emptyOrClosedThreadsStartANewSegmentWithoutAskingTheModel() = runBlocking {
        val classifier = FakeClassifier(TopicClassification.Existing(0))
        val router = SmartSegmentRouter(classifier)
        assertTrue(router.route(request("明天杭州会下雨吗")) is RouteDecision.New)
        assertTrue(router.route(request("明天杭州会下雨吗", segment(1, closed = true))) is RouteDecision.New)
        assertEquals(0, classifier.calls)
    }

    @Test
    fun idleThreadsCloseStaleSegmentsAndStartFresh() = runBlocking {
        val stale = segment(3 * 60)
        val decision = SmartSegmentRouter(FakeClassifier(null)).route(request("早上好", stale))
        assertEquals(RouteDecision.New("thread idle", closeSegmentIds = setOf(stale.id)), decision)
    }

    @Test
    fun clearlyUnrelatedMessagesOpenParallelSegments() = runBlocking {
        val rain = segment(1, title = "杭州天气")
        val decision = SmartSegmentRouter(FakeClassifier(TopicClassification.New(0.9))).route(request("帮我想个猫的名字", rain))
        assertTrue(decision is RouteDecision.New)
        assertEquals(emptySet<Uuid>(), (decision as RouteDecision.New).closeSegmentIds)
    }

    @Test
    fun uncertainOrFailedClassificationContinuesTheNewestSegment() = runBlocking {
        val older = segment(5, title = "猫的名字")
        val newest = segment(1, title = "杭州天气")
        val uncertain = SmartSegmentRouter(FakeClassifier(TopicClassification.New(0.5))).route(request("那后天呢", older, newest))
        assertEquals(newest.id, (uncertain as RouteDecision.Existing).segmentId)
        val failed = SmartSegmentRouter(FakeClassifier(null)).route(request("那后天呢", older, newest))
        assertEquals(newest.id, (failed as RouteDecision.Existing).segmentId)
    }

    @Test
    fun classifierCanRouteBackToAnOlderParallelTopic() = runBlocking {
        val cat = segment(2, title = "猫的名字")
        val tomato = segment(1, title = "番茄炒蛋")
        val classifier = FakeClassifier(TopicClassification.Existing(1))
        val decision = SmartSegmentRouter(classifier).route(request("再来两个名字", cat, tomato))
        assertEquals(listOf(tomato.id, cat.id), classifier.lastCandidates.map { it.segmentId })
        assertEquals(cat.id, (decision as RouteDecision.Existing).segmentId)
    }

    @Test
    fun shortFollowUpsSkipTheModel() = runBlocking {
        val classifier = FakeClassifier(TopicClassification.New(1.0))
        val newest = segment(1)
        val decision = SmartSegmentRouter(classifier).route(request("好的", newest))
        assertEquals(newest.id, (decision as RouteDecision.Existing).segmentId)
        assertEquals(0, classifier.calls)
    }

    @Test
    fun aFullSegmentIsClosedAndANewOneStarted() = runBlocking {
        val full = segment(1, text = "x".repeat(100))
        val decision = SmartSegmentRouter(FakeClassifier(null), maxSegmentChars = 50).route(request("继续说说吧", full))
        assertEquals(setOf(full.id), (decision as RouteDecision.New).closeSegmentIds)
    }

    @Test
    fun openingBeyondTheLimitClosesTheOldestActiveSegment() = runBlocking {
        val a = segment(3, title = "a")
        val b = segment(2, title = "b")
        val c = segment(1, title = "c")
        val decision = SmartSegmentRouter(FakeClassifier(TopicClassification.New(0.95))).route(request("完全不同的问题", a, b, c))
        assertEquals(setOf(a.id), (decision as RouteDecision.New).closeSegmentIds)
    }

    @Test
    fun classifierAnswersAreParsedDefensively() {
        assertEquals(TopicClassification.Existing(1), LlmTopicClassifier.parseClassification("""{"topic": 1, "confidence": 0.8}""", 3))
        assertEquals(TopicClassification.New(0.9), LlmTopicClassifier.parseClassification("```json\n{\"topic\": \"new\", \"confidence\": 0.9}\n```", 3))
        assertEquals(TopicClassification.New(1.0), LlmTopicClassifier.parseClassification("""{"topic":"NEW","confidence":7}""", 3))
        assertNull(LlmTopicClassifier.parseClassification("""{"topic": 5}""", 3))
        assertNull(LlmTopicClassifier.parseClassification("I think it is new", 3))
    }

    @Test
    fun earlierSummariesFormatNewestFirstWithDates() {
        val text = ThreadContext.format(
            listOf(
                ThreadSegmentDigest("a", "猫", "用户养了一只橘猫，取名团子", Instant.parse("2026-10-03T10:00:00Z").toEpochMilli()),
                ThreadSegmentDigest("b", "天气", "杭州下周多雨", Instant.parse("2026-10-01T10:00:00Z").toEpochMilli()),
            ),
            zone = ZoneOffset.UTC,
        )!!
        assertTrue(text.startsWith("<earlier_conversations>"))
        assertTrue(text.indexOf("(2026-10-03) 用户养了一只橘猫") < text.indexOf("(2026-10-01) 杭州下周多雨"))
        assertNull(ThreadContext.format(emptyList()))
    }
}
