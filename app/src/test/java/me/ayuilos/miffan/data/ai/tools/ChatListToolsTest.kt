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
import java.time.Instant
import kotlin.uuid.Uuid

class ChatListToolsTest {
    private val a = Assistant(name = "A", systemPrompt = "secret A")
    private val b = Assistant(name = "B", systemPrompt = "secret B")
    private val c = Assistant(name = "C", avatar = Avatar.Image("file:///private/image"))
    private val access = AgentAccess.forAssistant(a, true)
    private val trigger = MessageRef(Uuid.random(), Uuid.random())
    private val direct = PartnerChat(Uuid.random(), PartnerChatKind.DIRECT, listOf(a.id, b.id), "ignored private direct title", Instant.EPOCH)
    private val group = PartnerChat(Uuid.random(), PartnerChatKind.GROUP, listOf(a.id, c.id), "Visible group", Instant.EPOCH, a.id)
    private val hiddenDirect = PartnerChat(Uuid.random(), PartnerChatKind.DIRECT, listOf(b.id, c.id), "hidden direct", Instant.EPOCH)
    private val hiddenGroup = PartnerChat(Uuid.random(), PartnerChatKind.GROUP, listOf(b.id, c.id), "hidden group", Instant.EPOCH, b.id)
    private val allChats = listOf(direct, group, hiddenDirect, hiddenGroup)
    private val folder = ChatGroup(name = "Folder", chatIds = listOf(b.id, hiddenDirect.id, direct.id))
    private val hiddenFolder = ChatGroup(name = "Hidden contents", chatIds = listOf(hiddenGroup.id))
    private val organization = ChatOrganization(pinned = listOf(hiddenGroup.id, b.id, group.id), groups = listOf(folder, hiddenFolder))
    private fun List<UIMessagePart>.result() = Json.parseToJsonElement((single() as UIMessagePart.Text).text).jsonObject
    private fun operations(vararg operations: JsonObject) = JsonArray(operations.toList())
    private fun op(type: String, vararg args: Pair<String, String>) = buildJsonObject { put("type", type); args.forEach { (key, value) -> put(key, value) } }
    private val visible get() = setOf(a.id, b.id, c.id, direct.id, group.id)

    private inner class Fixture {
        val state = MutableStateFlow(Settings(assistants = listOf(a, b, c), chatOrganization = organization))
        val store = mockk<SettingsStore>()
        val service = mockk<PartnerChatService>()
        var origin: RevisionOrigin? = null
        var writes = 0
        init {
            every { store.settingsFlow } returns state
            every { service.observeSummaries() } returns MutableStateFlow(allChats.map { chat ->
                PartnerChatSummary(chat, PartnerChatMessage(Uuid.random(), chat.id, b.id, "secret preview", Instant.EPOCH), emptySet(), Instant.EPOCH, 99)
            })
            coEvery { store.update(any<(Settings) -> Settings>()) } coAnswers {
                origin = currentCoroutineContext()[RevisionOrigin]
                val changed = firstArg<(Settings) -> Settings>()(state.value)
                state.value = changed; writes++
            }
        }
        val tools get() = buildChatListTools(access, true, store, trigger, service)
        suspend fun organize(ops: JsonArray) = tools.last().execute(buildJsonObject { put("operations", ops); put("reason", "整理列表") }).result()
    }

    @Test fun registrationAndApproval() = runTest {
        val f = Fixture()
        assertTrue(buildChatListTools(access, false, f.store, trigger, f.service).isEmpty())
        assertTrue(buildChatListTools(access.copy(ownerInitiated = false), true, f.store, trigger, f.service).isEmpty())
        assertTrue(buildChatListTools(access, true, f.store, null, f.service).isEmpty())
        assertFalse(f.tools.first().needsApproval(JsonObject(emptyMap())))
        assertTrue(f.tools.last().needsApproval(JsonNull))
    }

    @Test fun viewIncludesPublicOwnerThreadsAndOnlyMemberChatsWithSafeFields() = runTest {
        val f = Fixture()
        val output = f.tools.first().execute(JsonObject(emptyMap())).result()
        val folders = output.getValue("folders").jsonArray
        assertEquals(listOf(folder.id.toString(), hiddenFolder.id.toString()), folders.map { it.jsonObject.getValue("folder_id").jsonPrimitive.content })
        assertTrue(folders.last().jsonObject.getValue("chats").jsonArray.isEmpty())
        val chats = folders.flatMap { it.jsonObject.getValue("chats").jsonArray } + output.getValue("ungrouped").jsonArray
        assertEquals(visible.map { it.toString() }.toSet(), chats.map { it.jsonObject.getValue("chat_id").jsonPrimitive.content }.toSet())
        chats.forEach { assertEquals(setOf("chat_id", "name", "type", "pinned"), it.jsonObject.keys) }
        val byId = chats.associate { it.jsonObject.getValue("chat_id").jsonPrimitive.content to it.jsonObject }
        assertEquals("A · B", byId.getValue(direct.id.toString()).getValue("name").jsonPrimitive.content)
        assertEquals("partner_chat", byId.getValue(direct.id.toString()).getValue("type").jsonPrimitive.content)
        assertEquals("Visible group", byId.getValue(group.id.toString()).getValue("name").jsonPrimitive.content)
        assertEquals("group_chat", byId.getValue(group.id.toString()).getValue("type").jsonPrimitive.content)
        assertEquals("partner_thread", byId.getValue(b.id.toString()).getValue("type").jsonPrimitive.content)
        assertEquals(listOf(b.id.toString(), group.id.toString()), output.getValue("pinned").jsonArray.map { it.jsonObject.getValue("chat_id").jsonPrimitive.content })
        listOf(hiddenDirect.id.toString(), hiddenGroup.id.toString(), "hidden direct", "hidden group", "secret", "file:///", "unread", "lastActivity", "createdAt").forEach {
            assertFalse(it, output.toString().contains(it))
        }
        assertEquals(0, f.writes)
    }

    @Test fun batchUsesRefsAndLatestSettingsWithAgentOrigin() = runTest {
        val f = Fixture()
        val tools = f.tools // owner changes while approval is pending
        val ownerFolder = ChatGroup(name = "Owner-added")
        f.state.value = f.state.value.copy(chatOrganization = organization.copy(groups = organization.groups + ownerFolder))
        val result = tools.last().execute(buildJsonObject {
            put("operations", operations(
                op("create_folder", "name" to " 新分组 ", "ref" to "new"),
                op("move_to_folder", "chat_id" to a.id.toString(), "ref" to "new"),
                op("pin", "chat_id" to a.id.toString()),
                op("unpin", "chat_id" to b.id.toString()),
                op("rename_folder", "folder_id" to folder.id.toString(), "name" to "改名"),
                op("move_to_folder", "chat_id" to direct.id.toString()),
            )); put("reason", "整理列表")
        }).result()
        assertTrue(result.getValue("success").jsonPrimitive.boolean)
        assertEquals(1, f.writes)
        val id = Uuid.parse(result.getValue("folder_refs").jsonObject.getValue("new").jsonPrimitive.content)
        val current = f.state.value.chatOrganization
        assertEquals(id, current.groupOf(a.id)?.id)
        assertEquals("新分组", current.groupOf(a.id)?.name)
        assertNull(current.groupOf(direct.id))
        assertEquals(ownerFolder, current.groups.first { it.id == ownerFolder.id })
        assertEquals(listOf(hiddenGroup.id, group.id, a.id), current.pinned)
        assertEquals(hiddenFolder, current.groups.first { it.id == hiddenFolder.id })
        assertTrue(hiddenDirect.id in current.groups.first { it.id == folder.id }.chatIds)
        assertEquals(RevisionAuthor.AGENT, f.origin?.author)
        assertEquals(trigger, f.origin?.trigger)
        assertEquals("整理列表", f.origin?.summary)
    }

    @Test fun anyInvalidStepRollsBackEntireBatchAndHiddenChatsAreIndistinguishable() = runTest {
        val f = Fixture()
        for (type in listOf("pin", "unpin", "move_to_folder")) {
            var absent: JsonObject? = null
            for (id in listOf(Uuid.random(), hiddenDirect.id, hiddenGroup.id)) {
                val result = f.organize(operations(op("create_folder", "name" to "temporary", "ref" to "tmp"), op(type, "chat_id" to id.toString())))
                assertFalse(result.getValue("success").jsonPrimitive.boolean)
                assertEquals(2, result.getValue("operation_index").jsonPrimitive.int)
                if (absent == null) absent = result else assertEquals(absent, result)
                assertEquals(organization, f.state.value.chatOrganization)
            }
        }
        assertEquals(0, f.writes)
    }

    @Test fun emptyFolderDeletionCountsInvisibleChatsAndBatchChanges() = runTest {
        val f = Fixture()
        val failed = f.organize(operations(op("delete_empty_folder", "folder_id" to hiddenFolder.id.toString())))
        assertFalse(failed.getValue("success").jsonPrimitive.boolean)
        assertEquals(organization, f.state.value.chatOrganization)
        val emptied = f.organize(operations(
            op("move_to_folder", "chat_id" to b.id.toString()), op("move_to_folder", "chat_id" to direct.id.toString()),
            op("delete_empty_folder", "folder_id" to folder.id.toString()),
        ))
        assertFalse(emptied.getValue("success").jsonPrimitive.boolean)
        assertEquals(3, emptied.getValue("operation_index").jsonPrimitive.int)
        assertEquals(organization, f.state.value.chatOrganization)
        val empty = ChatGroup(name = "empty")
        f.state.value = f.state.value.copy(chatOrganization = organization.copy(groups = organization.groups + empty))
        assertTrue(f.organize(operations(op("delete_empty_folder", "folder_id" to empty.id.toString()))).getValue("success").jsonPrimitive.boolean)
        assertEquals(organization, f.state.value.chatOrganization)
    }

    @Test fun limitsFieldValidationRefsAndNames() = runTest {
        val f = Fixture()
        val invalid = listOf(
            JsonArray(List(51) { op("pin", "chat_id" to a.id.toString()) }),
            operations(op("create_folder", "name" to " ")), operations(op("create_folder", "name" to "x".repeat(31))),
            operations(op("rename_folder", "folder_id" to folder.id.toString(), "name" to "x".repeat(31))),
            operations(op("move_to_folder", "chat_id" to a.id.toString(), "ref" to "missing")),
            operations(op("move_to_folder", "chat_id" to a.id.toString(), "folder_id" to folder.id.toString(), "ref" to "x")),
            operations(op("move_to_folder", "chat_id" to a.id.toString(), "folder_id" to Uuid.random().toString())),
            operations(op("create_folder", "name" to "X", "ref" to "same"), op("create_folder", "name" to "Y", "ref" to "same")),
            operations(op("create_folder", "name" to "X", "ref" to " ")),
            operations(op("remove_member")), operations(op("pin", "chat_id" to a.id.toString(), "extra" to "ignored")),
            JsonArray(listOf(JsonNull)), operations(buildJsonObject { put("type", "create_folder"); put("name", 42) }),
        ) + listOf(JsonNull, JsonPrimitive("1"), JsonPrimitive(1.5), JsonPrimitive(0), JsonPrimitive(2147483648L)).map {
            operations(buildJsonObject { put("type", "move_folder"); put("folder_id", folder.id.toString()); put("offset", it) })
        }
        invalid.forEach { assertFalse("$it", f.organize(it).getValue("success").jsonPrimitive.boolean); assertEquals(organization, f.state.value.chatOrganization) }
        assertEquals(0, f.writes)
        assertTrue(f.organize(JsonArray(emptyList())).getValue("success").jsonPrimitive.boolean)
        assertEquals(0, f.writes)
        for (name in listOf("X", "x".repeat(30), "🐳".repeat(30))) {
            val result = f.organize(operations(op("create_folder", "name" to name)))
            assertTrue(result.getValue("success").jsonPrimitive.boolean)
            assertEquals(name, f.state.value.chatOrganization.groups.last().name)
        }
        assertTrue(f.organize(JsonArray(List(50) { op("pin", "chat_id" to a.id.toString()) })).getValue("success").jsonPrimitive.boolean)
    }

    @Test fun arbitraryOffsetsAndExistingFolderTargets() {
        val third = ChatGroup(name = "third")
        val initial = organization.copy(groups = organization.groups + third)
        val moved = applyChatListOperations(initial, visible, operations(buildJsonObject { put("type", "move_folder"); put("folder_id", folder.id.toString()); put("offset", 2) })).organization
        assertEquals(listOf(hiddenFolder.id, third.id, folder.id), moved.groups.map { it.id })
        val back = applyChatListOperations(moved, visible, operations(buildJsonObject { put("type", "move_folder"); put("folder_id", folder.id.toString()); put("offset", -2) })).organization
        assertEquals(initial, back)
        val target = applyChatListOperations(initial, visible, operations(op("move_to_folder", "chat_id" to a.id.toString(), "folder_id" to third.id.toString()))).organization
        assertEquals(third.id, target.groupOf(a.id)?.id)
    }

    @Test fun duplicateImportedFolderIdsCannotDeleteHiddenContents() {
        val duplicateId = folder.id
        val corrupt = ChatOrganization(groups = listOf(
            ChatGroup(id = duplicateId, name = "Empty"),
            ChatGroup(id = duplicateId, name = "Hidden", chatIds = listOf(hiddenGroup.id)),
        ))
        val pinned = applyChatListOperations(corrupt, visible, operations(op("pin", "chat_id" to a.id.toString()))).organization
        assertEquals(corrupt.groups, pinned.groups)
        try {
            applyChatListOperations(corrupt, visible, operations(op("delete_empty_folder", "folder_id" to duplicateId.toString())))
            fail("Must not delete any nonempty folder with this id")
        } catch (e: ChatListOperationError) {
            assertEquals(1, e.index)
            assertEquals("Folder is not empty.", e.message)
        }
    }

    @Test fun importedHiddenPinsAndMembershipArePreservedExactly() {
        val corrupt = organization.copy(
            pinned = listOf(hiddenGroup.id, hiddenGroup.id, a.id, a.id),
            groups = listOf(folder.copy(chatIds = listOf(hiddenDirect.id, hiddenDirect.id, a.id, a.id)), hiddenFolder.copy(chatIds = listOf(hiddenDirect.id, hiddenGroup.id))),
        )
        val result = applyChatListOperations(corrupt, visible, operations(op("pin", "chat_id" to c.id.toString()), op("move_to_folder", "chat_id" to a.id.toString(), "folder_id" to hiddenFolder.id.toString()))).organization
        assertEquals(corrupt.pinned.filter { it !in visible }, result.pinned.filter { it !in visible })
        corrupt.groups.forEach { old -> assertEquals(old.chatIds.filter { it !in visible }, result.groups.first { it.id == old.id }.chatIds.filter { it !in visible }) }
        assertEquals(1, result.groups.flatMap { it.chatIds }.count { it == a.id })
    }
}
