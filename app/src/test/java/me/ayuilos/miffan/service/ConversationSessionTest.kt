package me.ayuilos.miffan.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `queued messages wait for completion and run one at a time in FIFO order`() = Fixture().use { f ->
        val current = Job().also(f.session::setJob)
        val first = message("first")
        val second = message("second").copy(answer = false)
        f.session.enqueueMessage(first)
        f.session.enqueueMessage(second)

        assertTrue(current.isActive)
        assertTrue(f.started.isEmpty())
        current.complete()
        assertEquals(listOf(first), f.started)
        assertEquals(listOf(second), f.session.messageQueue.value.messages)

        f.turns.last().complete()
        assertEquals(listOf(first, second), f.started)
        f.turns.last().complete()
        assertNull(f.session.getJob())
        assertTrue(f.session.messageQueue.value.messages.isEmpty())
    }

    @Test
    fun `stop pauses the queue without discarding messages or sending the next one`() = Fixture().use { f ->
        val current = Job().also(f.session::setJob)
        val pending = message("keep me")
        f.session.enqueueMessage(pending)
        assertEquals(listOf(current), f.session.stopGeneration())

        assertTrue(f.session.messageQueue.value.paused)
        assertEquals(listOf(pending), f.session.messageQueue.value.messages)
        assertTrue(f.started.isEmpty())
        assertTrue(f.session.isInUse)

        f.session.resumeQueue()
        assertEquals(listOf(pending), f.started)
    }

    @Test
    fun `failed generation pauses pending messages`() = Fixture().use { f ->
        val current = Job().also(f.session::setJob)
        val pending = message("after failure")
        f.session.enqueueMessage(pending)
        current.completeExceptionally(IllegalStateException("network failed"))

        assertTrue(f.session.messageQueue.value.paused)
        assertEquals(listOf(pending), f.session.messageQueue.value.messages)
        assertTrue(f.started.isEmpty())
    }

    @Test
    fun `handled errors can pause even when the coroutine completes normally`() = Fixture().use { f ->
        val current = Job().also(f.session::setJob)
        f.session.enqueueMessage(message("pending"))
        f.session.pauseQueue()
        current.complete()
        f.session.enqueueMessage(message("another"))

        assertTrue(f.session.messageQueue.value.paused)
        assertEquals(2, f.session.messageQueue.value.messages.size)
        assertTrue(f.started.isEmpty())
    }

    @Test
    fun `sending after a stop with no pending messages starts normally`() = Fixture().use { f ->
        f.session.pauseQueue()
        val next = message("new turn")
        f.session.enqueueMessage(next)
        assertEquals(listOf(next), f.started)
        assertFalse(f.session.messageQueue.value.paused)
    }

    @Test
    fun `queue does not skip tool approval and resumes after tool continuation`() = Fixture().use { f ->
        f.session.state.value = f.session.state.value.copy(
            messageNodes = listOf(
                MessageNode.of(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "approval",
                                toolName = "shell",
                                input = "{}",
                                approvalState = ToolApprovalState.Pending,
                            )
                        ),
                    )
                )
            )
        )
        val pending = message("after tool")
        f.session.enqueueMessage(pending)
        assertTrue(f.started.isEmpty())
        assertNull(f.session.takeNextQueuedMessage())

        val continuation = Job().also(f.session::setJob)
        f.session.state.value = f.session.state.value.copy(messageNodes = emptyList())
        continuation.complete()
        assertEquals(listOf(pending), f.started)
    }

    @Test
    fun `removing one queued message preserves the rest`() = Fixture().use { f ->
        val current = Job().also(f.session::setJob)
        val first = message("first")
        val removed = message("remove")
        val last = message("last")
        listOf(first, removed, last).forEach(f.session::enqueueMessage)
        assertEquals(removed, f.session.removeQueuedMessage(removed.id))
        assertNull(f.session.removeQueuedMessage(removed.id))
        current.complete()
        assertEquals(listOf(first), f.started)
        assertEquals(listOf(last), f.session.messageQueue.value.messages)
    }

    @Test
    fun `queued content is a snapshot and conversations remain isolated`() = Fixture().use { first ->
        Fixture().use { second ->
            Job().also(first.session::setJob)
            Job().also(second.session::setJob)
            val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text("original"))
            first.session.enqueueMessage(QueuedMessage(content = parts))
            parts.clear()
            assertEquals(listOf(UIMessagePart.Text("original")), first.session.messageQueue.value.messages.single().content)
            assertTrue(second.session.messageQueue.value.messages.isEmpty())
        }
    }

    @Test
    fun `empty input is ignored but attachment-only messages can be queued`() = Fixture().use { f ->
        Job().also(f.session::setJob)
        f.session.enqueueMessage(message("  \n"))
        f.session.enqueueMessage(QueuedMessage(content = emptyList()))
        val attachment = QueuedMessage(content = listOf(UIMessagePart.Image("file:///example.png")))
        f.session.enqueueMessage(attachment)
        assertEquals(listOf(attachment), f.session.messageQueue.value.messages)
    }

    @Test
    fun `immediate send waits for cancelled turn cleanup and retains the other queued messages`() = runBlocking {
        Fixture().use { f ->
            val cleanup = CompletableDeferred<Unit>()
            val current = f.session.launchGeneration {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
            val first = message("first")
            val priority = message("priority")
            listOf(first, priority).forEach(f.session::enqueueMessage)
            val chosen = f.session.removeQueuedMessage(priority.id)!!
            val finishPriority = CompletableDeferred<Unit>()
            var priorityStarted = false
            val replacement = f.session.launchGeneration(chosen) {
                priorityStarted = true
                f.commit(chosen)
                finishPriority.await()
            }

            assertTrue(current.isCancelled)
            assertFalse(priorityStarted)
            assertSame(replacement, f.session.getJob())
            cleanup.complete(Unit)
            current.join()
            assertTrue(priorityStarted)
            assertSame(replacement, f.session.getJob())
            assertFalse(f.session.messageQueue.value.paused)
            assertEquals(listOf(first), f.session.messageQueue.value.messages)

            finishPriority.complete(Unit)
            replacement.join()
            assertEquals(listOf(first), f.started)
        }
    }

    @Test
    fun `rapid repeated interruptions still wait for the earliest tool cleanup`() = runBlocking {
        Fixture().use { f ->
            val cleanup = CompletableDeferred<Unit>()
            val first = f.session.launchGeneration {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
            val interrupted = message("interrupted before being saved")
            val middle = f.session.launchGeneration(interrupted) { error("must not start") }
            val latestContent = message("latest")
            val finishLatest = CompletableDeferred<Unit>()
            var latestStarted = false
            val latest = f.session.launchGeneration(latestContent) {
                latestStarted = true
                f.commit(latestContent)
                finishLatest.await()
            }

            middle.join()
            assertFalse(latestStarted)
            assertSame(latest, f.session.getJob())
            assertEquals(listOf(interrupted), f.session.messageQueue.value.messages)
            cleanup.complete(Unit)
            first.join()
            assertTrue(latestStarted)
            assertSame(latest, f.session.getJob())
            finishLatest.complete(Unit)
            latest.join()
            assertEquals(listOf(interrupted), f.started)
        }
    }

    @Test
    fun `cancellation before coroutine starts restores the unsent message`() = runBlocking {
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation(assistantId = Uuid.random(), messageNodes = emptyList()),
            scope = this,
            onIdle = {},
        )
        try {
            val pending = message("not saved yet")
            val job = session.launchGeneration(pending) { error("must not start") }
            job.cancel()
            job.join()
            assertEquals(listOf(pending), session.messageQueue.value.messages)
            assertTrue(session.messageQueue.value.paused)
        } finally {
            session.cleanup()
        }
    }

    @Test
    fun `cancelling an already saved input does not put it back in the queue`() = runBlocking {
        Fixture().use { f ->
            val content = message("already in history")
            val job = f.session.launchGeneration(content) {
                f.commit(content)
                awaitCancellation()
            }
            job.cancel()
            job.join()
            assertTrue(f.session.messageQueue.value.messages.isEmpty())
            assertTrue(f.session.messageQueue.value.paused)
        }
    }

    @Test
    fun `cleanup discards pending state without launching another turn`() = Fixture().use { f ->
        val current = Job().also(f.session::setJob)
        f.session.enqueueMessage(message("pending"))
        f.session.cleanup()
        assertTrue(current.isCancelled)
        assertTrue(f.session.messageQueue.value.messages.isEmpty())
        assertTrue(f.started.isEmpty())
        assertNull(f.session.getJob())
    }

    @Test
    fun `stopping a replacement also waits for earlier interrupted turns`() = runBlocking {
        Fixture().use { f ->
            val cleanup = CompletableDeferred<Unit>()
            val first = f.session.launchGeneration {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
            val next = message("unsent")
            val replacement = f.session.launchGeneration(next) { error("must not start") }
            val stopped = f.session.stopGeneration()
            assertEquals(setOf(first, replacement), stopped.toSet())
            assertFalse(first.isCompleted)
            cleanup.complete(Unit)
            stopped.forEach { it.join() }
            assertEquals(listOf(next), f.session.messageQueue.value.messages)
            assertTrue(f.started.isEmpty())
            assertTrue(f.session.messageQueue.value.paused)
        }
    }

    private fun message(text: String) = QueuedMessage(content = listOf(UIMessagePart.Text(text)))

    private class Fixture : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = mutableListOf<QueuedMessage>()
        val turns = mutableListOf<CompletableJob>()
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation(assistantId = Uuid.random(), messageNodes = emptyList()),
            scope = scope,
            onIdle = {},
            onQueueReady = { session ->
                session.takeNextQueuedMessage()?.let { message ->
                    started.add(message)
                    val job = Job()
                    turns.add(job)
                    session.setJob(job)
                }
            },
        )

        fun commit(message: QueuedMessage) {
            session.state.value = session.state.value.copy(
                messageNodes = session.state.value.messageNodes + MessageNode.of(
                    UIMessage(id = message.id, role = MessageRole.USER, parts = message.content)
                )
            )
        }

        override fun close() {
            session.cleanup()
            scope.cancel()
        }
    }
}
