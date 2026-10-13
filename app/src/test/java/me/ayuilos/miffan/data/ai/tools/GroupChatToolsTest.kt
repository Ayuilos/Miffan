package me.ayuilos.miffan.data.ai.tools

import io.mockk.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.ai.AgentAccess
import me.ayuilos.miffan.data.datastore.*
import me.ayuilos.miffan.data.model.*
import me.ayuilos.miffan.data.partner.*
import me.ayuilos.miffan.data.revision.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GroupChatToolsTest {
    private val a = Assistant(name = "A")
    private val b = Assistant(name = "B")
    private val c = Assistant(name = "C")
    private val d = Assistant(name = "D")
    private val access = AgentAccess.forAssistant(a, true)
    private val trigger = MessageRef(Uuid.random(), Uuid.random())
    private fun List<UIMessagePart>.result() = Json.parseToJsonElement((single() as UIMessagePart.Text).text).jsonObject

    @Test fun registrationAndApproval() = runTest {
        val store = mockk<SettingsStore>(); val service = mockk<PartnerChatService>()
        assertTrue(buildGroupChatTools(access, false, store, trigger, service).isEmpty())
        assertTrue(buildGroupChatTools(access.copy(ownerInitiated = false), true, store, trigger, service).isEmpty())
        assertTrue(buildGroupChatTools(access, true, store, null, service).isEmpty())
        val tools = buildGroupChatTools(access, true, store, trigger, service)
        assertEquals(listOf("create_group_chat", "invite_to_group_chat"), tools.map { it.name })
        tools.forEach { assertTrue(it.needsApproval(JsonNull)) }
    }

    @Test fun realServiceCreationInvitationAndNonmemberPrivacy() = runTest {
        val state = MutableStateFlow(Settings(assistants = listOf(a, b, c, d)))
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns state
        val backing = FakePartnerStore()
        val runtime = mockk<PartnerChatRuntime>()
        val service = DefaultPartnerChatService(backing, settingsStore, runtime, backgroundScope)
        val tools = buildGroupChatTools(access, true, settingsStore, trigger, service)
        suspend fun create(members: List<Uuid>) = tools[0].execute(buildJsonObject {
            put("title", "学习群"); put("member_ids", buildJsonArray { members.forEach { add(it.toString()) } }); put("reason", "建群")
        }).result()
        val created = create(listOf(b.id))
        assertTrue(created.getValue("success").jsonPrimitive.boolean)
        val id = Uuid.parse(created.getValue("chat_id").jsonPrimitive.content)
        assertEquals(listOf(a.id, b.id), backing.chat(id)!!.memberIds)
        assertEquals(a.id, backing.chat(id)!!.hostId)
        service.setHost(id, b.id)
        suspend fun invite(chatId: Uuid, ids: List<Uuid>) = tools[1].execute(buildJsonObject {
            put("chat_id", chatId.toString()); put("member_ids", buildJsonArray { ids.forEach { add(it.toString()) } }); put("reason", "拉人")
        }).result()
        assertTrue(invite(id, listOf(b.id, c.id, c.id)).getValue("success").jsonPrimitive.boolean)
        assertEquals(listOf(a.id, b.id, c.id), backing.chat(id)!!.memberIds)
        assertEquals(b.id, backing.chat(id)!!.hostId)
        // A stale proposed member list must still merge with the latest list under the management lock.
        service.setMembers(id, listOf(d.id), access)
        assertEquals(listOf(a.id, b.id, c.id, d.id), backing.chat(id)!!.memberIds)
        assertEquals(b.id, backing.chat(id)!!.hostId)
        val hidden = service.createGroup(listOf(b.id, c.id), "hidden")
        assertEquals(invite(Uuid.random(), listOf(d.id)), invite(hidden, listOf(d.id)))
        assertFalse(create(listOf(a.id, b.id)).getValue("success").jsonPrimitive.boolean)
        assertFalse(create(listOf(b.id, b.id)).getValue("success").jsonPrimitive.boolean)
        assertFalse(create(emptyList()).getValue("success").jsonPrimitive.boolean)
        assertFalse(create(listOf(Uuid.random())).getValue("success").jsonPrimitive.boolean)
        coVerify(exactly = 0) { runtime.stop(any()) }
    }

    @Test fun invalidInputsNoWritesAndRevisionOrigin() = runTest {
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns MutableStateFlow(Settings(assistants = listOf(a, b)))
        val service = mockk<PartnerChatService>()
        var origin: RevisionOrigin? = null
        coEvery { service.createGroup(any(), any()) } coAnswers {
            origin = currentCoroutineContext()[RevisionOrigin]; Uuid.random()
        }
        val create = buildGroupChatTools(access, true, settingsStore, trigger, service).first()
        val invalid = listOf("null", "{}", """{"title":2,"member_ids":[],"reason":"why"}""",
            """{"title":"X","member_ids":null,"reason":"why"}""", """{"title":"X","member_ids":[null],"reason":"why"}""",
            """{"title":"X","member_ids":[],"reason":" "}""", """{"title":"X","member_ids":["owner"],"reason":"why"}""")
        invalid.forEach { assertFalse(create.execute(Json.parseToJsonElement(it)).result().getValue("success").jsonPrimitive.boolean) }
        coVerify(exactly = 0) { service.createGroup(any(), any()) }
        create.execute(buildJsonObject { put("title", "X"); put("member_ids", buildJsonArray { add(b.id.toString()) }); put("reason", "建群") })
        assertEquals(RevisionAuthor.AGENT, origin?.author)
        assertEquals(trigger, origin?.trigger)
        assertEquals("建群", origin?.summary)
    }
}
