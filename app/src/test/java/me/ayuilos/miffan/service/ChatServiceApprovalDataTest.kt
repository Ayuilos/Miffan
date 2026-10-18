package me.ayuilos.miffan.service

import android.app.Application
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.data.audit.AuditRepository
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.*
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ChatServiceApprovalDataTest {
    @Before fun mockLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
    }
    @After fun restoreLog() = unmockkStatic(Log::class)

    private class Fixture(scope: TestScope, calls: List<UIMessagePart.Tool>) {
        val assistant = Assistant()
        val other = Assistant()
        val state = MutableStateFlow(Settings(assistants = listOf(assistant, other), assistantId = assistant.id))
        val settings = mockk<SettingsStore>()
        val repo = mockk<ConversationRepository>(relaxed = true)
        val audit = mockk<AuditRepository>(relaxed = true)
        val job = SupervisorJob()
        val service: ChatService
        val original: Conversation
        init {
            val application = mockk<Application>(relaxed = true)
            every { application.cacheDir } returns File(requireNotNull(System.getProperty("java.io.tmpdir")))
            val appScope = mockk<AppScope>()
            every { appScope.coroutineContext } returns (StandardTestDispatcher(scope.testScheduler) + job)
            every { settings.settingsFlow } returns state
            coEvery { settings.update(any<(Settings) -> Settings>()) } answers {
                state.value = firstArg<(Settings) -> Settings>()(state.value)
            }
            coEvery { repo.existsConversationById(any()) } returns true
            service = ChatService(application, appScope, mockk(relaxed = true), settings, repo,
                mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
                mockk(), mockk(), audit, OkHttpClient())
            original = Conversation(assistantId = assistant.id, messageNodes = emptyList())
                .appendMessage(UIMessage.user("owner"))
                .appendMessage(UIMessage(role = MessageRole.ASSISTANT, parts = calls + call("waiting", "ask_user")))
            service.updateConversationState(original.id) { original }
        }
        fun tools() = service.getConversationFlow(original.id).value.currentMessages.last().getTools()
        fun close() { service.cleanup(); job.cancel() }
    }

    @Test fun alwaysAllowPersistsOnlyOwnersKeyApprovesBatchAndRecordsProvenance() = runTest {
        val f = Fixture(this, listOf(call("one", "mcp__server__write"), call("two", "mcp__server__write"),
            call("different", "mcp__other__write")))
        try {
            f.service.alwaysAllowTool(f.original.id, "one")
            runCurrent()
            assertTrue(f.service.errors.value.isEmpty())
            assertEquals(setOf("mcp__server__write"), f.state.value.assistants.first().standingToolApprovals)
            assertTrue(f.state.value.assistants.last().standingToolApprovals.isEmpty())
            f.tools().take(2).forEach {
                assertEquals(ToolApprovalState.Approved, it.approvalState)
                assertEquals(ToolDecision.ALLOWED, it.approvalRecord?.decision)
                assertEquals(ToolDecisionVia.ALWAYS_ALLOW, it.approvalRecord?.via)
            }
            assertTrue(f.tools().drop(2).all { it.isPending })
            verify(exactly = 1) { f.audit.recordDecisions(any(), f.original) }
            coVerify(exactly = 1) { f.repo.updateConversation(any()) }
            f.service.revokeStandingApproval(f.assistant.id, "mcp__server__write")
            runCurrent()
            assertTrue(f.state.value.assistants.all { it.standingToolApprovals.isEmpty() })
        } finally { f.close() }
    }

    @Test fun standingGrantIgnoresIneligibleMissingResolvedAndOldTipCalls() = runTest {
        for (name in listOf("workspace_shell", "send_message", "calendar_create")) {
            val f = Fixture(this, listOf(call("one", name)))
            try {
                if (name == "calendar_create") f.service.updateConversationState(f.original.id) {
                    it.appendMessage(UIMessage.user("new turn"))
                }
                f.service.alwaysAllowTool(f.original.id, "one")
                f.service.alwaysAllowTool(f.original.id, "missing")
                runCurrent()
                assertTrue(f.state.value.assistants.all { it.standingToolApprovals.isEmpty() })
                coVerify(exactly = 0) { f.settings.update(any<(Settings) -> Settings>()) }
                coVerify(exactly = 0) { f.repo.updateConversation(any()) }
            } finally { f.close() }
        }
        val f = Fixture(this, listOf(call("one", "calendar_create").copy(approvalState = ToolApprovalState.Approved)))
        try {
            f.service.alwaysAllowTool(f.original.id, "one")
            runCurrent()
            coVerify(exactly = 0) { f.settings.update(any<(Settings) -> Settings>()) }
        } finally { f.close() }
    }

    @Test fun calendarApprovalReplacesArgumentsAndRecordsCardDecision() = runTest {
        val f = Fixture(this, listOf(call("event", "calendar_create")))
        try {
            val input = buildJsonObject {
                put("title", "Edited event"); put("start", "2026-10-20T10:00:00+08:00"); put("location", "Office")
            }
            f.service.approveToolWithInput(f.original.id, "event", input)
            runCurrent()
            assertTrue(f.service.errors.value.isEmpty())
            val call = f.tools().first()
            assertEquals(input, call.inputAsJson())
            assertEquals(ToolApprovalState.Approved, call.approvalState)
            assertEquals(ToolDecisionVia.CARD, call.approvalRecord?.via)
            coVerify(exactly = 0) { f.settings.update(any<(Settings) -> Settings>()) }
        } finally { f.close() }
    }

    @Test fun calendarEditsRejectOtherToolsAndInvalidRequiredFieldsOrTimes() = runTest {
        val invalid = listOf("{}", "{\"title\":\"Event\"}", "{\"title\":\" \",\"start\":\"2026-10-20\"}",
            "{\"title\":\"Event\",\"start\":\"garbage\"}", "{\"title\":{},\"start\":\"2026-10-20\"}",
            "{\"title\":\"Event\",\"start\":\"2026-10-20\",\"end\":\"2026-10-19\"}")
        val f = Fixture(this, listOf(call("event", "calendar_create"), call("other", "mcp__server__write")))
        try {
            invalid.forEach { f.service.approveToolWithInput(f.original.id, "event", Json.parseToJsonElement(it).jsonObject) }
            f.service.approveToolWithInput(f.original.id, "other", buildJsonObject {
                put("title", "Event"); put("start", "2026-10-20")
            })
            runCurrent()
            assertTrue(f.tools().all { it.isPending })
            coVerify(exactly = 0) { f.repo.updateConversation(any()) }
        } finally { f.close() }
    }

    companion object {
        private fun call(id: String, name: String) = UIMessagePart.Tool(id, name, "{}", approvalState = ToolApprovalState.Pending)
    }
}
