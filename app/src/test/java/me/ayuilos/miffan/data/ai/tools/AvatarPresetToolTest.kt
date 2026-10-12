package me.ayuilos.miffan.data.ai.tools

import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.ai.AgentAccess
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.*
import me.ayuilos.miffan.data.revision.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AvatarPresetToolTest {
    private class History : RevisionStore {
        val rows = MutableStateFlow<List<Revision>>(emptyList())
        val queried = mutableListOf<Pair<RevisionSubject, String>>()
        override suspend fun head(subject: RevisionSubject, subjectId: String) =
            rows.value.lastOrNull { it.subject == subject && it.subjectId == subjectId }
        override suspend fun get(id: String) = rows.value.find { it.id == id }
        override suspend fun insert(revision: Revision) { rows.value += revision }
        override suspend fun <T> transaction(block: suspend () -> T) = block()
        override fun history(subject: RevisionSubject, subjectId: String) = rows.map { list ->
            queried += subject to subjectId
            list.filter { it.subject == subject && it.subjectId == subjectId }.reversed()
        }
        override fun byAuthor(author: RevisionAuthor, subjectIds: List<String>) = rows.map { list ->
            list.filter { it.author == author && it.subjectId in subjectIds }
        }
    }

    private class Fixture(initial: Avatar = Avatar.Image("file:///own/image.png")) {
        val caller = Assistant(name = "A", avatar = initial, systemPrompt = "own prompt")
        val other = Assistant(name = "B", avatar = Avatar.Image("file:///other/private.png"))
        val state = MutableStateFlow(Settings(assistants = listOf(caller, other)))
        val store = mockk<SettingsStore>()
        val history = History()
        val revisions = RevisionRepository(history)
        val recorder = AssistantRevisionRecorder(revisions)
        val service = RevisionService(revisions, store, mockk())
        val trigger = MessageRef(Uuid.random(), Uuid.random())
        init {
            every { store.settingsFlow } returns state
            coEvery { store.update(any<(Settings) -> Settings>()) } coAnswers {
                val before = state.value
                val after = firstArg<(Settings) -> Settings>()(before)
                state.value = after
                recorder.onAssistantsChanged(before.assistants, after.assistants)
            }
        }
        val tool = buildSelfProfileTools(AgentAccess.forAssistant(caller, true), true, store, trigger, service) {
            setOf("😀", "🐳")
        }.single()
        val current: Assistant get() = state.value.assistants.first()
        suspend fun call(input: JsonObject): JsonObject = Json.parseToJsonElement(
            (tool.execute(input).single() as UIMessagePart.Text).text,
        ).jsonObject
        suspend fun preset(value: String, extra: JsonObject = JsonObject(emptyMap())) = call(buildJsonObject {
            put("avatar_preset", value); put("reason", "换头像"); extra.forEach { (key, value) -> put(key, value) }
        })
    }
    private fun JsonObject.succeeded() = getValue("success").jsonPrimitive.boolean

    @Test fun allMiffanSerialValuesAreAcceptedAndOtherTypesUseDefaults() = runTest {
        val f = Fixture()
        assertTrue(f.preset("miffan").succeeded())
        assertEquals(Avatar.Miffan(), f.current.avatar)
        for (kind in MiffanKind.entries) for (palette in MiffanPalette.entries) {
            val result = f.preset("miffan", buildJsonObject {
                put("miffan_kind", Json.encodeToJsonElement(kind))
                put("miffan_palette", Json.encodeToJsonElement(palette))
            })
            assertTrue(result.succeeded())
            assertEquals(Avatar.Miffan(MiffanAppearance(palette, kind)), f.current.avatar)
        }
        assertEquals(f.other, f.state.value.assistants.last())
    }

    @Test fun omittedMiffanFieldsAndBothPresetMotionProfilesUseLatestSettings() = runTest {
        val f = Fixture()
        // Simulate the owner editing the avatar while this tool was awaiting approval.
        val latest = Avatar.Miffan(MiffanAppearance(MiffanPalette.INK_JADE, MiffanKind.STARGAZER,
            MiffanColorSource.APP_THEME), MiffanMotionProfile.CALM)
        f.state.value = f.state.value.copy(assistants = listOf(f.caller.copy(avatar = latest), f.other))
        assertTrue(f.preset("miffan").succeeded())
        assertEquals(latest, f.current.avatar)
        assertTrue(f.preset("miffan", buildJsonObject { put("miffan_kind", "sprout") }).succeeded())
        assertEquals(latest.copy(appearance = latest.appearance.copy(kind = MiffanKind.SPROUT)), f.current.avatar)
        assertTrue(f.preset("miffan", buildJsonObject { put("miffan_palette", "sea_salt") }).succeeded())
        assertEquals(latest.copy(appearance = latest.appearance.copy(kind = MiffanKind.SPROUT,
            palette = MiffanPalette.SEA_SALT)), f.current.avatar)
        assertTrue(f.preset("whale_girl").succeeded())
        assertEquals(Avatar.WhaleGirl(MiffanMotionProfile.CALM), f.current.avatar)
        assertTrue(f.preset("miffan").succeeded())
        assertEquals(Avatar.Miffan(motionProfile = MiffanMotionProfile.CALM), f.current.avatar)
        val fresh = Fixture(Avatar.Emoji("😀"))
        assertTrue(fresh.preset("whale_girl").succeeded())
        assertEquals(Avatar.WhaleGirl(), fresh.current.avatar)
    }

    @Test fun invalidOptionsFailWithoutAnyWritesOrRevisions() = runTest {
        val f = Fixture()
        val invalid = listOf(
            """{"avatar_preset":"miffan","avatar_emoji":"😀"}""",
            """{"avatar_preset":"unknown"}""", """{"avatar_preset":null}""",
            """{"miffan_kind":"rice"}""", """{"miffan_palette":"classic"}""",
            """{"avatar_preset":"miffan","miffan_kind":"RICE"}""",
            """{"avatar_preset":"miffan","miffan_palette":"unknown"}""",
            """{"avatar_preset":"miffan","miffan_kind":42}""",
            """{"avatar_preset":"miffan","miffan_palette":null}""",
            """{"avatar_preset":"whale_girl","miffan_kind":"rice"}""",
            """{"avatar_preset":"previous","miffan_palette":"classic"}""",
        )
        for (input in invalid) {
            assertFalse(input, f.call(JsonObject(Json.parseToJsonElement(input).jsonObject + ("reason" to JsonPrimitive("头像")))).succeeded())
        }
        coVerify(exactly = 0) { f.store.update(any<(Settings) -> Settings>()) }
        assertTrue(f.history.rows.value.isEmpty())
        assertEquals(f.caller, f.current)
    }

    @Test fun previousRestoresImageAndEmojiAndSkipsLatestUnchangedAvatars() = runTest {
        val f = Fixture()
        assertTrue(f.call(buildJsonObject { put("avatar_emoji", "😀"); put("reason", "头像") }).succeeded())
        assertTrue(f.call(buildJsonObject { put("name", "Renamed"); put("reason", "名字") }).succeeded())
        assertEquals(f.caller.avatar, f.service.previousAvatar(f.caller.id))
        assertTrue(f.preset("previous").succeeded())
        assertEquals(f.caller.avatar, f.current.avatar)
        assertEquals("Renamed", f.current.name)
        assertEquals(Avatar.Emoji("😀"), f.service.previousAvatar(f.caller.id))
        assertTrue(f.preset("previous").succeeded())
        assertEquals(Avatar.Emoji("😀"), f.current.avatar)
        assertEquals(f.other, f.state.value.assistants.last())
        assertTrue(f.history.queried.all { it == RevisionSubject.ASSISTANT to f.caller.id.toString() })
        // No image URL is echoed in tool results; only the UI preview gets the Avatar.
        assertFalse(f.preset("previous").toString().contains("file:"))
    }

    @Test fun missingOrSameOnlyHistoryFailsAtomicallyAndOtherPartnersAreNeverRead() = runTest {
        val f = Fixture(Avatar.Emoji("😀"))
        f.recorder.onAssistantsChanged(listOf(f.other), listOf(f.other.copy(avatar = Avatar.Emoji("🐳"))))
        assertNull(f.service.previousAvatar(f.caller.id))
        val result = f.preset("previous", buildJsonObject { put("name", "Must not change") })
        assertFalse(result.succeeded())
        assertEquals("没有更早的头像", result.getValue("error").jsonPrimitive.content)
        assertEquals(f.caller, f.current)
        assertTrue(f.history.rows.value.all { it.subjectId == f.other.id.toString() })
        assertTrue(f.history.queried.all { it == RevisionSubject.ASSISTANT to f.caller.id.toString() })
        assertTrue(f.call(buildJsonObject { put("name", "Same avatar"); put("reason", "名字") }).succeeded())
        assertNull(f.service.previousAvatar(f.caller.id))
        assertFalse(f.preset("previous").succeeded())
        assertEquals("Same avatar", f.current.name)
    }

    @Test fun presetAndPreviousRevisionsHaveAgentOriginAndUndoRoundTrip() = runTest {
        val f = Fixture()
        for (preset in listOf("miffan", "whale_girl")) {
            val before = f.current
            assertTrue(f.preset(preset).succeeded())
            val revision = f.history.rows.value.last()
            assertEquals(RevisionAuthor.AGENT, revision.author)
            assertEquals(f.trigger, revision.trigger)
            assertEquals("换头像", revision.summary)
            assertEquals(RestoreResult.Restored, f.service.undo(revision.id))
            assertEquals(before, f.current)
            assertEquals(RevisionAuthor.RESTORE, f.history.rows.value.last().author)
        }
        assertTrue(f.preset("miffan").succeeded())
        val before = f.current
        assertTrue(f.preset("previous").succeeded())
        assertEquals(f.caller.avatar, f.current.avatar)
        val revision = f.history.rows.value.last()
        assertEquals(RevisionAuthor.AGENT, revision.author)
        assertEquals(RestoreResult.Restored, f.service.undo(revision.id))
        assertEquals(before, f.current)
        assertEquals(f.other, f.state.value.assistants.last())
    }

    @Test fun deletedCallerCannotPreviewOrChangeOtherPartner() = runTest {
        val f = Fixture()
        f.state.value = f.state.value.copy(assistants = listOf(f.other))
        assertNull(f.service.previousAvatar(f.caller.id))
        assertFalse(f.preset("miffan").succeeded())
        assertFalse(f.preset("previous").succeeded())
        assertEquals(listOf(f.other), f.state.value.assistants)
        assertTrue(f.history.rows.value.isEmpty())
    }
}
