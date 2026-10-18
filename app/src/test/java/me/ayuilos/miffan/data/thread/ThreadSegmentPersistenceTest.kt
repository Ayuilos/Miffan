package me.ayuilos.miffan.data.thread

import android.app.Application
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.TopicStart
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.service.ChatService
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class ThreadSegmentPersistenceTest {
    @Before fun mockLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
    }
    @After fun restoreLog() = unmockkStatic(Log::class)

    @Test fun openingAgainAndLaterSavesKeepTheOriginalStartReason() = runTest {
        val app = mockk<Application>(relaxed = true)
        every { app.cacheDir } returns File(requireNotNull(System.getProperty("java.io.tmpdir")))
        val scope = mockk<AppScope>()
        val job = SupervisorJob()
        every { scope.coroutineContext } returns StandardTestDispatcher(testScheduler) + job
        val assistant = Assistant()
        val settings = mockk<SettingsStore>()
        every { settings.settingsFlow } returns MutableStateFlow(Settings(assistants = listOf(assistant)))
        val repo = mockk<ConversationRepository>(relaxed = true)
        val stored = mutableMapOf<Uuid, Conversation>()
        coEvery { repo.getConversationById(any()) } answers { stored[firstArg()] }
        coEvery { repo.existsConversationById(any()) } answers { firstArg<Uuid>() in stored }
        coEvery { repo.insertConversation(any()) } coAnswers { firstArg<Conversation>().let { stored[it.id] = it } }
        coEvery { repo.updateConversation(any()) } coAnswers { firstArg<Conversation>().let { stored[it.id] = it } }
        val chat = ChatService(app, scope, mockk(relaxed = true), settings, repo,
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
            mockk(), mockk(), mockk(), OkHttpClient())
        try {
            for (start in TopicStart.entries) {
                val id = Uuid.random()
                chat.openThreadSegment(id, assistant.id, start)
                chat.openThreadSegment(id, assistant.id)
                assertEquals(start, chat.getConversationFlow(id).value.topicStart)
                chat.saveConversation(id, chat.getConversationFlow(id).value.appendMessage(UIMessage.user("owner")))
                chat.updateThreadSegment(id) { it.copy(title = "Title") }
                assertEquals(start, stored.getValue(id).topicStart)
            }
        } finally { chat.cleanup(); job.cancel() }
    }

    @Test fun modelSerializationAndRoomMappingCarryReasonsAndReadLegacyNull() {
        val repository = ConversationRepository(mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
        for (start in TopicStart.entries + listOf(null)) {
            val original = Conversation(assistantId = Uuid.random(), messageNodes = emptyList(), topicStart = start)
            val json = JsonInstant.encodeToString(original)
            assertEquals(start, JsonInstant.decodeFromString<Conversation>(json).topicStart)
            val entity = repository.conversationToConversationEntity(original)
            assertEquals(start?.name, entity.topicStart)
            assertEquals(start, repository.conversationEntityToConversation(entity, emptyList()).topicStart)
        }
        val legacy = """{"assistantId":"${Uuid.random()}","messageNodes":[],"threadClosedAt":12}"""
        assertNull(JsonInstant.decodeFromString<Conversation>(legacy).topicStart)
    }
}
