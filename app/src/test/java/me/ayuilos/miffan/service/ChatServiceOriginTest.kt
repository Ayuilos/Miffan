package me.ayuilos.miffan.service

import android.app.Application
import android.util.Log
import io.mockk.*
import org.junit.Before
import org.junit.After
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageOrigin
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class ChatServiceOriginTest {
    @Before fun mockAndroidLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
    }
    @After fun restoreAndroidLog() = unmockkStatic(Log::class)

    @Test fun eventsQueueBehindApprovalEvenWithImmediateSendOrQueuePromotion() = runTest {
        val application = mockk<Application>(relaxed = true)
        every { application.cacheDir } returns File(requireNotNull(System.getProperty("java.io.tmpdir")))
        val scope = mockk<AppScope>()
        val scopeJob = SupervisorJob()
        every { scope.coroutineContext } returns (StandardTestDispatcher(testScheduler) + scopeJob)
        val assistant = Assistant()
        val settings = mockk<SettingsStore>()
        every { settings.settingsFlow } returns MutableStateFlow(Settings(assistants = listOf(assistant), assistantId = assistant.id))
        val repo = mockk<ConversationRepository>(relaxed = true)
        val service = ChatService(application, scope, mockk(relaxed = true), settings, repo,
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
            mockk(), mockk(), mockk(), OkHttpClient())
        val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList())
            .appendMessage(UIMessage.user("owner"))
            .appendMessage(UIMessage(parts = listOf(UIMessagePart.Tool(toolCallId = "pending", toolName = "shell",
                input = "{}", approvalState = ToolApprovalState.Pending)), role = me.rerere.ai.core.MessageRole.ASSISTANT))
        try {
            service.updateConversationState(conversation.id) { conversation }
            val origin = MessageOrigin.Event("debug", "event")
            val id = Uuid.random()
            service.sendMessage(conversation.id, listOf(UIMessagePart.Text("event")), immediately = true,
                origin = origin, messageId = id)
            runCurrent()
            assertEquals(origin, service.getMessageQueueFlow(conversation.id).value.messages.single().origin)
            assertEquals(conversation, service.getConversationFlow(conversation.id).value)
            service.sendQueuedMessageImmediately(conversation.id, id)
            service.resumeMessageQueue(conversation.id)
            runCurrent()
            assertTrue(service.getConversationFlow(conversation.id).value.hasPendingToolApprovals())
            assertEquals(id, service.getMessageQueueFlow(conversation.id).value.messages.single().id)
            coVerify(exactly = 0) { repo.updateConversation(any()) }
        } finally {
            service.cleanup()
            scopeJob.cancel()
        }
    }
}
