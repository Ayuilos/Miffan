package me.ayuilos.miffan.data.ai.tools

import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.*
import me.ayuilos.miffan.data.partner.PartnerChatService
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ReadPartnerChatTest {
    private val a = Assistant(name = "蓝鲸")
    private val blank = Assistant(name = "")
    private val repo = mockk<ConversationRepository>()
    private val chats = mockk<PartnerChatService>()
    private val settings = Settings(assistants = listOf(a, blank)).withInterfaceMode(InterfaceMode.IM)
    private val chat = PartnerChat(Uuid.random(), PartnerChatKind.DIRECT, listOf(a.id, blank.id), createdAt = Instant.EPOCH)
    private fun tools(s: Settings = settings) = createConversationTools(repo, a.id, s, chats)
    private suspend fun read(id: String = chat.id.toString(), limit: Int? = null, s: Settings = settings): JsonObject {
        val parts = tools(s).single { it.name == "read_partner_chat" }.execute(buildJsonObject {
            put("chat_id", id); limit?.let { put("limit", it) }
        })
        return Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }
    private fun message(time: Long, sender: Uuid = blank.id, text: String = "message-$time") =
        PartnerChatMessage(Uuid.random(), chat.id, sender, text, Instant.ofEpochSecond(time))

    @Test fun memberReadsFinalMessagesInTimeOrderWithLimitNamesAndUnicodeTruncation() = runTest {
        every { chats.observeChat(chat.id) } returns flowOf(chat)
        every { chats.observeMessages(chat.id) } returns flowOf(listOf(
            message(3, OWNER_PARTICIPANT_ID, "😀".repeat(2001)), message(1), message(2)))
        val result = read(limit = 2)
        assertEquals("和 默认助手 的私聊", result.getValue("title").jsonPrimitive.content)
        assertEquals("direct", result.getValue("type").jsonPrimitive.content)
        val rows = result.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("1970-01-01T00:00:02Z", "1970-01-01T00:00:03Z"), rows.map { it.getValue("created_at").jsonPrimitive.content })
        assertEquals(listOf("默认助手", "主人"), rows.map { it.getValue("sender").jsonPrimitive.content })
        assertEquals("😀".repeat(2000), rows.last().getValue("text").jsonPrimitive.content)
        verify { repo wasNot Called }
    }

    @Test fun limitsDefaultTo20AndClampAt50AndOne() = runTest {
        every { chats.observeChat(chat.id) } returns flowOf(chat)
        every { chats.observeMessages(chat.id) } returns flowOf((1L..60L).map { message(it) })
        assertEquals(20, read().getValue("messages").jsonArray.size)
        val max = read(limit = 500).getValue("messages").jsonArray
        assertEquals(50, max.size)
        assertEquals("message-11", max.first().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(1, read(limit = 0).getValue("messages").jsonArray.size)
    }

    @Test fun nonMembersAreRefusedBeforeReadingAnyContent() = runTest {
        every { chats.observeChat(chat.id) } returns flowOf(chat.copy(memberIds = listOf(blank.id)))
        assertEquals(setOf("error"), read().keys)
        verify(exactly = 0) { chats.observeMessages(any()) }
        verify { repo wasNot Called }
    }

    @Test fun membershipRevocationDuringReadCannotExposeContent() = runTest {
        every { chats.observeChat(chat.id) } returnsMany listOf(flowOf(chat), flowOf(chat.copy(memberIds = listOf(blank.id))))
        every { chats.observeMessages(chat.id) } returns flowOf(listOf(message(1, text = "private")))
        val result = read()
        assertEquals(setOf("error"), result.keys)
        assertFalse(result.toString().contains("private"))
    }

    @Test fun nonexistentAndMalformedChatsReturnErrorsWithoutReadingMessages() = runTest {
        every { chats.observeChat(chat.id) } returns flowOf(null)
        assertEquals(setOf("error"), read().keys)
        assertEquals(setOf("error"), read(id = "not-a-uuid").keys)
        val tool = tools().single { it.name == "read_partner_chat" }
        assertTrue((tool.execute(buildJsonObject {}).single() as UIMessagePart.Text).text.contains("error"))
        verify(exactly = 0) { chats.observeMessages(any()) }
    }

    @Test fun groupTitleNicknameAndDeletedSenderAreShown() = runTest {
        every { chats.observeChat(chat.id) } returns flowOf(chat.copy(kind = PartnerChatKind.GROUP, title = "秋日"))
        every { chats.observeMessages(chat.id) } returns flowOf(listOf(message(1, OWNER_PARTICIPANT_ID), message(2, Uuid.random())))
        val s = settings.copy(displaySetting = settings.displaySetting.copy(userNickname = "饭主"))
        val result = read(s = s)
        assertEquals("秋日", result.getValue("title").jsonPrimitive.content)
        assertEquals("group", result.getValue("type").jsonPrimitive.content)
        assertEquals(listOf("饭主", "已删除的伙伴"), result.getValue("messages").jsonArray.map { it.jsonObject.getValue("sender").jsonPrimitive.content })
    }

    @Test fun professionalModeDoesNotRegisterReaderOrChangeDescriptions() {
        val professional = tools(settings.withInterfaceMode(InterfaceMode.PROFESSIONAL))
        assertEquals(listOf("recent_chats", "conversation_search"), professional.map { it.name })
        assertTrue(professional.none { it.description.contains("read_partner_chat") })
        for (tool in tools().filter { it.name != "read_partner_chat" }) assertTrue(tool.description.contains("read_partner_chat"))
    }
}
