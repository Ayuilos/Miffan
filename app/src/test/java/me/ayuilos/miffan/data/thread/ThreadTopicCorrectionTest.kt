package me.ayuilos.miffan.data.thread

import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.*
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.service.ChatService
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import android.util.Log
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid

class ThreadTopicCorrectionTest {
    @Before fun mockLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
    }
    @After fun restoreLog() = unmockkStatic(Log::class)

    private val assistant = Assistant()
    private val content = listOf(UIMessagePart.Text("another topic"))
    private fun message(role: MessageRole, minute: Int) = UIMessage(role = role,
        parts = listOf(UIMessagePart.Text("$role $minute")), createdAt = LocalDateTime(2026, 10, 1, 9, minute))
    private fun segment(minute: Int, vararg messages: UIMessage) = Conversation.linear(
        assistantId = assistant.id, messages = messages.toList()).copy(
        createAt = Instant.parse("2026-10-01T09:00:00Z").plusSeconds(minute * 60L),
        updateAt = Instant.now(), topicStart = TopicStart.AUTO)

    private inner class Fixture(vararg segments: Conversation) {
        val data = segments.associateBy { it.id }.toMutableMap()
        val chat = mockk<ChatService>(relaxed = true)
        val repo = mockk<ConversationRepository>()
        val router = mockk<SegmentRouter>()
        val summarizer = mockk<SegmentSummarizer>(relaxed = true)
        val store = mockk<SettingsStore>()
        val service: ThreadService
        init {
            every { store.settingsFlow } returns MutableStateFlow(Settings(assistants = listOf(assistant)))
            every { repo.getConversationsOfAssistant(assistant.id) } answers { flowOf(data.values.toList()) }
            coEvery { chat.readThreadSegment(any()) } answers { data[firstArg()] }
            coEvery { chat.openThreadSegment(any(), any(), any()) } coAnswers {
                val id = firstArg<Uuid>()
                data.putIfAbsent(id, Conversation(id = id, assistantId = assistant.id,
                    messageNodes = emptyList(), topicStart = thirdArg()))
                Unit
            }
            coEvery { chat.updateThreadSegment(any(), any()) } coAnswers {
                val id = firstArg<Uuid>()
                data[id]?.let { data[id] = secondArg<(Conversation) -> Conversation>()(it) }
                Unit
            }
            coEvery { chat.applyThreadMerge(any(), any()) } coAnswers {
                data.remove(firstArg<Conversation>().id)
                val target = secondArg<Conversation>()
                data[target.id] = target
            }
            coEvery { chat.restoreThreadMerge(any(), any()) } coAnswers {
                val source = firstArg<Conversation>(); val target = secondArg<Conversation>()
                data[source.id] = source; data[target.id] = target
            }
            coEvery { router.route(any()) } answers { RouteDecision.New("new") }
            service = ThreadService(chat, store, router, summarizer, repo)
        }
        suspend fun send(fresh: Boolean = false, reply: MessageRef? = null, topic: Uuid? = null, reviewedHistory: Boolean = false) =
            service.send(assistant.id, content, emptyList(), replyTo = reply, topicSegmentId = topic,
                freshStart = fresh, reviewedHistory = reviewedHistory)
    }

    @Test fun reviewedHistoryIsForwardedToRouterWithoutChangingExplicitTopicPriority() = runTest {
        val old = segment(0, message(MessageRole.USER, 0))
        val f = Fixture(old)
        coEvery { f.router.route(any()) } returns RouteDecision.Existing(old.id, "same")
        assertEquals(old.id, f.send(reviewedHistory = true))
        coVerify(exactly = 1) { f.router.route(match { it.reviewedHistory }) }
        assertEquals(old.id, f.send())
        coVerify(exactly = 1) { f.router.route(match { !it.reviewedHistory }) }

        clearMocks(f.router, answers = false)
        f.service.requestNewTopic(assistant.id)
        assertEquals(old.id, f.send(topic = old.id, reviewedHistory = true))
        assertTrue(assistant.id in f.service.pendingNewTopic.value)
        val id = f.send(reviewedHistory = true)
        assertEquals(TopicStart.OWNER, f.data.getValue(id).topicStart)
        assertFalse(assistant.id in f.service.pendingNewTopic.value)
        coVerify(exactly = 0) { f.router.route(any()) }
    }

    @Test fun newStartsAreWrittenWhenOpeningAndFirstSegmentHasNoReason() = runTest {
        for (start in TopicStart.entries) {
            val old = segment(0)
            val f = Fixture(old)
            coEvery { f.router.route(any()) } returns RouteDecision.New("test", start = start)
            val id = f.send()
            assertEquals(start, f.data.getValue(id).topicStart)
        }
        val f = Fixture()
        val id = f.send()
        assertNull(f.data.getValue(id).topicStart)
    }

    @Test fun welcomeBypassesClassifierAndClosesEvenOffPageSegments() = runTest {
        val old = segment(0); val parallel = segment(1)
        val f = Fixture(old, parallel)
        val id = f.send(fresh = true)
        assertEquals(TopicStart.WELCOME, f.data.getValue(id).topicStart)
        assertTrue(f.data.getValue(old.id).threadClosedAt > 0)
        assertTrue(f.data.getValue(parallel.id).threadClosedAt > 0)
        verify { f.summarizer.summarizeLater(match { it.toSet() == setOf(old.id, parallel.id) }) }
        coVerify(exactly = 0) { f.router.route(any()) }
    }

    @Test fun replyAndFilterOverrideWelcomeAndDoNotConsumeOwnerRequest() = runTest {
        val old = segment(0, message(MessageRole.USER, 0))
        val f = Fixture(old)
        f.service.requestNewTopic(assistant.id)
        val ref = MessageRef(old.id, old.currentMessages.first().id)
        assertEquals(old.id, f.send(fresh = true, reply = ref))
        assertEquals(old.id, f.send(fresh = true, topic = old.id))
        assertTrue(assistant.id in f.service.pendingNewTopic.value)
        assertEquals(0L, f.data.getValue(old.id).threadClosedAt)
        coVerify(exactly = 0) { f.router.route(any()) }
    }

    @Test fun approvalTargetOverridesWelcomeAndOwnerRequest() = runTest {
        val approval = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            toolCallId = "pending", toolName = "shell", input = "{}",
            approvalState = me.rerere.ai.ui.ToolApprovalState.Pending)))
        val old = segment(0, message(MessageRole.USER, 0), approval)
        val f = Fixture(old)
        f.service.requestNewTopic(assistant.id)
        assertEquals(old.id, f.send(fresh = true))
        assertTrue(assistant.id in f.service.pendingNewTopic.value)
        assertEquals(0L, f.data.getValue(old.id).threadClosedAt)
        coVerify(exactly = 0) { f.router.route(any()) }
    }

    @Test fun pendingOwnerStartWinsWelcomeIsConsumedAndCanBeCancelled() = runTest {
        val f = Fixture(segment(0))
        f.service.requestNewTopic(assistant.id)
        val id = f.send(fresh = true)
        assertEquals(TopicStart.OWNER, f.data.getValue(id).topicStart)
        assertFalse(assistant.id in f.service.pendingNewTopic.value)
        f.service.requestNewTopic(assistant.id)
        f.service.cancelNewTopic(assistant.id)
        coEvery { f.router.route(any()) } returns RouteDecision.Existing(id, "same")
        assertEquals(id, f.send())
        coVerify(exactly = 1) { f.router.route(any()) }
    }

    @Test fun multiTurnMergeInterleavesDeletesSourceAndUndoRestoresBothSnapshots() = runTest {
        val target = segment(0, message(MessageRole.USER, 0), message(MessageRole.ASSISTANT, 3))
            .copy(threadClosedAt = 2, threadSummary = "stale")
        val source = segment(1, message(MessageRole.USER, 1), message(MessageRole.ASSISTANT, 2),
            message(MessageRole.USER, 4), message(MessageRole.ASSISTANT, 5)).copy(topicStart = TopicStart.OWNER)
        val f = Fixture(target, source)
        assertTrue(f.service.mergeSegment(assistant.id, source.id, target.id) is MergeResult.Success)
        assertFalse(source.id in f.data)
        val merged = f.data.getValue(target.id)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), merged.currentMessages.map { it.createdAt.minute })
        assertEquals(0L, merged.threadClosedAt)
        assertEquals("", merged.threadSummary)
        verify(exactly = 0) { f.chat.regenerateAtMessage(any(), any(), any(), any()) }
        assertTrue(f.service.undoMerge(assistant.id) is MergeResult.Success)
        assertEquals(source, f.data[source.id])
        assertEquals(target, f.data[target.id])
    }

    @Test fun singleTurnRegeneratesOwnerAndPreservesLaterTargetMessagesAsContinuation() = runTest {
        val target = segment(0, message(MessageRole.USER, 0), message(MessageRole.ASSISTANT, 1),
            message(MessageRole.USER, 4), message(MessageRole.ASSISTANT, 5))
        val source = segment(2, message(MessageRole.USER, 2), message(MessageRole.ASSISTANT, 3))
        val f = Fixture(target, source)
        f.service.mergeSegment(assistant.id, source.id, target.id)
        verify { f.chat.regenerateAtMessage(target.id, source.currentMessages.first(), true,
            target.currentMessageNodes.takeLast(2).map { it.id }) }
        val merged = f.data.getValue(target.id)
        val owner = merged.getMessageNodeByMessageId(source.currentMessages.first().id)!!
        val prefix = merged.currentMessageNodes.takeWhile { it.id != owner.id } + owner
        val replacement = message(MessageRole.ASSISTANT, 6)
        val regenerated = merged.updateCurrentMessages(prefix.map { it.message } + replacement)
            .attachMergeContinuation(target.currentMessageNodes.takeLast(2).map { it.id })
        assertEquals(listOf(0, 1, 2, 6, 4, 5), regenerated.currentMessages.map { it.createdAt.minute })
        assertEquals(2, regenerated.getChildren(owner.id).size)
        f.data[target.id] = regenerated
        assertTrue(f.service.undoMerge(assistant.id) is MergeResult.Success)
        coVerify { f.chat.stopGeneration(target.id) }
        assertEquals(target, f.data[target.id])
    }

    @Test fun interleavedTargetReplyDoesNotStealTheOldSourceReplyBranch() {
        val target = segment(0, message(MessageRole.USER, 0), message(MessageRole.ASSISTANT, 2))
        val source = segment(1, message(MessageRole.USER, 1), message(MessageRole.ASSISTANT, 3))
        val plan = planSegmentMerge(source, target)
        val owner = source.currentMessageNodes.first()
        val reply = source.currentMessageNodes.last()
        val unchanged = plan.merged.attachMergeContinuation(plan.continuationIds, owner.id, listOf(reply.id))
        assertEquals(plan.merged, unchanged)
        val prefix = plan.merged.currentMessageNodes.takeWhile { it.id != owner.id } + owner
        val regenerated = plan.merged.updateCurrentMessages(prefix.map { it.message } + message(MessageRole.ASSISTANT, 4))
            .attachMergeContinuation(plan.continuationIds, owner.id, listOf(reply.id))
        assertEquals(listOf(0, 1, 4, 2), regenerated.currentMessages.map { it.createdAt.minute })
        assertEquals(owner.id, regenerated.getMessageNode(reply.id)?.parentId)
        assertEquals(2, regenerated.getChildren(owner.id).size)
    }

    @Test fun onlySelectedSourceBranchMovesAndTargetAlternativesSurvive() {
        val target = segment(0, message(MessageRole.USER, 0), message(MessageRole.ASSISTANT, 1))
        val targetAlternative = MessageNode(message = message(MessageRole.ASSISTANT, 2),
            parentId = target.currentMessageNodes.first().id)
        val branchedTarget = target.copy(messageNodes = target.messageNodes + targetAlternative)
        val source = segment(3, message(MessageRole.USER, 3), message(MessageRole.ASSISTANT, 4))
        val sourceAlternative = MessageNode(message = message(MessageRole.ASSISTANT, 5),
            parentId = source.currentMessageNodes.first().id)
        val plan = planSegmentMerge(source.copy(messageNodes = source.messageNodes + sourceAlternative), branchedTarget)
        assertNotNull(plan.merged.getMessageNode(targetAlternative.id))
        assertNull(plan.merged.getMessageNode(sourceAlternative.id))
        assertEquals(4, plan.merged.currentMessageNodes.size)
    }

    @Test fun generatingSourceOrTargetIsRejectedWithoutStopping() = runTest {
        val target = segment(0); val source = segment(1)
        for (busy in listOf(target, source)) {
            val f = Fixture(target, source)
            every { f.chat.isThreadSegmentBusy(busy.id) } returns true
            assertTrue(f.service.mergeSegment(assistant.id, source.id, target.id) is MergeResult.Failure)
            coVerify(exactly = 0) { f.chat.stopGeneration(any()) }
            assertEquals(2, f.data.size)
        }
    }

    @Test fun newOwnerMessageOrQueuedOwnerMessagePreventsUndo() = runTest {
        val target = segment(0); val source = segment(1, message(MessageRole.USER, 1))
        for (queued in listOf(false, true)) {
            val f = Fixture(target, source)
            f.service.mergeSegment(assistant.id, source.id, target.id)
            if (queued) every { f.chat.hasQueuedOwnerMessage(target.id) } returns true
            else f.data[target.id] = f.data.getValue(target.id).appendMessage(message(MessageRole.USER, 2))
            assertTrue(f.service.undoMerge(assistant.id) is MergeResult.Failure)
            coVerify(exactly = 0) { f.chat.stopGeneration(any()) }
            assertFalse(source.id in f.data)
        }
    }

    @Test fun boundariesPrecedeFirstVisibleMessageAndBreakGrouping() {
        val first = segment(0, message(MessageRole.USER, 0)).copy(topicStart = null)
        val next = segment(1, message(MessageRole.USER, 1), message(MessageRole.ASSISTANT, 2))
        val items = ThreadTimeline.build(listOf(next, first), zone = ZoneOffset.UTC)
        val boundary = items.filterIsInstance<TimelineItem.TopicBoundary>().single()
        assertEquals("boundary-${next.id}", boundary.key)
        assertTrue(boundary.mergeable)
        val after = items[items.indexOf(boundary) + 1] as TimelineItem.Message
        assertEquals(next.id, after.segmentId)
        assertFalse(after.groupedWithPrevious)
        assertFalse(ThreadTimeline.build(listOf(first, next), generatingSegmentIds = setOf(first.id))
            .filterIsInstance<TimelineItem.TopicBoundary>().single().mergeable)
    }

    @Test fun welcomeFilteredAndLegacySegmentsDoNotInsertBoundaries() {
        val source = segment(0, message(MessageRole.USER, 0))
        for (start in listOf(null, TopicStart.WELCOME)) {
            assertTrue(ThreadTimeline.build(listOf(source.copy(topicStart = start)))
                .none { it is TimelineItem.TopicBoundary })
        }
        assertTrue(ThreadTimeline.build(listOf(source), filterSegmentId = source.id)
            .none { it is TimelineItem.TopicBoundary })
    }

    @Test fun catalogIndicesStayStableAcrossPagesAndOffPageTargetsAllowMerge() {
        val older = segment(0, message(MessageRole.USER, 0))
        val newer = segment(1, message(MessageRole.USER, 1))
        val indices = mapOf(older.id to 4, newer.id to 5)
        val page = ThreadTimeline.build(listOf(newer), topicIndices = indices,
            earlierSegmentIds = mapOf(newer.id to setOf(older.id)))
        val expanded = ThreadTimeline.build(listOf(older, newer), topicIndices = indices)
        assertEquals(ThreadTimeline.build(listOf(newer)).filterIsInstance<TimelineItem.Message>().single().topicIndex,
            ThreadTimeline.build(listOf(older, newer)).filterIsInstance<TimelineItem.Message>().last().topicIndex)
        assertEquals(5, page.filterIsInstance<TimelineItem.Message>().single().topicIndex)
        assertEquals(5, expanded.filterIsInstance<TimelineItem.Message>().last().topicIndex)
        assertTrue(page.filterIsInstance<TimelineItem.TopicBoundary>().single().mergeable)
    }
}
