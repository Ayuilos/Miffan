package me.ayuilos.miffan.data.appsettings

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.data.ai.tools.buildAppSettingsTools
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.repository.MemoryRepository
import me.ayuilos.miffan.data.revision.AiServicesRevisionRecorder
import me.ayuilos.miffan.data.revision.AssistantRevisionRecorder
import me.ayuilos.miffan.data.revision.CurrentToolCall
import me.ayuilos.miffan.data.revision.RestoreResult
import me.ayuilos.miffan.data.revision.Revision
import me.ayuilos.miffan.data.revision.RevisionAuthor
import me.ayuilos.miffan.data.revision.RevisionOrigin
import me.ayuilos.miffan.data.revision.RevisionRepository
import me.ayuilos.miffan.data.revision.RevisionService
import me.ayuilos.miffan.data.revision.RevisionStore
import me.ayuilos.miffan.data.revision.RevisionSubject
import me.ayuilos.miffan.data.revision.ToolCallChangeState
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerSettingsChangeTest {
    private val thinker = Model(modelId = "thinker", displayName = "Thinker", abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING))
    private val plain = Model(modelId = "plain", displayName = "Plain")
    private val provider = ProviderSetting.OpenAI(name = "Relay", baseUrl = "https://relay.example.com/v1", apiKey = "sk-relay-key-123456",
        models = listOf(thinker, plain))
    private val whale = Assistant(name = "小鲸")
    private val fox = Assistant(name = "小狐", chatModelId = plain.id, workspaceShellEnabled = false)

    private fun settings() = Settings(providers = listOf(provider), chatModelId = thinker.id, fastModelId = thinker.id,
        assistants = listOf(whale, fox))

    private fun changes(vararg items: String) = Json.parseToJsonElement("""{"changes":[${items.joinToString(",")}],"reason":"r"}""")

    private fun ok(plan: AppSettingsPlan) = plan as? AppSettingsPlan.Ok ?: error("expected Ok, got $plan")

    private fun invalid(plan: AppSettingsPlan) = (plan as? AppSettingsPlan.Invalid ?: error("expected Invalid, got $plan")).reason

    @Test
    fun aPartnerAdjustsItselfAndAnotherPartner() {
        val plan = ok(planAppSettingsChange(changes(
            """{"type":"set_partner_settings","partner_id":"${whale.id}","thinking":"quick","reply_limit":4096,"temperature":1.2}""",
            """{"type":"set_partner_settings","partner_id":"${fox.id}","context_messages":"40","memory":true,"shared_memory":true}"""),
            settings()))
        val whaleAfter = plan.settings.assistants.first { it.id == whale.id }
        val foxAfter = plan.settings.assistants.first { it.id == fox.id }
        assertEquals(ThinkTier.QUICK.levelFor(thinker), whaleAfter.reasoningLevel)
        assertEquals(4096, whaleAfter.maxTokens)
        assertEquals(1.2f, whaleAfter.temperature)
        assertEquals(40, foxAfter.contextMessageLimit)
        assertTrue(foxAfter.enableMemory && foxAfter.useGlobalMemory)
        // Nothing else of the other partner moves, its permissions least of all.
        assertEquals(fox.copy(contextMessageLimit = 40, enableMemory = true, useGlobalMemory = true), foxAfter)
        assertEquals(listOf(
            AppSettingsLine.PartnerSetting(whale.id.toString(), "小鲸", "thinking", "quick"),
            AppSettingsLine.PartnerSetting(whale.id.toString(), "小鲸", "reply_limit", "4096"),
            AppSettingsLine.PartnerSetting(whale.id.toString(), "小鲸", "temperature", "1.2"),
            AppSettingsLine.PartnerSetting(fox.id.toString(), "小狐", "context_messages", "40"),
            AppSettingsLine.PartnerSetting(fox.id.toString(), "小狐", "memory", "on"),
            AppSettingsLine.PartnerSetting(fox.id.toString(), "小狐", "shared_memory", "on"),
        ), plan.lines)

        // The words for "no setting of its own" clear them again.
        val cleared = ok(planAppSettingsChange(changes(
            """{"type":"set_partner_settings","partner_id":"${whale.id}","reply_limit":"none","context_messages":"all","temperature":"default"}"""),
            plan.settings)).settings.assistants.first { it.id == whale.id }
        assertNull(cleared.maxTokens)
        assertEquals(0, cleared.contextMessageLimit)
        assertNull(cleared.temperature)
    }

    @Test
    fun permissionsAndOutOfRangeValuesAreRefused() {
        fun refused(fields: String) = invalid(planAppSettingsChange(changes(
            """{"type":"set_partner_settings","partner_id":"${whale.id}",$fields}"""), settings()))
        assertTrue("only for the owner" in refused(""""workspaceShellEnabled":true"""))
        assertTrue("only for the owner" in refused(""""memory":true,"computer_use":"auto""""))
        assertTrue("only for the owner" in refused(""""system_prompt":"be rude""""))
        assertTrue("reply_limit" in refused(""""reply_limit":100"""))
        assertTrue("context_messages" in refused(""""context_messages":5"""))
        assertTrue("temperature" in refused(""""temperature":3"""))
        assertTrue("temperature" in refused(""""temperature":"hot""""))
        assertTrue("thinking" in refused(""""thinking":"forever""""))
        assertTrue("at least one" in invalid(planAppSettingsChange(changes(
            """{"type":"set_partner_settings","partner_id":"${whale.id}"}"""), settings())))
        // A partner whose model cannot think has no thinking tiers.
        assertTrue("cannot think" in invalid(planAppSettingsChange(changes(
            """{"type":"set_partner_settings","partner_id":"${fox.id}","thinking":"deep"}"""), settings())))
        assertTrue("partner_id" in invalid(planAppSettingsChange(changes(
            """{"type":"set_partner_settings","partner_id":"nobody","memory":true}"""), settings())))
        // Asking for what is already set changes nothing.
        assertTrue("Nothing would change" in invalid(planAppSettingsChange(changes(
            """{"type":"set_partner_settings","partner_id":"${whale.id}","memory":false}"""), settings())))
    }

    @Test
    fun theCatalogShowsEachPartnersReplySettings() {
        val catalog = buildAppSettingsCatalog(settings().copy(assistants = listOf(whale.copy(maxTokens = 2048, reasoningLevel = ReasoningLevel.HIGH), fox)))
        val partners = catalog["partners"]!!.jsonArray.map { it.jsonObject }
        val whaleSettings = partners.first { it["id"]!!.jsonPrimitive.content == whale.id.toString() }["settings"]!!.jsonObject
        assertEquals("2048", whaleSettings["reply_limit"]!!.jsonPrimitive.content)
        assertEquals("all", whaleSettings["context_messages"]!!.jsonPrimitive.content)
        assertEquals("default", whaleSettings["temperature"]!!.jsonPrimitive.content)
        assertTrue("thinking" in whaleSettings)
        val foxSettings = partners.first { it["id"]!!.jsonPrimitive.content == fox.id.toString() }["settings"]!!.jsonObject
        assertFalse("thinking" in foxSettings)
    }

    private class FakeStore : RevisionStore {
        val revisions = MutableStateFlow<List<Revision>>(emptyList())
        override suspend fun head(subject: RevisionSubject, subjectId: String) =
            revisions.value.lastOrNull { it.subject == subject && it.subjectId == subjectId }
        override suspend fun get(id: String) = revisions.value.firstOrNull { it.id == id }
        override suspend fun insert(revision: Revision) { revisions.value = revisions.value + revision }
        override suspend fun <T> transaction(block: suspend () -> T): T = block()
        override fun history(subject: RevisionSubject, subjectId: String): Flow<List<Revision>> =
            revisions.map { list -> list.filter { it.subject == subject && it.subjectId == subjectId }.reversed() }
        override fun byToolCallId(toolCallId: String) = revisions.map { list -> list.filter { it.toolCallId == toolCallId } }
        override fun byAuthor(author: RevisionAuthor, subjectIds: List<String>): Flow<List<Revision>> =
            revisions.map { list -> list.filter { it.author == author && it.subjectId in subjectIds } }
    }

    @Test
    fun oneUndoTakesBackBothPartnersAndTheDisplayChange() = runBlocking {
        val revisions = RevisionRepository(FakeStore())
        val services = AiServicesRevisionRecorder(revisions)
        val assistants = AssistantRevisionRecorder(revisions)
        val state = MutableStateFlow(settings())
        val store = mockk<SettingsStore>()
        every { store.settingsFlow } returns state
        coEvery { store.update(any<(Settings) -> Settings>()) } coAnswers {
            val old = state.value
            val next = firstArg<(Settings) -> Settings>()(old)
            state.value = next
            assistants.onAssistantsChanged(old.assistants, next.assistants)
            services.onSettingsChanged(old, next)
        }
        // Each partner already has a version, as every saved partner does.
        state.value.assistants.forEach { revisions.record(RevisionSubject.ASSISTANT, it.id.toString(), null, AssistantRevisionRecorder.snapshot(it)) }
        val service = RevisionService(revisions, store, mockk<MemoryRepository>(relaxed = true))
        val tool = buildAppSettingsTools(store, recorder = services).first { it.name == CHANGE_APP_SETTINGS_TOOL }
        val output = withContext(CurrentToolCall("c1", approved = true) + RevisionOrigin(RevisionAuthor.AGENT)) {
            tool.execute(changes(
                """{"type":"set_partner_settings","partner_id":"${whale.id}","thinking":"deep"}""",
                """{"type":"set_partner_settings","partner_id":"${fox.id}","temperature":0.3,"memory":true}""",
                """{"type":"set_display","font_size":"large"}"""))
        }
        val text = (output.single() as UIMessagePart.Text).text
        assertTrue(text, "\"success\":true" in text && "partner_setting" in text)
        assertEquals(0.3f, state.value.assistants.first { it.id == fox.id }.temperature)
        assertEquals(ToolCallChangeState.Undoable, service.observeToolCallChange("c1").first()!!.state)

        assertEquals(RestoreResult.Restored, service.undoToolCall("c1"))
        assertEquals(settings().assistants, state.value.assistants)
        assertEquals(1.0f, state.value.displaySetting.fontSizeRatio)
        assertEquals(RestoreResult.Restored, service.redoToolCall("c1"))
        assertEquals(ThinkTier.DEEP.levelFor(thinker), state.value.assistants.first { it.id == whale.id }.reasoningLevel)
        assertTrue(state.value.assistants.first { it.id == fox.id }.enableMemory)
    }
}
