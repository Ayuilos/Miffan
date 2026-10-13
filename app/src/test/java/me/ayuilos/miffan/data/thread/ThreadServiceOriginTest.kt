package me.ayuilos.miffan.data.thread

import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.async
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageOrigin
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.service.ChatService
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ThreadServiceOriginTest {
    private val assistant = Assistant()
    private val origin = MessageOrigin.Event("debug", "notice")
    private val content = listOf(UIMessagePart.Text("payload"))
    private fun segment(time: Long, closed: Boolean = false) = Conversation(assistantId = assistant.id,
        messageNodes = emptyList(), updateAt = Instant.ofEpochMilli(time), threadClosedAt = if (closed) 1 else 0)

    private class Fixture(val assistant: Assistant, segments: List<Conversation>) {
        val chat = mockk<ChatService>(relaxed = true)
        val repository = mockk<ConversationRepository>()
        val router = mockk<SegmentRouter>()
        val store = mockk<SettingsStore>()
        val service: ThreadService
        init {
            every { store.settingsFlow } returns MutableStateFlow(Settings(assistants = listOf(assistant)))
            every { repository.getConversationsOfAssistant(assistant.id) } returns flowOf(segments)
            coEvery { repository.getAssistantIdOf(any()) } returns null
            coEvery { repository.isPartnerPerspective(any()) } answers {
                segments.any { it.id == firstArg<Uuid>() && it.chatId != null }
            }
            service = ThreadService(chat, store, router, mockk(), repository)
        }
    }

    @Test fun explicitSegmentWinsIncludingClosedSegmentsAndNeverRoutes() = runTest {
        val explicit = segment(1, closed = true)
        val f = Fixture(assistant, listOf(explicit, segment(3)))
        assertEquals(explicit.id, f.service.deliverFrom(origin, assistant.id, content, explicit.id))
        coVerifySequence {
            f.chat.openThreadSegment(explicit.id, assistant.id)
            f.chat.sendMessage(explicit.id, content, immediately = false, messageId = any(), origin = origin)
            f.chat.resumeMessageQueue(explicit.id)
        }
        coVerify(exactly = 0) { f.router.route(any()) }
    }

    @Test fun newestOpenSegmentWinsRegardlessOfStoredOrder() = runTest {
        val newest = segment(3)
        val f = Fixture(assistant, listOf(segment(5, true), segment(1), newest))
        assertEquals(newest.id, f.service.deliverFrom(origin, assistant.id, content))
    }

    @Test fun allClosedCreatesSegmentAndRapidDeliveriesReuseUnsavedSegment() = runTest {
        val closed = segment(5, true)
        val f = Fixture(assistant, listOf(closed))
        val id = f.service.deliverFrom(origin, assistant.id, content)
        assertNotEquals(closed.id, id)
        assertEquals(id, f.service.deliverFrom(origin, assistant.id, content))
        verify(exactly = 2) { f.chat.sendMessage(id, content, immediately = false, messageId = any(), origin = origin) }
    }

    @Test fun explicitSegmentCannotBelongToAnotherPartner() = runTest {
        val f = Fixture(assistant, emptyList())
        coEvery { f.repository.getAssistantIdOf(any()) } returns Uuid.random()
        assertTrue(runCatching { f.service.deliverFrom(origin, assistant.id, content, Uuid.random()) }.exceptionOrNull() is IllegalArgumentException)
        verify(exactly = 0) { f.chat.sendMessage(any(), any(), any(), any(), any(), any(), any(), any()) }
    }
    @Test fun coldStartupWaitsForSettingsInsteadOfUsingDummyAssistants() = runTest {
        val f = Fixture(assistant, emptyList())
        val settings = MutableStateFlow(Settings.dummy())
        every { f.store.settingsFlow } returns settings
        val delivery = async { f.service.deliverFrom(origin, assistant.id, content) }
        runCurrent()
        assertFalse(delivery.isCompleted)
        coVerify(exactly = 0) { f.chat.openThreadSegment(any(), any()) }
        settings.value = Settings(assistants = listOf(assistant))
        val id = delivery.await()
        verify { f.chat.sendMessage(id, content, immediately = false, messageId = any(), origin = origin) }
    }

    @Test fun perspectiveNeverReceivesImEventsEvenWhenNewerOrExplicitlyRequested() = runTest {
        val im = segment(1)
        val perspective = segment(100).copy(chatId = Uuid.random())
        val f = Fixture(assistant, listOf(perspective, im))
        assertEquals(im.id, f.service.deliverFrom(origin, assistant.id, content))
        val failure = runCatching { f.service.deliverFrom(origin, assistant.id, content, perspective.id) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun ownerNoteUsesImSegmentWithoutGenerating() = runTest {
        val im = segment(1)
        val perspective = segment(100).copy(chatId = Uuid.random())
        val f = Fixture(assistant, listOf(perspective, im))
        f.service.deliverNote(assistant.id, origin, "note")
        coVerify { f.chat.appendPartnerNote(im.id, assistant.id, origin, "note") }
        verify(exactly = 0) { f.chat.sendMessage(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun explicitHiddenPerspectiveIsRejectedEvenWhenDisplayedListHasAlreadyFilteredIt() = runTest {
        val im = segment(1)
        val hiddenId = Uuid.random()
        val f = Fixture(assistant, listOf(im))
        coEvery { f.repository.isPartnerPerspective(hiddenId) } returns true
        val failure = runCatching { f.service.deliverFrom(origin, assistant.id, content, hiddenId) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        coVerify(exactly = 0) { f.chat.openThreadSegment(any(), any()) }
        verify(exactly = 0) { f.chat.sendMessage(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

}
