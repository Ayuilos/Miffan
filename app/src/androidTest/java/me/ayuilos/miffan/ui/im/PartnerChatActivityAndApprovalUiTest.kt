package me.ayuilos.miffan.ui.im

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.sonner.rememberToasterState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.appString
import me.ayuilos.miffan.data.ai.AgentAccess
import me.ayuilos.miffan.data.ai.tools.WORKSPACE_TERMINAL_TOOL_NAME
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.PartnerChat
import me.ayuilos.miffan.data.model.PartnerChatApproval
import me.ayuilos.miffan.data.model.PartnerChatKind
import me.ayuilos.miffan.data.model.PartnerChatMessage
import me.ayuilos.miffan.data.model.PartnerChatSummary
import me.ayuilos.miffan.data.model.PartnerTurnActivity
import me.ayuilos.miffan.data.partner.PartnerChatService
import me.ayuilos.miffan.data.thread.ThreadListState
import me.ayuilos.miffan.ui.components.ui.LocalFloatingChromeBlurEnabled
import me.ayuilos.miffan.ui.context.LocalASRState
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.context.LocalTTSState
import me.ayuilos.miffan.ui.context.LocalToaster
import me.ayuilos.miffan.ui.context.Navigator
import me.ayuilos.miffan.ui.hooks.rememberCustomAsrState
import me.ayuilos.miffan.ui.hooks.rememberCustomTtsState
import me.ayuilos.miffan.ui.im.thread.AgentThreadPage
import me.ayuilos.miffan.ui.im.thread.AgentThreadVM
import me.ayuilos.miffan.ui.im.thread.LocalThreadPartnerChatState
import me.ayuilos.miffan.ui.im.thread.ThreadPartnerChatState
import me.ayuilos.miffan.ui.im.thread.ThreadPartnerMessage
import me.ayuilos.miffan.ui.pages.extensions.workspace.TerminalCommandSessions
import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.ui.theme.MiffanTheme
import me.ayuilos.miffan.utils.NetworkMonitor
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class PartnerChatActivityAndApprovalUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun partnerChatShowsLiveStepsAndStatusWithoutFalseConnecting() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val partnerA = Assistant(name = "小饭", avatar = Avatar.Emoji("🍚"))
        val partnerB = Assistant(name = "运维助手", avatar = Avatar.Emoji("🛠️"))
        val chatId = Uuid.random()
        val fakeService = FakePartnerChatService().apply {
            chat.value = PartnerChat(chatId, PartnerChatKind.DIRECT, listOf(partnerA.id, partnerB.id), createdAt = Instant.now())
        }
        val online = GlobalContext.get().get<NetworkMonitor>().online.value

        withTempAssistants(partnerA, partnerB) { settingsStore ->
            val backStack = mutableStateListOf<NavKey>(Screen.PartnerChat(chatId.toString()))
            setMountedContent(
                settingsStore = settingsStore,
                backStack = backStack,
                content = {
                    val vm = viewModel(key = "partner-chat-$chatId") {
                        PartnerChatVM(chatId, settingsStore, fakeService, ThreadListState(context))
                    }
                    PartnerChatPage(chatId = chatId, vm = vm)
                },
            ) {
                // Phase 1: pre-response connecting (or offline when network is down)
                fakeService.typing.value = setOf(partnerB.id)
                fakeService.activity.value = mapOf(partnerB.id to PartnerTurnActivity(connected = false, parts = emptyList()))
                val initialLabel = appString(if (online) R.string.im_thread_status_connecting else R.string.im_thread_status_offline)
                compose.waitUntilTextDisplayed(initialLabel)

                // Phase 2: tool running while SSE stream is closed (connected = false)
                fakeService.activity.value = mapOf(
                    partnerB.id to PartnerTurnActivity(
                        connected = false,
                        parts = listOf(UIMessagePart.Tool("t1", "workspace_shell", """{"command":"ls -la"}""")),
                    ),
                )
                compose.waitUntilTextDisplayed(appString(R.string.im_steps_running, 1))
                compose.waitUntilTextDisplayed(appString(R.string.im_step_command))
                compose.onNodeWithText(appString(R.string.im_thread_status_connecting)).assertDoesNotExist()

                // Phase 3: tool executed, between LLM steps with connected = false -> must not revert to Connecting
                fakeService.activity.value = mapOf(
                    partnerB.id to PartnerTurnActivity(
                        connected = false,
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "t1",
                                toolName = "workspace_shell",
                                input = """{"command":"ls -la"}""",
                                output = listOf(UIMessagePart.Text("done")),
                            ),
                        ),
                    ),
                )
                val betweenStepLabel = appString(if (online) R.string.im_thread_tools_working else R.string.im_thread_status_offline)
                compose.waitUntilTextDisplayed(betweenStepLabel)
                compose.onNodeWithText(appString(R.string.im_steps_running, 1)).assertIsDisplayed()
                compose.onNodeWithText(appString(R.string.im_thread_status_connecting)).assertDoesNotExist()

                // Phase 4: reply text starts streaming
                fakeService.activity.value = mapOf(
                    partnerB.id to PartnerTurnActivity(
                        connected = true,
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "t1",
                                toolName = "workspace_shell",
                                input = """{"command":"ls -la"}""",
                                output = listOf(UIMessagePart.Text("done")),
                            ),
                            UIMessagePart.Text("已经查完了"),
                        ),
                    ),
                )
                compose.waitUntilTextDisplayed(appString(R.string.im_thread_typing))

                // Phase 5: pending approval suppresses the duplicate typing/live-steps row
                fakeService.approvals.value = listOf(
                    PartnerChatApproval(
                        memberId = partnerB.id,
                        tool = UIMessagePart.Tool(
                            toolCallId = "t2",
                            toolName = "workspace_shell",
                            input = """{"command":"rm -rf /tmp/x"}""",
                            approvalState = ToolApprovalState.Pending,
                        ),
                        segmentId = Uuid.random(),
                    ),
                )
                compose.waitUntilTextDisplayed(appString(R.string.im_gc_needs_ok, partnerB.name))
                compose.onNodeWithText(appString(R.string.im_steps_running, 1)).assertDoesNotExist()
            }
        }
    }

    @Test
    fun partnerChatTerminalApprovalBindsSegmentAndRoutesCancelThroughAnswer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val partnerA = Assistant(name = "小饭", avatar = Avatar.Emoji("🍚"))
        val partnerB = Assistant(name = "运维助手", avatar = Avatar.Emoji("🛠️"))
        val chatId = Uuid.random()
        val segmentId = Uuid.random()
        val requestId = "ui-partner-terminal-${Uuid.random()}"
        val target = WorkspaceToolTargetSnapshot(
            assistantId = partnerB.id.toString(),
            workspacePermissionRevision = "rev-1",
            workspaceId = "ws",
            scopeId = null,
            kind = "REMOTE",
            remoteHostId = "host-1",
            remoteRoot = "/srv/app",
            workspaceName = "生产机",
            remoteHostLabel = "ops@prod",
            conversationId = segmentId.toString(),
        )
        val terminalTool = UIMessagePart.Tool(
            toolCallId = "term-1",
            toolName = WORKSPACE_TERMINAL_TOOL_NAME,
            input = """{"command":"sudo systemctl restart api","reason":"重启服务需要 sudo 密码"}""",
            approvalState = ToolApprovalState.Pending,
            workspaceTarget = target,
            terminalRequestId = requestId,
        )
        val fakeService = FakePartnerChatService().apply {
            chat.value = PartnerChat(chatId, PartnerChatKind.DIRECT, listOf(partnerA.id, partnerB.id), createdAt = Instant.now())
            approvals.value = listOf(PartnerChatApproval(partnerB.id, terminalTool, segmentId))
        }

        try {
            withTempAssistants(partnerA, partnerB) { settingsStore ->
                val backStack = mutableStateListOf<NavKey>(Screen.PartnerChat(chatId.toString()))
                setMountedContent(
                    settingsStore = settingsStore,
                    backStack = backStack,
                    content = {
                        val vm = viewModel(key = "partner-chat-term-$chatId") {
                            PartnerChatVM(chatId, settingsStore, fakeService, ThreadListState(context))
                        }
                        PartnerChatPage(chatId = chatId, vm = vm)
                    },
                ) {
                    compose.waitUntilTextDisplayed(appString(R.string.im_gc_needs_ok, partnerB.name))
                    compose.waitUntilTextDisplayed("sudo systemctl restart api")
                    compose.onNodeWithText(appString(R.string.terminal_command_missing_session)).assertDoesNotExist()
                    compose.onNodeWithText(appString(R.string.terminal_command_open))
                        .assertIsDisplayed()
                        .assertIsEnabled()

                    compose.onNodeWithText(appString(R.string.terminal_command_cancel_run)).performClick()
                    compose.waitUntil(10_000) { fakeService.recordedAnswers.isNotEmpty() }

                    val recorded = fakeService.recordedAnswers.single()
                    assertEquals(chatId, recorded.chatId)
                    assertEquals(partnerB.id, recorded.memberId)
                    assertEquals("term-1", recorded.toolCallId)
                    assertTrue(recorded.approved)
                    val answer = recorded.answer.orEmpty()
                    assertTrue("Expected terminalRequestId in $answer", answer.contains(requestId))
                    assertTrue("Expected cancelled status in $answer", answer.contains("cancelled"))
                }
            }
        } finally {
            TerminalCommandSessions.forget(requestId)
            context.getSharedPreferences("terminal_command_receipts", Context.MODE_PRIVATE).edit(commit = true) {
                remove(requestId)
            }
        }
    }

    @Test
    fun partnerChatAskUserApprovalHidesReplyHintAndSubmitsAnswer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val partnerA = Assistant(name = "小饭", avatar = Avatar.Emoji("🍚"))
        val partnerB = Assistant(name = "部署助手", avatar = Avatar.Emoji("🚀"))
        val chatId = Uuid.random()
        val askTool = UIMessagePart.Tool(
            toolCallId = "ask-1",
            toolName = "ask_user",
            input = """{"questions":[{"id":"env","question":"部署到哪个环境？","type":"single","options":["预发","生产"]}]}""",
            approvalState = ToolApprovalState.Pending,
        )
        val fakeService = FakePartnerChatService().apply {
            chat.value = PartnerChat(chatId, PartnerChatKind.DIRECT, listOf(partnerA.id, partnerB.id), createdAt = Instant.now())
            approvals.value = listOf(PartnerChatApproval(partnerB.id, askTool, Uuid.random()))
        }

        withTempAssistants(partnerA, partnerB) { settingsStore ->
            val backStack = mutableStateListOf<NavKey>(Screen.PartnerChat(chatId.toString()))
            setMountedContent(
                settingsStore = settingsStore,
                backStack = backStack,
                content = {
                    val vm = viewModel(key = "partner-chat-ask-$chatId") {
                        PartnerChatVM(chatId, settingsStore, fakeService, ThreadListState(context))
                    }
                    PartnerChatPage(chatId = chatId, vm = vm)
                },
            ) {
                compose.waitUntilTextDisplayed("部署到哪个环境？")
                compose.onNodeWithText(appString(R.string.im_question_reply_hint)).assertDoesNotExist()
                compose.onNodeWithText("预发").performClick()
                compose.onNodeWithText(appString(R.string.im_question_send)).performClick()

                compose.waitUntil(10_000) { fakeService.recordedAnswers.isNotEmpty() }
                val recorded = fakeService.recordedAnswers.single()
                assertEquals(chatId, recorded.chatId)
                assertEquals(partnerB.id, recorded.memberId)
                assertEquals("ask-1", recorded.toolCallId)
                assertTrue(recorded.approved)
                assertTrue("Expected answer to contain 预发, got ${recorded.answer}", recorded.answer.orEmpty().contains("预发"))
            }
        }
    }

    @Test
    fun threadPartnerMessageShowsPendingApprovalAndOpensPartnerChatWhileRunning() = runBlocking {
        val partnerA = Assistant(name = "小饭", avatar = Avatar.Emoji("🍚"))
        val partnerB = Assistant(name = "运维助手", avatar = Avatar.Emoji("🛠️"))
        val chatId = Uuid.random()
        val runningSendMessage = UIMessagePart.Tool(
            toolCallId = "msg-1",
            toolName = "send_message",
            input = """{"to":"${partnerB.id}","text":"去重启一下服务"}""",
            output = emptyList(),
        )
        val partnerState = mutableStateOf(
            ThreadPartnerChatState(directChatIds = mapOf(partnerB.id to chatId)),
        )

        withTempAssistants(partnerA, partnerB) { settingsStore ->
            val backStack = mutableStateListOf<NavKey>(Screen.Thread(partnerA.id.toString()))
            setMountedContent(
                settingsStore = settingsStore,
                backStack = backStack,
                content = {
                    CompositionLocalProvider(LocalThreadPartnerChatState provides partnerState.value) {
                        ThreadPartnerMessage(tool = runningSendMessage, sender = partnerA, onApproval = { _, _ -> })
                    }
                },
            ) {
                compose.waitUntilTextDisplayed(appString(R.string.im_partner_msg_asking, partnerB.name))
                compose.onNodeWithText(appString(R.string.im_thread_view)).assertIsDisplayed()

                compose.runOnIdle {
                    partnerState.value = ThreadPartnerChatState(
                        directChatIds = mapOf(partnerB.id to chatId),
                        approvalMembersByChat = mapOf(chatId to setOf(partnerB.id)),
                    )
                }
                val needsOkLabel = appString(R.string.im_gc_needs_ok, partnerB.name)
                compose.waitUntilTextDisplayed(needsOkLabel)
                compose.onNodeWithText(appString(R.string.im_thread_view)).assertIsDisplayed()

                compose.onNodeWithText(needsOkLabel).performClick()
                compose.runOnIdle {
                    assertEquals(Screen.PartnerChat(chatId.toString()), backStack.last())
                }
            }
        }
    }

    @Test
    fun agentThreadShowsTopBannerForPendingPartnerApprovalAndNavigates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val partnerA = Assistant(name = "小饭", avatar = Avatar.Emoji("🍚"))
        val partnerB = Assistant(name = "运维助手", avatar = Avatar.Emoji("🛠️"))
        val chatId = Uuid.random()
        val chat = PartnerChat(chatId, PartnerChatKind.DIRECT, listOf(partnerA.id, partnerB.id), createdAt = Instant.now())
        val fakeService = FakePartnerChatService().apply {
            summaries.value = listOf(PartnerChatSummary(chat, last = null, typing = setOf(partnerB.id), lastActivity = Instant.now()))
            approvalMembers.value = mapOf(chatId to setOf(partnerB.id))
        }

        withTempAssistants(partnerA, partnerB) { settingsStore ->
            val backStack = mutableStateListOf<NavKey>(Screen.Thread(partnerA.id.toString()))
            setMountedContent(
                settingsStore = settingsStore,
                backStack = backStack,
                content = {
                    val vm = viewModel(key = "agent-thread-${partnerA.id}") {
                        AgentThreadVM(
                            assistantId = partnerA.id,
                            settingsStore = settingsStore,
                            threadRepository = koin.get(),
                            noticeSource = koin.get(),
                            chatService = koin.get(),
                            threadService = koin.get(),
                            revisionService = koin.get(),
                            listState = ThreadListState(context),
                            favoriteRepository = koin.get(),
                            partnerChatService = fakeService,
                        )
                    }
                    AgentThreadPage(assistantId = partnerA.id, vm = vm)
                },
            ) {
                val bannerText = appString(R.string.im_gc_needs_ok, partnerB.name)
                compose.waitUntilTextDisplayed(bannerText)
                compose.onNodeWithText(appString(R.string.im_thread_view)).assertIsDisplayed()

                compose.onNodeWithText(bannerText).performClick()
                compose.runOnIdle {
                    assertEquals(Screen.PartnerChat(chatId.toString()), backStack.last())
                }
            }
        }
    }

    @Test
    fun imChatsTabPrioritizesWaitingOverTypingForPartnerChat() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val partnerA = Assistant(name = "小饭", avatar = Avatar.Emoji("🍚"))
        val partnerB = Assistant(name = "运维助手", avatar = Avatar.Emoji("🛠️"))
        val chatId = Uuid.random()
        val chat = PartnerChat(chatId, PartnerChatKind.DIRECT, listOf(partnerA.id, partnerB.id), createdAt = Instant.now())
        val fakeService = FakePartnerChatService().apply {
            summaries.value = listOf(
                PartnerChatSummary(
                    chat = chat,
                    last = null,
                    typing = setOf(partnerB.id),
                    lastActivity = Instant.now().plusSeconds(3600),
                ),
            )
            approvalMembers.value = mapOf(chatId to setOf(partnerB.id))
        }

        withTempAssistants(partnerA, partnerB) { settingsStore ->
            val backStack = mutableStateListOf<NavKey>(Screen.Home)
            setMountedContent(
                settingsStore = settingsStore,
                backStack = backStack,
                content = {
                    val vm = viewModel(key = "im-home-$chatId") {
                        ImHomeVM(
                            settingsStore = settingsStore,
                            conversationRepository = koin.get(),
                            chatService = koin.get(),
                            listState = ThreadListState(context),
                            partnerChatService = fakeService,
                            updateChecker = koin.get(),
                        )
                    }
                    ImChatsTab(
                        vm = vm,
                        innerPadding = PaddingValues(0.dp),
                        listState = rememberLazyListState(),
                        onFindPartner = {},
                        searchOpen = false,
                        onSearch = {},
                        onSearchFieldMoved = {},
                    )
                },
            ) {
                val rowTag = "im_chat_row_$chatId"
                val waitingLabel = appString(R.string.im_partner_waiting)
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasTestTag(rowTag)).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("im_chat_list").performScrollToNode(hasTestTag(rowTag))
                val waitingRow = hasTestTag(rowTag) and hasText(waitingLabel)
                compose.waitUntil(10_000) {
                    compose.onAllNodes(waitingRow).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(waitingRow).assertIsDisplayed()
                compose.onNode(hasTestTag(rowTag) and hasText(appString(R.string.im_chats_typing)))
                    .assertDoesNotExist()
            }
        }
    }

    private suspend fun setMountedContent(
        settingsStore: SettingsStore,
        backStack: SnapshotStateList<NavKey>,
        content: @Composable () -> Unit,
        assertions: suspend () -> Unit,
    ) {
        val mounted = mutableStateOf(true)
        compose.setContent {
            if (mounted.value) {
                TestHost(settingsStore, backStack, content)
            }
        }
        try {
            assertions()
        } finally {
            // Unmount before Activity destruction so HazeSourceNode detaches cleanly while still attached to an owner.
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
        }
    }

    private suspend fun withTempAssistants(
        vararg assistants: Assistant,
        block: suspend (SettingsStore) -> Unit,
    ) {
        val settingsStore = GlobalContext.get().get<SettingsStore>()
        val saved = settingsStore.settingsFlowRaw.first()
        try {
            settingsStore.update { it.copy(assistants = it.assistants + assistants.toList()) }
            settingsStore.settingsFlow.first { current ->
                assistants.all { target -> current.assistants.any { it.id == target.id } }
            }
            block(settingsStore)
        } finally {
            settingsStore.update(saved)
        }
    }

    @Composable
    private fun TestHost(
        settingsStore: SettingsStore,
        backStack: SnapshotStateList<NavKey>,
        content: @Composable () -> Unit,
    ) {
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        val toaster = rememberToasterState()
        val tts = rememberCustomTtsState()
        val asr = rememberCustomAsrState()
        MiffanTheme(colorMode = ColorMode.LIGHT) {
            CompositionLocalProvider(
                LocalSettings provides settings,
                LocalNavController provides Navigator(backStack),
                LocalToaster provides toaster,
                LocalTTSState provides tts,
                LocalASRState provides asr,
                LocalFloatingChromeBlurEnabled provides false,
            ) {
                content()
            }
        }
    }

    private fun ComposeContentTestRule.waitUntilTextDisplayed(text: String) {
        waitUntil(10_000) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(text).assertIsDisplayed()
    }

    private data class RecordedApprovalAnswer(
        val chatId: Uuid,
        val memberId: Uuid,
        val toolCallId: String,
        val approved: Boolean,
        val answer: String?,
    )

    private class FakePartnerChatService : PartnerChatService {
        val summaries = MutableStateFlow<List<PartnerChatSummary>>(emptyList())
        val chat = MutableStateFlow<PartnerChat?>(null)
        val messages = MutableStateFlow<List<PartnerChatMessage>>(emptyList())
        val typing = MutableStateFlow<Set<Uuid>>(emptySet())
        val activity = MutableStateFlow<Map<Uuid, PartnerTurnActivity>>(emptyMap())
        val approvals = MutableStateFlow<List<PartnerChatApproval>>(emptyList())
        val approvalMembers = MutableStateFlow<Map<Uuid, Set<Uuid>>>(emptyMap())
        val recordedAnswers = CopyOnWriteArrayList<RecordedApprovalAnswer>()

        override fun observeSummaries(): Flow<List<PartnerChatSummary>> = summaries

        override fun observeChat(chatId: Uuid): Flow<PartnerChat?> = chat

        override fun observeMessages(chatId: Uuid): Flow<List<PartnerChatMessage>> = messages

        override fun observeTyping(chatId: Uuid): Flow<Set<Uuid>> = typing

        override fun observeConnected(chatId: Uuid): Flow<Set<Uuid>> =
            activity.map { entries -> entries.filterValues { it.connected }.keys }

        override fun observeActivity(chatId: Uuid): Flow<Map<Uuid, PartnerTurnActivity>> = activity

        override suspend fun sendFromOwner(chatId: Uuid, text: String, mentions: List<Uuid>) = Unit

        override fun observeContacts(assistantId: Uuid): Flow<List<Uuid>> = flowOf(emptyList())

        override suspend fun revokeContact(a: Uuid, b: Uuid) = Unit

        override fun observeApprovals(chatId: Uuid): Flow<List<PartnerChatApproval>> = approvals

        override fun observeApprovalMembers(): Flow<Map<Uuid, Set<Uuid>>> = approvalMembers

        override suspend fun answerApproval(
            chatId: Uuid,
            memberId: Uuid,
            toolCallId: String,
            approved: Boolean,
            answer: String?,
        ) {
            recordedAnswers.add(RecordedApprovalAnswer(chatId, memberId, toolCallId, approved, answer))
        }

        override suspend fun createGroup(memberIds: List<Uuid>, title: String): Uuid = Uuid.random()

        override suspend fun renameGroup(chatId: Uuid, title: String) = Unit

        override suspend fun setMembers(chatId: Uuid, memberIds: List<Uuid>) = Unit

        override suspend fun setMembers(chatId: Uuid, memberIds: List<Uuid>, access: AgentAccess) = Unit

        override suspend fun setHost(chatId: Uuid, assistantId: Uuid) = Unit

        override suspend fun deleteGroup(chatId: Uuid) = Unit
    }
}
