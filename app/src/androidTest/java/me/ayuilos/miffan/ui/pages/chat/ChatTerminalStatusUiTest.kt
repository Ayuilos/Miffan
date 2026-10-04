package me.ayuilos.miffan.ui.pages.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.RouteActivity
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.service.ChatService
import me.ayuilos.miffan.ui.hooks.readBooleanPreference
import me.ayuilos.miffan.ui.hooks.readStringPreference
import me.ayuilos.miffan.ui.hooks.writeBooleanPreference
import me.ayuilos.miffan.ui.hooks.writeStringPreference
import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.ui.theme.MiffanTheme
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ChatTerminalStatusUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun statusStaysAboveInputDuringLongChatAndKeyboardAndLocatesOriginalCommand() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val settingsStore = koin.get<SettingsStore>()
        val repository = koin.get<ConversationRepository>()
        val savedSettings = settingsStore.settingsFlowRaw.first()
        val savedLast = context.readStringPreference("lastConversationId")
        val savedNew = context.readBooleanPreference("create_new_conversation_on_start", true)
        val savedMode = context.readStringPreference("colorMode")
        val model = Model(modelId = "terminal-status-test")
        val provider = ProviderSetting.OpenAI(name = "UI fixture", enabled = false,
            baseUrl = "https://example.invalid/v1", models = listOf(model))
        val assistant = Assistant(name = "终端状态栏测试", avatar = Avatar.Emoji("💻"), chatModelId = model.id)
        val chatId = Uuid.random()
        val target = WorkspaceToolTargetSnapshot(assistant.id.toString(), "revision", "test-workspace", null, "REMOTE",
            remoteHostId = "host", remoteRoot = "/srv/project", workspaceName = "测试服务器",
            remoteHostLabel = "user@remote-server", conversationId = chatId.toString())
        val command = UIMessagePart.Tool("terminal", "workspace_terminal", """{"command":"sudo -v","reason":"验证授权"}""",
            output = listOf(UIMessagePart.Text("""{"status":"completed","exitCode":0,"sessionId":"ended-session","sessionOpen":true}""")),
            approvalState = ToolApprovalState.Approved, workspaceTarget = target, terminalRequestId = "ui-fixture")
        val nodes = listOf(MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(command)))) +
            (1..80).map { MessageNode.of(UIMessage.assistant("第 $it 条后续消息。".repeat(12))) }
        val chat = Conversation(id = chatId, assistantId = assistant.id, title = "终端状态栏长聊天",
            messageNodes = nodes.mapIndexed { index, node -> node.copy(
                parentId = nodes.getOrNull(index - 1)?.id, selectedChildId = nodes.getOrNull(index + 1)?.id) })
        try {
            repository.insertConversation(chat)
            settingsStore.update { it.copy(assistantId = assistant.id, assistants = it.assistants + assistant,
                providers = it.providers + provider, chatModelId = model.id, remoteWorkspaceIntroSeen = true,
                whaleThemeDiscovery = it.whaleThemeDiscovery.copy(introPending = false, settingsSeen = true)) }
            context.writeBooleanPreference("create_new_conversation_on_start", false)
            context.writeStringPreference("lastConversationId", chatId.toString())
            context.writeStringPreference("colorMode", ColorMode.LIGHT.name)
            ActivityScenario.launch(RouteActivity::class.java).use { activity ->
                compose.waitUntil(20_000) { compose.onAllNodesWithTag("chat_terminal_status").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("chat_message_list").performScrollToIndex(60)
                compose.onNodeWithTag("chat_terminal_status").assertIsDisplayed()
                val before = compose.onNodeWithTag("chat_terminal_status").fetchSemanticsNode().boundsInRoot
                compose.onNodeWithTag("chat_message_list").performScrollToIndex(30)
                assertEquals(before, compose.onNodeWithTag("chat_terminal_status").fetchSemanticsNode().boundsInRoot)
                saveScreenshot("terminal-status-long-chat.png")
                compose.onNodeWithTag("chat_input").performClick().performTextInput("保留这段草稿")
                compose.waitUntil(5_000) {
                    var keyboardVisible = false
                    activity.onActivity { host ->
                        keyboardVisible = ViewCompat.getRootWindowInsets(host.window.decorView)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                    keyboardVisible && compose.onNodeWithTag("chat_terminal_status")
                        .fetchSemanticsNode().boundsInRoot.top < before.top - 100
                }
                val bar = compose.onNodeWithTag("chat_terminal_status").fetchSemanticsNode().boundsInRoot
                val input = compose.onNodeWithTag("chat_input").fetchSemanticsNode().boundsInRoot
                assertTrue(bar.bottom <= input.top)
                saveScreenshot("terminal-status-keyboard.png")
                compose.onNodeWithTag("chat_terminal_locate").performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag("chat_message_${nodes.first().id}").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("保留这段草稿").assertIsDisplayed()
                compose.onNodeWithText("收起").performClick()
                compose.onNodeWithTag("chat_terminal_status").assertDoesNotExist()
                compose.onNodeWithText("保留这段草稿").assertIsDisplayed()
            }
        } finally {
            koin.get<ChatService>().deleteConversation(chat)
            settingsStore.update(savedSettings)
            context.writeStringPreference("lastConversationId", savedLast)
            context.writeBooleanPreference("create_new_conversation_on_start", savedNew)
            context.writeStringPreference("colorMode", savedMode)
        }
        Unit
    }

    private fun saveScreenshot(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "terminal-status-tests")
        directory.mkdirs()
        File(directory, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class ChatTerminalStatusActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowStatusBarKeepsEndActionAccessibleAndDoesNotTriggerNavigation() {
        var located = 0
        var ended = 0
        val node = Uuid.random()
        compose.setContent {
            MiffanTheme(colorMode = ColorMode.DARK) {
                var state by remember { mutableStateOf(ChatTerminalStatus(ChatTerminalPhase.CONNECTED,
                    "user@very-long-remote-hostname.example.com:2222", node, "session", "session")) }
                Column(Modifier.width(320.dp)) {
                    ChatTerminalStatusBar(state, closing = false, onLocate = { located++ }, onEnd = {
                        ended++
                        state = state.copy(phase = ChatTerminalPhase.ENDED, sessionId = null)
                    }, onDismiss = {})
                }
            }
        }
        compose.onNodeWithTag("chat_terminal_end").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, ended); assertEquals(0, located) }
        compose.onNodeWithTag("chat_terminal_end").assertDoesNotExist()
        compose.onNodeWithTag("chat_terminal_locate").performClick()
        compose.runOnIdle { assertEquals(1, located) }
        compose.onNodeWithText("收起").assertIsDisplayed()
    }
}
