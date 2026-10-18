package me.ayuilos.miffan.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.ai.tools.GENERATE_IMAGE_TOOL
import me.ayuilos.miffan.data.datastore.DisplaySetting
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.PartnerMessageTool
import me.ayuilos.miffan.testutils.workspaceTestResources
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ApprovalNotificationTest {
    private val resources = workspaceTestResources("values")
    private fun summary(vararg tools: UIMessagePart.Tool) = approvalNotificationSummary(tools.toList()) { id, args ->
        resources.getString(id, *args.toTypedArray())
    }
    private fun tool(name: String, input: String = "{}") = UIMessagePart.Tool(
        toolCallId = name, toolName = name, input = input, approvalState = ToolApprovalState.Pending,
    )
    private fun target(name: String? = "My Mac") = WorkspaceToolTargetSnapshot(
        assistantId = "partner", workspacePermissionRevision = "revision", workspaceId = "workspace",
        scopeId = null, kind = "remote", workspaceName = "Home", remoteHostName = name, remoteHostLabel = "alice@mac",
    )

    @Test fun shellUsesOnlyFirstLineAndAtMostEightyCharacters() {
        assertEquals("Run command: uname -a", summary(tool("workspace_shell", """{"command":"uname -a\nnext command"}""")))
        val command = "😀".repeat(81) + "\nsecret second line"
        val input = buildJsonObject { put("command", command) }.toString()
        assertEquals("Run command: " + "😀".repeat(80), summary(tool("workspace_shell", input)))
    }

    @Test fun computerUsesHistoricalNameAndSharedActionWithAndWithoutDetail() {
        assertEquals("On My Mac: Click", summary(tool("computer_click").copy(workspaceTarget = target())))
        assertEquals("On My Mac: Type text: Hello", summary(tool("computer_type_text", """{"text":"Hello"}""").copy(workspaceTarget = target())))
        assertEquals("On alice@mac: Open app: Firefox", summary(tool("computer_launch_app", """{"name":"Firefox"}""").copy(workspaceTarget = target(""))))
        assertEquals("On computer: Click", summary(tool("computer_click")))
    }

    @Test fun genericAndMultipleCallsKeepOnlyPendingApprovals() {
        assertEquals("Has a question for you", summary(tool("ask_user")))
        assertEquals("Run command: uname -a, and 2 more", summary(
            tool("ignored").copy(approvalState = ToolApprovalState.Approved),
            tool("workspace_shell", """{"command":"uname -a"}"""), tool("ask_user"), tool("computer_click"),
        ))
        assertNull(summary(tool("settled").copy(approvalState = ToolApprovalState.Denied("No"))))
        assertNull(summary())
    }

    @Test fun malformedAndUnknownToolsHaveSafeFallbacks() {
        assertEquals("Run command: not json", summary(tool("workspace_shell", "not json\nsecond line")))
        assertEquals("On computer: computer_future_action", summary(tool("computer_future_action", "[]")))
        assertEquals("Use some_tool", summary(tool("some_tool")))
    }

    @Test fun calendarUsesTitleOrGenericEventAction() {
        assertEquals("Add event: Team lunch", summary(tool("calendar_create", """{"title":"Team lunch"}""")))
        for (input in listOf("{}", "not json", "[]", """{"title":"  "}""", """{"title":null}""", """{"title":42}""", """{"title":{}}""")) {
            assertEquals("Add a calendar event", summary(tool("calendar_create", input)))
        }
        val input = buildJsonObject { put("title", "😀".repeat(81) + "\nPrivate notes") }.toString()
        assertEquals("Add event: " + "😀".repeat(80), summary(tool("calendar_create", input)))
    }

    @Test fun downloadUsesOnlyTheUrlHost() {
        assertEquals("Download a file from example.com", summary(tool(
            "workspace_fetch_url", """{"url":"https://user:password@example.com:8443/private/file?q=secret"}""",
        )))
        for (input in listOf("{}", "not json", "[]", """{"url":""}""", """{"url":null}""", """{"url":true}""",
            """{"url":"/relative/file"}""", """{"url":"https://bad host/file"}""", """{"url":"file:///local/file"}""")) {
            assertEquals("Download a file", summary(tool("workspace_fetch_url", input)))
        }
    }

    @Test fun workspaceFilesUseOnlyTheLastPathSegment() {
        for ((name, action) in listOf("workspace_write_file" to "Write", "workspace_edit_file" to "Edit", "workspace_read_file" to "Read")) {
            assertEquals("$action notes.txt", summary(tool(name, """{"path":"/workspace/private/notes.txt"}""")))
            assertEquals("$action notes.txt", summary(tool(name, """{"path":"notes.txt"}""")))
            for (input in listOf("{}", "not json", "[]", """{"path":"  "}""", """{"path":null}""", """{"path":42}""", """{"path":"/workspace/"}""")) {
                assertEquals("$action a file", summary(tool(name, input)))
            }
            val input = buildJsonObject { put("path", "/workspace/" + "😀".repeat(81) + "\nPrivate notes") }.toString()
            assertEquals("$action " + "😀".repeat(80), summary(tool(name, input)))
        }
    }

    @Test fun mcpUsesServiceAndTheEntireStepAfterTheFirstSeparator() {
        assertEquals("Use “create__event” from calendar", summary(tool("mcp__calendar__create__event")))
        assertEquals("Use “calendar” from calendar", summary(tool("mcp__calendar")))
        val longName = "😀".repeat(81) + "\nPrivate notes"
        assertEquals("Use “${"😀".repeat(80)}” from ${"😀".repeat(80)}", summary(tool("mcp__${longName}__${longName}")))
    }

    @Test fun questionsUseOnlyTheFirstQuestionText() {
        assertEquals("Has a question: Which day?", summary(tool(
            "ask_user", """{"questions":[{"id":"day","question":"Which day?","options":["Monday"]},{"id":"time","question":"What time?"}]}""",
        )))
        for (input in listOf("{}", "not json", "[]", """{"questions":{}}""", """{"questions":[]}""",
            """{"questions":[null,{"question":"Later question"}]}""", """{"questions":[{}]}""",
            """{"questions":[{"question":"  "}]}""", """{"questions":[{"question":null}]}""", """{"questions":[{"question":42}]}""")) {
            assertEquals("Has a question for you", summary(tool("ask_user", input)))
        }
        val input = """{"questions":[${buildJsonObject { put("question", "😀".repeat(81) + "\nPrivate notes") }}]}"""
        assertEquals("Has a question: " + "😀".repeat(80), summary(tool("ask_user", input)))
    }

    @Test fun knownActionsUsePlainLanguageWithoutResolvingArguments() {
        for ((name, expected) in listOf(
            "request_web_search" to "Turn on web search",
            "update_my_profile" to "Change partner profile",
            "create_partner" to "Create a partner",
            "create_group_chat" to "Start a group chat",
            "invite_to_group_chat" to "Invite to the group chat",
            "organize_chat_list" to "Tidy up groups",
            "update_my_preferences" to "Update your preferences",
            "extensions_apply_changes" to "Change extension settings",
            "draft_bug_report" to "Bug report draft",
            GENERATE_IMAGE_TOOL to "Draw a picture",
            PartnerMessageTool.NAME to "Send a message to another partner",
        )) {
            assertEquals(expected, summary(tool(name)))
            assertEquals(expected, summary(tool(name, """{"to":"opaque-partner-id"}""")))
        }
        assertEquals("Add event: Lunch, and 1 more", summary(
            tool("ignored").copy(approvalState = ToolApprovalState.Approved),
            tool("calendar_create", """{"title":"Lunch"}"""), tool("ask_user"),
        ))
    }

    @Test fun selectedConversationApprovalsClearForEverySettlement() {
        val pending = tool("workspace_shell", """{"command":"uname -a"}""")
        val reply = UIMessage.assistant("").copy(parts = listOf(pending))
        val conversation = Conversation.linear(assistantId = Uuid.random(), messages = listOf(reply))
        assertEquals(listOf(pending), conversation.pendingApprovalTools())
        for (state in listOf(ToolApprovalState.Approved, ToolApprovalState.Denied("No"), ToolApprovalState.Answered("Answer"))) {
            assertTrue(conversation.updateMessage(reply.id) { it.copy(parts = listOf(pending.copy(approvalState = state))) }.pendingApprovalTools().isEmpty())
        }
        for (replied in listOf(true, false)) {
            assertTrue(conversation.updateMessage(reply.id) { it.settleUnfinishedTools(replied) }.pendingApprovalTools().isEmpty())
        }
        assertTrue(conversation.copy(messageNodes = emptyList()).pendingApprovalTools().isEmpty())
        val otherBranch = conversation.appendMessage(UIMessage.user("Other"))
        val root = me.ayuilos.miffan.data.model.MessageNode.of(UIMessage.user("New branch"))
        assertTrue(otherBranch.addNodeAndSelect(root).pendingApprovalTools().isEmpty())
    }

    @Test fun notificationIdsAreStableAndSeparateFromExistingNotifications() {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000001")
        assertEquals(approvalNotificationId(id), approvalNotificationId(Uuid.parse(id.toString())))
        assertTrue(approvalNotificationId(id) < 0)
        assertNotEquals(1, approvalNotificationId(id))
        assertNotEquals(ChatGenerationForegroundService.NOTIFICATION_ID, approvalNotificationId(id))
        assertNotEquals(approvalNotificationId(id), approvalNotificationId(Uuid.parse("00000000-0000-0000-0000-000000000002")))
    }

    @Test fun missingSettingsDefaultOnWhileExplicitChoicesSurviveRoundTrip() {
        val defaults = JsonInstant.decodeFromString<DisplaySetting>("{}")
        assertTrue(defaults.enableNotificationOnMessageGeneration)
        assertTrue(defaults.enableLiveUpdateNotification)
        for (reply in listOf(false, true)) for (live in listOf(false, true)) {
            val saved = """{"enableNotificationOnMessageGeneration":$reply,"enableLiveUpdateNotification":$live}"""
            val decoded = JsonInstant.decodeFromString<DisplaySetting>(saved)
            assertEquals(reply, decoded.enableNotificationOnMessageGeneration)
            assertEquals(live, decoded.enableLiveUpdateNotification)
            assertEquals(decoded, JsonInstant.decodeFromString<DisplaySetting>(JsonInstant.encodeToString(decoded)))
        }
    }

    @Test fun allSixLocalesFormatApprovalSummaries() {
        for (locale in listOf("values", "values-zh", "values-zh-rTW", "values-ja", "values-ko-rKR", "values-ru")) {
            val localized = workspaceTestResources(locale)
            val result = approvalNotificationSummary(listOf(tool("computer_click").copy(workspaceTarget = target()), tool("ask_user"))) { id, args ->
                localized.getString(id, *args.toTypedArray())
            }!!
            assertTrue(result.contains("My Mac"))
            assertTrue(result.contains("1"))
            assertFalse(result.contains("%"))
        }
    }
}
