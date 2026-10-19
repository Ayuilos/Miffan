package me.ayuilos.miffan.data.thread

import androidx.lifecycle.ViewModelStore
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.dao.ThreadCatalogEntry
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.service.ChatService
import me.ayuilos.miffan.ui.im.thread.AgentThreadVM
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadTopicViewModelTest {
    @Test fun offPageTargetsHavePreviewsAndStableIndicesSharedWithTimeline() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val assistant = Assistant()
        val all = (0..15).map { index ->
            Conversation.linear(assistantId = assistant.id, title = if (index == 5) "" else "Topic $index",
                messages = listOf(UIMessage.user("User $index"), UIMessage.assistant("Reply $index"))).copy(
                createAt = Instant.ofEpochMilli(index * 1000L),
                updateAt = Instant.ofEpochMilli((if (index == 0) 100 else index) * 1000L))
        }
        val repo = mockk<ThreadRepository>()
        every { repo.observeSegments(assistant.id, any()) } answers {
            flowOf(all.sortedByDescending { it.updateAt }.take(secondArg()))
        }
        every { repo.observeCatalog(assistant.id) } returns flowOf(all.map {
            ThreadCatalogEntry(it.id.toString(), it.createAt.toEpochMilli(), it.updateAt.toEpochMilli())
        })
        val settings = mockk<SettingsStore>()
        every { settings.settingsFlow } returns MutableStateFlow(Settings(assistants = listOf(assistant)))
        val chat = mockk<ChatService>(relaxed = true)
        every { chat.getConversationJobs() } returns flowOf(emptyMap())
        every { chat.errors } returns MutableStateFlow(emptyList())
        coEvery { chat.readThreadSegment(any()) } answers { all.firstOrNull { it.id == firstArg<Uuid>() } }
        val service = mockk<ThreadService>(relaxed = true)
        every { service.topicChanges } returns MutableSharedFlow()
        every { service.pendingNewTopic } returns MutableStateFlow(emptySet())
        val notices = mockk<ThreadNoticeSource>()
        every { notices.observe(assistant.id) } returns flowOf(emptyList())
        val vm = AgentThreadVM(assistant.id, settings, repo, notices, chat, service, mockk(), mockk(relaxed = true))
        val store = ViewModelStore()
        store.put("thread", vm)
        try {
            runCurrent()
            val before = vm.topics.value.mapValues { it.value.topicIndex }
            val targets = vm.mergeTargets(all[5].id)
            assertEquals(listOf(all[0].id, all[4].id, all[3].id), targets.map { it.segmentId })
            assertEquals("Reply 0", targets.first().preview)
            assertEquals(MessageRole.ASSISTANT, targets.first().speaker)
            assertEquals(0, targets.first().topicIndex)
            assertNull(vm.mergeTargets(all[6].id).first { it.segmentId == all[5].id }.title)
            vm.loadMore()
            runCurrent()
            before.forEach { (id, index) -> assertEquals(index, vm.topics.value.getValue(id).topicIndex) }
            vm.timeline.value.filterIsInstance<TimelineItem.Message>().forEach {
                assertEquals(vm.topics.value.getValue(it.segmentId).topicIndex, it.topicIndex)
            }
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
