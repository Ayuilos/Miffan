package me.ayuilos.miffan.data.thread

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import me.ayuilos.miffan.data.model.MessageNode
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ThreadDigestCacheTest {
    private val utc = TimeZone.UTC

    private fun node(role: MessageRole, minute: Int, vararg parts: UIMessagePart) =
        MessageNode(message = UIMessage(role = role, parts = parts.toList(), createdAt = LocalDateTime(2026, 10, 5, 21, minute)))

    private val newestFirst = listOf(
        node(MessageRole.ASSISTANT, 5, UIMessagePart.Text("三")),
        node(MessageRole.ASSISTANT, 4, UIMessagePart.Text("二")),
        node(MessageRole.USER, 3, UIMessagePart.Text("一")),
    )

    @Test fun digestSummaryMatchesSummarizeForEveryReadTime() {
        val digest = ThreadListSummaries.digest(newestFirst, utc)
        for (minute in 0..6) {
            val readAt = Instant.parse("2026-10-05T21:0${minute}:00Z").toEpochMilli()
            assertEquals(ThreadListSummaries.summarize(newestFirst, readAt, utc), digest.summary(readAt))
        }
    }

    @Test fun pendingToolsAreDetectedInAnyDigestedMessage() {
        val pending = UIMessagePart.Tool("t", "send_message", "{}", approvalState = ToolApprovalState.Pending)
        val withPending = listOf(node(MessageRole.ASSISTANT, 5, UIMessagePart.Text("等你")), node(MessageRole.ASSISTANT, 4, pending))
        assertTrue(ThreadListSummaries.digest(withPending, utc).hasPendingTool)
        assertFalse(ThreadListSummaries.digest(newestFirst, utc).hasPendingTool)
    }

    @Test fun anUnchangedConversationIsReadOnceNoMatterHowOftenItIsAskedFor() = runTest {
        var loads = 0
        val cache = ThreadDigestCache { loads++; ThreadListSummaries.digest(newestFirst, utc) }
        val id = Uuid.random()
        val stamp = ThreadStamp(updateAt = 10, nodeCount = 3, revisionSum = 3)
        val first = cache.digest(id, stamp)
        repeat(5) { assertSame(first, cache.digest(id, stamp)) }
        assertEquals(1, loads)
    }

    @Test fun otherConversationsDoNotDisturbEachOther() = runTest {
        var loads = 0
        val cache = ThreadDigestCache { loads++; ThreadListSummaries.digest(newestFirst, utc) }
        val a = Uuid.random(); val b = Uuid.random()
        cache.digest(a, ThreadStamp(1, 1, 1)); cache.digest(b, ThreadStamp(1, 1, 1))
        cache.digest(a, ThreadStamp(1, 1, 1)); cache.digest(b, ThreadStamp(1, 1, 1))
        assertEquals(2, loads)
    }

    @Test fun anyStampChangeReloadsOnlyThatConversation() = runTest {
        val loaded = mutableListOf<Uuid>()
        val cache = ThreadDigestCache { loaded += it; ThreadListSummaries.digest(newestFirst, utc) }
        val id = Uuid.random()
        val base = ThreadStamp(updateAt = 10, nodeCount = 3, revisionSum = 3, zoneId = "UTC")
        cache.digest(id, base)
        cache.digest(id, base.copy(updateAt = 11))
        cache.digest(id, base.copy(updateAt = 11, nodeCount = 4))
        cache.digest(id, base.copy(updateAt = 11, nodeCount = 4, revisionSum = 5))
        cache.digest(id, base.copy(updateAt = 11, nodeCount = 4, revisionSum = 5, zoneId = "Asia/Shanghai"))
        assertEquals(5, loaded.size)
    }

    @Test fun retainOnlyForgetsConversationsThatAreNoLongerNewest() = runTest {
        var loads = 0
        val cache = ThreadDigestCache { loads++; ThreadListSummaries.digest(newestFirst, utc) }
        val kept = Uuid.random(); val dropped = Uuid.random()
        val stamp = ThreadStamp(1, 1, 1)
        cache.digest(kept, stamp); cache.digest(dropped, stamp)
        cache.retainOnly(setOf(kept))
        cache.digest(kept, stamp); cache.digest(dropped, stamp)
        assertEquals(3, loads)
    }
}
