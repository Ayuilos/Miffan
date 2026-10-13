package me.ayuilos.miffan.data.ai.tools

import me.ayuilos.miffan.data.ai.AgentAccess
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.db.fts.MessageSearchResult
import me.ayuilos.miffan.data.db.fts.MessageSearchSort
import me.ayuilos.miffan.data.model.*
import me.ayuilos.miffan.data.partner.PartnerChatService
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationRecallTest {
    private val a = Assistant(name = "小A")
    private val b = Assistant(name = "小B")
    private val c = Assistant(name = "小C")
    private val repo = mockk<ConversationRepository>()
    private val chats = mockk<PartnerChatService>()
    private val settings = Settings(assistants = listOf(a, b, c)).withInterfaceMode(InterfaceMode.IM)
    private fun chat(kind: PartnerChatKind, title: String = "", members: List<Uuid> = listOf(a.id, b.id)) =
        PartnerChat(Uuid.random(), kind, members, title, Instant.ofEpochSecond(1))
    private fun result(title: String, chat: PartnerChat? = null) = MessageSearchResult(
        "node", "message", Uuid.random().toString(), title, Instant.ofEpochSecond(1), "[needle]", chat?.id?.toString())
    private fun parse(parts: List<UIMessagePart>) = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonArray
    private suspend fun search(s: Settings = settings): JsonArray = parse(createConversationTools(repo, AgentAccess.forAssistant(a, true), s, chats)
        .single { it.name == "conversation_search" }.execute(buildJsonObject { put("query", "needle") }))

    @Test fun searchLabelsDirectNamedGroupFallbackGroupAndOwner() = runTest {
        val direct = chat(PartnerChatKind.DIRECT)
        val group = chat(PartnerChatKind.GROUP, "秋天群")
        val fallback = chat(PartnerChatKind.GROUP, members = listOf(a.id, b.id, c.id))
        coEvery { repo.searchMessages("needle", MessageSearchSort.RELEVANCE, AgentAccess.forAssistant(a, true)) } returns
            listOf(result("主人话题"), result("stale direct", direct), result("stale group", group), result("stale fallback", fallback))
        listOf(direct, group, fallback).forEach { every { chats.observeChat(it.id) } returns flowOf(it) }
        val rows = search().map { it.jsonObject }
        assertEquals(listOf("主人话题", "和 小B 的私聊", "秋天群", "小A · 小B · 小C"), rows.map { it.getValue("title").jsonPrimitive.content })
        assertEquals(listOf("owner_thread", "partner_chat", "group_chat", "group_chat"), rows.map { it.getValue("source").jsonPrimitive.content })
        assertFalse(rows.first().containsKey("chat_id"))
        assertEquals(direct.id.toString(), rows[1].getValue("chat_id").jsonPrimitive.content)
        assertEquals("[needle]", rows[1].getValue("snippet").jsonPrimitive.content)
    }

    @Test fun recentMixesActivityLimitsAndExcludesOtherPartnersEvenWithoutPerspectives() = runTest {
        val owner = Conversation(assistantId = a.id, title = "主人话题", updateAt = Instant.ofEpochSecond(30), messageNodes = emptyList())
        val direct = chat(PartnerChatKind.DIRECT)
        val group = chat(PartnerChatKind.GROUP, "群名")
        val unrelated = chat(PartnerChatKind.DIRECT, members = listOf(b.id, c.id))
        coEvery { repo.getRecentConversations(a.id, 2, true) } returns listOf(owner)
        every { chats.observeSummaries() } returns flowOf(listOf(
            PartnerChatSummary(direct, null, emptySet(), Instant.ofEpochSecond(40)),
            PartnerChatSummary(group, null, emptySet(), Instant.ofEpochSecond(20)),
            PartnerChatSummary(unrelated, null, emptySet(), Instant.ofEpochSecond(50))))
        val rows = parse(createConversationTools(repo, AgentAccess.forAssistant(a, true), settings, chats).first()
            .execute(buildJsonObject { put("limit", 2) })).map { it.jsonObject }
        assertEquals(listOf("和 小B 的私聊", "主人话题"), rows.map { it.getValue("title").jsonPrimitive.content })
        assertEquals(setOf("id", "title", "last_chat", "source", "chat_id"), rows.first().keys)
        coVerify { repo.getRecentConversations(a.id, 2, true) }
        coEvery { repo.getRecentConversations(a.id, 3, true) } returns listOf(owner)
        val groupRow = parse(createConversationTools(repo, AgentAccess.forAssistant(a, true), settings, chats).first()
            .execute(buildJsonObject { put("limit", 3) })).last().jsonObject
        assertEquals("群名", groupRow.getValue("title").jsonPrimitive.content)
        assertEquals("group_chat", groupRow.getValue("source").jsonPrimitive.content)
        assertEquals(group.id.toString(), groupRow.getValue("chat_id").jsonPrimitive.content)
        assertTrue(groupRow.containsKey("last_chat"))
    }

    @Test fun professionalSearchKeepsScopeTitlesAndPayload() = runTest {
        coEvery { repo.searchMessages("needle", MessageSearchSort.RELEVANCE, AgentAccess.forAssistant(a, true)) } returns listOf(result("原始视角标题", chat(PartnerChatKind.GROUP)))
        val row = search(settings.withInterfaceMode(InterfaceMode.PROFESSIONAL)).single().jsonObject
        assertEquals("原始视角标题", row.getValue("title").jsonPrimitive.content)
        assertEquals(setOf("conversation_id", "title", "snippet", "date"), row.keys)
        verify { chats wasNot Called }
    }

    @Test fun professionalRecentKeepsPinnedOrderingAndPayload() = runTest {
        val pinned = Conversation(assistantId = a.id, title = "旧置顶", updateAt = Instant.ofEpochSecond(1), messageNodes = emptyList())
        val newer = pinned.copy(id = Uuid.random(), title = "新话题", updateAt = Instant.ofEpochSecond(2))
        coEvery { repo.getRecentConversations(a.id, 10, false) } returns listOf(pinned, newer)
        val tools = createConversationTools(repo, AgentAccess.forAssistant(a, true), settings.withInterfaceMode(InterfaceMode.PROFESSIONAL), chats)
        val rows = parse(tools.first().execute(buildJsonObject {})).map { it.jsonObject }
        assertEquals(listOf("旧置顶", "新话题"), rows.map { it.getValue("title").jsonPrimitive.content })
        assertEquals(setOf("id", "title", "last_chat"), rows.first().keys)
        verify { chats wasNot Called }
    }

    @Test fun imDescriptionsAdvertisePartnerRecall() {
        createConversationTools(repo, AgentAccess.forAssistant(a, true), settings, chats).forEach {
            assertTrue(it.description.contains("private chats and group chats"))
        }
    }
    @Test fun oldPerspectiveTitlesResolveBlankNamesLiveAndAdvertiseDirectReading() = runTest {
        val unnamed = b.copy(name = "")
        val direct = chat(PartnerChatKind.DIRECT)
        val group = chat(PartnerChatKind.GROUP)
        coEvery { repo.searchMessages("needle", MessageSearchSort.RELEVANCE, AgentAccess.forAssistant(a, true)) } returns
            listOf(result("与 伙伴 的私聊", direct), result("群聊：伙伴", group))
        listOf(direct, group).forEach { every { chats.observeChat(it.id) } returns flowOf(it) }
        val rows = search(settings.copy(assistants = listOf(a, unnamed))).map { it.jsonObject }
        assertEquals(listOf("和 默认助手 的私聊", "小A · 默认助手"), rows.map { it.getValue("title").jsonPrimitive.content })
        assertTrue(rows.all { it.getValue("read_hint").jsonPrimitive.content.contains("read_partner_chat") })
    }

    @Test fun partnerTurnSkipsOwnerRecentQueryAndPassesRestrictedSearchInBothModes() = runTest {
        val access = AgentAccess.forAssistant(a, false)
        val direct = chat(PartnerChatKind.DIRECT)
        every { chats.observeSummaries() } returns flowOf(listOf(
            PartnerChatSummary(direct, null, emptySet(), Instant.ofEpochSecond(40))))
        for (mode in InterfaceMode.entries) {
            val tools = createConversationTools(repo, access, settings.withInterfaceMode(mode), chats)
            val recent = parse(tools.first().execute(buildJsonObject {}))
            assertEquals(if (mode == InterfaceMode.IM) 1 else 0, recent.size)
            assertTrue(recent.none { it.jsonObject["source"]?.jsonPrimitive?.content == "owner_thread" })
            coEvery { repo.searchMessages("needle", MessageSearchSort.RELEVANCE, access) } returns emptyList()
            assertTrue(parse(tools[1].execute(buildJsonObject { put("query", "needle") })).isEmpty())
            coVerify { repo.searchMessages("needle", MessageSearchSort.RELEVANCE, access) }
            assertTrue(tools.take(2).all { it.description.contains("owner conversations are unavailable") })
        }
        coVerify(exactly = 0) { repo.getRecentConversations(any(), any(), any()) }
    }

    @Test fun ownerSearchAlwaysPassesCallerInBothModes() = runTest {
        val access = AgentAccess.forAssistant(a, true)
        for (mode in InterfaceMode.entries) {
            coEvery { repo.searchMessages("needle", MessageSearchSort.RELEVANCE, access) } returns emptyList()
            search(settings.withInterfaceMode(mode))
        }
        coVerify(exactly = 2) { repo.searchMessages("needle", MessageSearchSort.RELEVANCE, access) }
    }

}
