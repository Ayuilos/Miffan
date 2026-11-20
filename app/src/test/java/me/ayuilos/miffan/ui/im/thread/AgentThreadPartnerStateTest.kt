package me.ayuilos.miffan.ui.im.thread

import androidx.lifecycle.ViewModelStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.FavoriteType
import me.ayuilos.miffan.data.model.PartnerChat
import me.ayuilos.miffan.data.model.PartnerChatKind
import me.ayuilos.miffan.data.model.PartnerChatSummary
import me.ayuilos.miffan.data.partner.PartnerChatService
import me.ayuilos.miffan.data.repository.FavoriteRepository
import me.ayuilos.miffan.data.thread.ThreadNoticeSource
import me.ayuilos.miffan.data.thread.ThreadRepository
import me.ayuilos.miffan.data.thread.ThreadService
import me.ayuilos.miffan.service.ChatService
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class AgentThreadPartnerStateTest {
    @Test
    fun partnerChatStateMapsRelevantDirectChatsAndOrdersOtherPartnerApprovalsFirst() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val assistantA = Assistant(name = "A")
        val assistantB = Assistant(name = "B")
        val assistantC = Assistant(name = "C")

        val directChatId = Uuid.random()
        val groupChatId = Uuid.random()
        val unrelatedChatId = Uuid.random()

        val summaries = MutableStateFlow(
            listOf(
                PartnerChatSummary(
                    chat = PartnerChat(directChatId, PartnerChatKind.DIRECT, listOf(assistantA.id, assistantB.id), createdAt = Instant.now()),
                    last = null,
                    typing = emptySet(),
                    lastActivity = Instant.now(),
                ),
                PartnerChatSummary(
                    chat = PartnerChat(groupChatId, PartnerChatKind.GROUP, listOf(assistantA.id, assistantB.id, assistantC.id), "Group", Instant.now(), assistantA.id),
                    last = null,
                    typing = emptySet(),
                    lastActivity = Instant.now(),
                ),
                PartnerChatSummary(
                    chat = PartnerChat(unrelatedChatId, PartnerChatKind.DIRECT, listOf(assistantB.id, assistantC.id), createdAt = Instant.now()),
                    last = null,
                    typing = emptySet(),
                    lastActivity = Instant.now(),
                ),
            )
        )
        val approvals = MutableStateFlow(
            mapOf(
                directChatId to setOf(assistantB.id),
                groupChatId to setOf(assistantA.id),
                unrelatedChatId to setOf(assistantC.id),
            )
        )

        val repo = mockk<ThreadRepository>()
        every { repo.observeSegments(assistantA.id, any()) } returns flowOf(emptyList())
        every { repo.observeCatalog(assistantA.id) } returns flowOf(emptyList())

        val settings = mockk<SettingsStore>()
        every { settings.settingsFlow } returns MutableStateFlow(Settings(assistants = listOf(assistantA, assistantB, assistantC)))

        val chat = mockk<ChatService>(relaxed = true)
        every { chat.getConversationJobs() } returns flowOf(emptyMap())
        every { chat.errors } returns MutableStateFlow(emptyList())

        val threadService = mockk<ThreadService>(relaxed = true)
        every { threadService.topicChanges } returns MutableSharedFlow()
        every { threadService.pendingNewTopic } returns MutableStateFlow(emptySet())

        val notices = mockk<ThreadNoticeSource>()
        every { notices.observe(assistantA.id) } returns flowOf(emptyList())

        val favorites = mockk<FavoriteRepository>()
        every { favorites.listByType(FavoriteType.NODE) } returns flowOf(emptyList())

        val partnerChatService = mockk<PartnerChatService>()
        every { partnerChatService.observeSummaries() } returns summaries
        every { partnerChatService.observeApprovalMembers() } returns approvals

        val vm = AgentThreadVM(
            assistantA.id,
            settings,
            repo,
            notices,
            chat,
            threadService,
            mockk(),
            mockk(relaxed = true),
            favorites,
            partnerChatService,
        )
        val store = ViewModelStore()
        store.put("thread", vm)
        try {
            val state = vm.partnerChatState.first { it.pendingApprovals.isNotEmpty() }
            assertEquals(mapOf(assistantB.id to directChatId), state.directChatIds)
            assertEquals(
                mapOf(
                    directChatId to setOf(assistantB.id),
                    groupChatId to setOf(assistantA.id),
                ),
                state.approvalMembersByChat,
            )
            assertEquals(
                listOf(
                    ThreadPartnerApproval(directChatId, assistantB.id),
                    ThreadPartnerApproval(groupChatId, assistantA.id),
                ),
                state.pendingApprovals,
            )
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
