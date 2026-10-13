package me.ayuilos.miffan.data.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.ai.AgentAccess
import me.ayuilos.miffan.data.ai.mcp.*
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class MyEnvironmentToolTest {
    private val a = Assistant(name = "A", enableMemory = true)
    private val b = Assistant(name = "B", systemPrompt = "foreign-prompt", learnedPreferences = "foreign-preferences")
    private val c = Assistant(name = "unrelated-person")
    private val model = Model(modelId = "raw-id", displayName = "Current model",
        customHeaders = listOf(CustomHeader("Authorization", "model-secret")))
    private val settings = Settings(assistants = listOf(a, b, c)).withInterfaceMode(InterfaceMode.IM)
    private fun group(title: String, members: List<Uuid>, kind: PartnerChatKind = PartnerChatKind.GROUP) =
        PartnerChat(Uuid.random(), kind, members, title, Instant.EPOCH)
    private fun environment(s: Settings = settings, access: AgentAccess = AgentAccess.forAssistant(a, true),
        tools: Set<String> = emptySet(), groups: List<PartnerChat> = emptyList(), contacts: Set<Uuid> = emptySet()) =
        buildMyEnvironment(access, s, model, "Provider", tools, listOf("Enabled skill"), "Workspace", "Computer", groups, contacts)

    @Test fun onlyOwnAndPublicMemberNamesLeaveTheSerializationBoundary() {
        val server = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "Bound MCP", headers = listOf("Authorization" to "header-secret"),
                oauth = McpOAuthState(enabled = true, clientSecret = "oauth-secret", accessToken = "oauth-token")),
            url = "https://user:password@credential.example/mcp?key=query-secret",
        )
        val privateModel = Uuid.random()
        val privateWorkspace = Uuid.random()
        val s = settings.copy(
            assistants = listOf(a.copy(avatar = Avatar.Image("file:///private/avatar.png"), mcpServers = setOf(server.id)),
                b.copy(chatModelId = privateModel, workspaceId = privateWorkspace), c),
            mcpServers = listOf(server),
            providers = listOf(ProviderSetting.OpenAI(name = "Provider", apiKey = "api-secret",
                baseUrl = "https://credential.example", models = listOf(model))),
            partnerContactGrants = listOf(PartnerPair.of(a.id, b.id)),
        )
        val output = environment(s, groups = listOf(group("My group", listOf(a.id, b.id)),
            group("foreign-group-secret", listOf(b.id, c.id)), group("private-direct", listOf(a.id, c.id), PartnerChatKind.DIRECT)))
        assertEquals("A", output.getValue("name").jsonPrimitive.content)
        assertEquals("image", output.getValue("avatar").jsonPrimitive.content)
        assertEquals("Current model", output.getValue("model").jsonObject.getValue("name").jsonPrimitive.content)
        val myGroup = output.getValue("groups").jsonArray.single().jsonObject
        assertEquals("My group", myGroup.getValue("name").jsonPrimitive.content)
        assertEquals(listOf("A", "B"), myGroup.getValue("members").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("B"), output.getValue("contacts_authorized").jsonArray.map { it.jsonPrimitive.content })
        for (forbidden in listOf("foreign-", "unrelated-person", "private-direct", "/private", "file:", "https:",
            "credential.example", "secret", "oauth-token", "Authorization", privateModel.toString(),
            privateWorkspace.toString())) {
            assertFalse(forbidden, output.toString().contains(forbidden))
        }
        assertTrue(output.getValue("abilities").toString().contains("Bound MCP"))
    }

    @Test fun abilitiesFollowActualRegisteredToolsRatherThanConfiguredSwitches() {
        val all = setOf("search_web", "recent_chats", "eval_javascript", "get_time_info", "clipboard_tool",
            "text_to_speech", "ask_user", "get_screen_time", "calendar_query", "calendar_create",
            "workspace_read_file", "workspace_shell", "computer_start", "use_skill",
            "extensions_catalog", "extensions_preview_changes")
        val abilities = environment(tools = all).getValue("abilities").jsonArray.map { it.jsonPrimitive.content }
        for (required in listOf("联网搜索", "工作空间 shell", "电脑操作", "Skill：Enabled skill", "读写剪贴板", "创建日历事件"))
            assertTrue(required, required in abilities)
        val disabled = environment(settings.copy(assistants = listOf(a.copy(enableWebSearch = true), b, c)))
        assertFalse(disabled.getValue("abilities").toString().contains("联网搜索"))
        assertFalse(disabled.getValue("abilities").toString().contains("Enabled skill"))
        val partner = environment(access = AgentAccess.forAssistant(a.copy(useGlobalMemory = true), false), tools = all)
        assertTrue(partner.getValue("abilities").toString().contains("全局记忆"))
        assertTrue(partner.getValue("abilities").toString().contains("本回合只读"))
        assertTrue(partner.getValue("abilities").toString().contains("仅参与的伙伴聊天"))
    }

    @Test fun blankNameAvatarVariantsAndNoBindingsUseSafeDisplayValues() {
        for ((avatar, type) in listOf(Avatar.Dummy to "default",
            Avatar.Emoji("secret-avatar-content") to "emoji", Avatar.WhaleGirl() to "whale_girl")) {
            val s = settings.copy(assistants = listOf(a.copy(name = "  ", avatar = avatar)))
            val output = buildMyEnvironment(AgentAccess.forAssistant(a, false), s, model, "Provider",
                emptySet(), emptyList(), null, null, emptyList())
            assertEquals("默认助手", output.getValue("name").jsonPrimitive.content)
            assertEquals(type, output.getValue("avatar").jsonPrimitive.content)
            assertFalse(output.containsKey("workspace"))
            assertFalse(output.containsKey("computer"))
            assertFalse(output.toString().contains("secret-avatar-content"))
        }
    }

    @Test fun miffanAvatarReportsSerialKindAndPaletteWithoutPaths() {
        for (kind in MiffanKind.entries) for (palette in MiffanPalette.entries) {
            val s = settings.copy(assistants = listOf(a.copy(avatar = Avatar.Miffan(MiffanAppearance(palette, kind)))))
            val avatar = environment(s).getValue("avatar").jsonObject
            assertEquals(setOf("type", "kind", "palette"), avatar.keys)
            assertEquals("miffan", avatar.getValue("type").jsonPrimitive.content)
            assertEquals(Json.encodeToJsonElement(kind), avatar.getValue("kind"))
            assertEquals(Json.encodeToJsonElement(palette), avatar.getValue("palette"))
        }
    }

    @Test fun readOnlyToolWorksInBothTurnOriginsAndRejectsProfessionalMode() = runTest {
        for (owner in listOf(false, true)) {
            var loaded = false
            val tool = createMyEnvironmentTool(AgentAccess.forAssistant(a, owner), settings, model, "Provider",
                { emptySet() }, emptyList(), null, null, { loaded = true; emptyList() }, setOf(b.id))
            assertFalse(tool.needsApproval(buildJsonObject {}))
            val output = (tool.execute(buildJsonObject {}).single() as UIMessagePart.Text).text
            assertTrue(loaded)
            assertTrue(output.contains("B"))
            assertFalse(output.contains("foreign-prompt"))
            assertTrue(tool.description.contains("who you are"))
        }
        assertTrue(runCatching {
            createMyEnvironmentTool(AgentAccess.forAssistant(a, true), settings.withInterfaceMode(InterfaceMode.PROFESSIONAL),
                model, "Provider", { emptySet() }, emptyList(), null, null, { emptyList() })
        }.isFailure)
    }
}
