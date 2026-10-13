package me.ayuilos.miffan.data.ai.tools

import io.mockk.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.ai.AgentAccess
import me.ayuilos.miffan.data.datastore.*
import me.ayuilos.miffan.data.model.*
import me.ayuilos.miffan.data.revision.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class CreatePartnerToolTest {
    private val caller = Assistant(name = "Caller", systemPrompt = "private")
    private val trigger = MessageRef(Uuid.random(), Uuid.random())
    private val access = AgentAccess.forAssistant(caller, true)
    private fun tools(store: SettingsStore, authority: AgentAccess = access, im: Boolean = true, ref: MessageRef? = trigger) =
        buildCreatePartnerTools(authority, im, store, ref) { setOf("🐳", "👍🏽", "👨‍👩‍👧") }
    private fun List<UIMessagePart>.result() = Json.parseToJsonElement((single() as UIMessagePart.Text).text).jsonObject

    @Test fun registrationAndApproval() = runTest {
        val store = mockk<SettingsStore>()
        assertTrue(tools(store, access.copy(ownerInitiated = false)).isEmpty())
        assertTrue(tools(store, im = false).isEmpty())
        assertTrue(tools(store, ref = null).isEmpty())
        assertTrue(tools(store).single().needsApproval(JsonNull))
    }

    @Test fun defaultsGrantAndAddedRevision() = runTest {
        val state = MutableStateFlow(Settings(assistants = listOf(caller)))
        val store = mockk<SettingsStore>()
        val revisions = mutableListOf<Revision>()
        val history = mockk<RevisionStore>()
        coEvery { history.head(any(), any()) } returns null
        coEvery { history.insert(any()) } coAnswers { revisions += firstArg<Revision>() }
        coEvery { history.transaction<Revision?>(any()) } coAnswers { firstArg<suspend () -> Revision?>()() }
        val recorder = AssistantRevisionRecorder(RevisionRepository(history))
        coEvery { store.update(any<(Settings) -> Settings>()) } coAnswers {
            val before = state.value
            state.value = firstArg<(Settings) -> Settings>()(before)
            recorder.onAssistantsChanged(before.assistants, state.value.assistants)
        }
        val result = tools(store).single().execute(buildJsonObject { put("name", " 新伙伴 "); put("reason", "主人要新伙伴") }).result()
        assertTrue(result.getValue("success").jsonPrimitive.boolean)
        val created = state.value.assistants.last()
        assertEquals(Assistant(id = created.id, name = "新伙伴", useAssistantAvatar = true), created)
        assertEquals(listOf(PartnerPair.of(caller.id, created.id)), state.value.partnerContactGrants)
        assertEquals(caller, state.value.assistants.first())
        assertEquals(created.id.toString(), result.getValue("partner_id").jsonPrimitive.content)
        val revision = revisions.single() // before=null creates no baseline
        assertNull(revision.parentId)
        assertEquals(RevisionAuthor.AGENT, revision.author)
        assertEquals(trigger, revision.trigger)
        assertEquals("主人要新伙伴", revision.summary)
        assertEquals(created, AssistantRevisionRecorder.restore(revision.snapshot))
    }

    @Test fun invalidInputsAndCapabilitiesNeverWrite() = runTest {
        val store = mockk<SettingsStore>()
        val invalid = listOf("null", "[]", "{}", """{"name":"X"}""", """{"name":" ","reason":"why"}""",
            """{"name":1,"reason":"why"}""", """{"name":"X","reason":" "}""")
            .map { Json.parseToJsonElement(it) } + listOf(
            buildJsonObject { put("name", "x".repeat(41)); put("reason", "why") },
            buildJsonObject { put("name", "X"); put("reason", "why"); put("system_prompt", "x".repeat(8001)) },
        ) + listOf("chatModelId", "model", "tools", "mcpServers", "workspace_id", "assistant_id").map {
            buildJsonObject { put("name", "X"); put("reason", "why"); put(it, "forbidden") }
        } + listOf(
            """{"avatar_preset":"previous"}""", """{"avatar_emoji":"🐳","avatar_preset":"miffan"}""",
            """{"miffan_kind":"rice"}""", """{"avatar_preset":"miffan","miffan_kind":"bad"}""",
            """{"avatar_preset":"miffan","miffan_palette":"bad"}""", """{"avatar_emoji":"🐳🐳"}""",
        ).map { Json.parseToJsonElement(it).jsonObject + buildJsonObject { put("name", "X"); put("reason", "why") } }.map(::JsonObject)
        invalid.forEach { assertFalse("$it", tools(store).single().execute(it).result().getValue("success").jsonPrimitive.boolean) }
        coVerify(exactly = 0) { store.update(any<(Settings) -> Settings>()) }
    }

    @Test fun sharedAvatarValidationAndLatestCallerCheck() = runTest {
        val state = MutableStateFlow(Settings(assistants = listOf(caller)))
        val store = mockk<SettingsStore>()
        var origin: RevisionOrigin? = null
        coEvery { store.update(any<(Settings) -> Settings>()) } coAnswers {
            origin = currentCoroutineContext()[RevisionOrigin]
            state.value = firstArg<(Settings) -> Settings>()(state.value)
        }
        val tool = tools(store).single()
        for (avatar in listOf(
            buildJsonObject { put("avatar_emoji", "👨‍👩‍👧") },
            buildJsonObject { put("avatar_preset", "whale_girl") },
            buildJsonObject { put("avatar_preset", "miffan"); put("miffan_kind", "sprout"); put("miffan_palette", "sea_salt") },
        )) {
            val params = JsonObject(avatar + buildJsonObject { put("name", "🐳".repeat(40)); put("system_prompt", "x".repeat(8000)); put("reason", "头像") })
            assertTrue(tool.execute(params).result().getValue("success").jsonPrimitive.boolean)
            assertEquals(validateProfileAvatar(params, { setOf("👨‍👩‍👧") }, false).resolve(Assistant().avatar), state.value.assistants.last().avatar)
            assertEquals(RevisionAuthor.AGENT, origin?.author)
        }
        state.value = state.value.copy(assistants = emptyList())
        assertFalse(tool.execute(buildJsonObject { put("name", "X"); put("reason", "why") }).result().getValue("success").jsonPrimitive.boolean)
        assertTrue(state.value.assistants.isEmpty())
    }
}
