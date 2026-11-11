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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.ayuilos.miffan.data.datastore.DEFAULT_PROVIDERS
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
import me.ayuilos.miffan.data.revision.aiServicesSnapshot
import me.ayuilos.miffan.data.revision.restoreAiServices
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.OpenAICodexCredentials
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AppSettingsChangeTest {
    private val deepseekId = Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b")
    private val key = "sk-SENTINEL-CHANGE-7f3a9c2e81d04b6a5e7b0c21"
    private val relayKey = "sk-SENTINEL-RELAY-0a1b2c3d4e5f60718293a4b5"
    private val chat = Model(modelId = "deepseek-chat", displayName = "DeepSeek V3",
        customHeaders = listOf(CustomHeader("X-Secret", "header-$key")))
    private val relay = ProviderSetting.OpenAI(name = "Relay", baseUrl = "https://relay.example.com/v1", apiKey = relayKey,
        models = listOf(Model(modelId = "relay-1")))
    private val partner = Assistant(name = "小鲸")

    private fun settings(): Settings {
        val providers = DEFAULT_PROVIDERS.map { provider ->
            if (provider.id == deepseekId) (provider as ProviderSetting.OpenAI).copy(apiKey = key, models = listOf(chat)) else provider
        } + relay
        return Settings(providers = providers, assistants = listOf(partner), chatModelId = chat.id, fastModelId = chat.id)
    }

    private fun changes(vararg items: String) = Json.parseToJsonElement("""{"changes":[${items.joinToString(",")}],"reason":"r"}""")

    private fun ok(plan: AppSettingsPlan) = plan as? AppSettingsPlan.Ok ?: error("expected Ok, got $plan")

    private fun invalid(plan: AppSettingsPlan) = (plan as? AppSettingsPlan.Invalid ?: error("expected Invalid, got $plan")).reason

    private val addReasoner = """{"type":"add_models","provider_id":"$deepseekId","models":[{"model_id":"deepseek-reasoner","name":"DeepSeek R1"}]}"""
    private val useReasoner = """{"type":"set_partner_model","partner_id":"${partner.id}","model":{"provider_id":"$deepseekId","model_id":"deepseek-reasoner"}}"""

    @Test
    fun aModelAddedEarlierCanBeUsedLaterInTheSameChanges() {
        val plan = ok(planAppSettingsChange(changes(addReasoner, useReasoner,
            """{"type":"set_default_model","role":"title","model":{"provider_id":"$deepseekId","model_id":"deepseek-reasoner"}}"""),
            settings()))
        val deepseek = plan.settings.providers.first { it.id == deepseekId }
        val reasoner = deepseek.models.single { it.modelId == "deepseek-reasoner" }
        assertEquals("DeepSeek R1", reasoner.displayName)
        assertEquals(reasoner.id, plan.settings.assistants.single().chatModelId)
        assertEquals(reasoner.id, plan.settings.titleModelId)
        assertEquals(listOf(
            AppSettingsLine.ModelsAdded("DeepSeek", listOf("DeepSeek R1")),
            AppSettingsLine.PartnerModel(partner.id.toString(), "小鲸", "DeepSeek R1", "DeepSeek"),
            AppSettingsLine.DefaultModel("title", "DeepSeek R1", "DeepSeek"),
        ), plan.lines)
        // Nothing about keys changed.
        assertEquals(key, (deepseek as ProviderSetting.OpenAI).apiKey)
    }

    @Test
    fun abilitiesComeFromTheModelIdUnlessThePartnerSetsThem() {
        val plan = ok(planAppSettingsChange(changes(
            """{"type":"add_models","provider_id":"$deepseekId","models":[{"model_id":"deepseek-reasoner"},{"model_id":"my-model","tools":true}]}"""),
            settings()))
        val models = plan.settings.providers.first { it.id == deepseekId }.models.associateBy { it.modelId }
        assertTrue(ModelAbility.REASONING in models.getValue("deepseek-reasoner").abilities)
        assertTrue(ModelAbility.TOOL in models.getValue("my-model").abilities)
        assertTrue(models.getValue("my-model").capabilitiesEdited)
    }

    @Test
    fun callsTheAppCannotApplyAreRefusedWithAReason() {
        val s = settings()
        assertTrue("provider_id" in invalid(planAppSettingsChange(changes("""{"type":"rename_provider","provider_id":"nope","name":"x"}"""), s)))
        assertTrue("no model" in invalid(planAppSettingsChange(changes(useReasoner), s)))
        assertTrue("Unknown type" in invalid(planAppSettingsChange(changes("""{"type":"delete_provider","provider_id":"$deepseekId"}"""), s)))
        assertTrue("Nothing would change" in invalid(planAppSettingsChange(changes(
            """{"type":"set_provider_enabled","provider_id":"$deepseekId","enabled":${s.providers.first { it.id == deepseekId }.enabled}}"""), s)))
        // The chat default uses this model: removing it would leave chat without a model.
        assertTrue("still used" in invalid(planAppSettingsChange(changes(
            """{"type":"remove_models","provider_id":"$deepseekId","model_ids":["deepseek-chat"]}"""), s)))
        // Switching off the service the chat default runs on, likewise.
        assertTrue("still used" in invalid(planAppSettingsChange(changes(
            """{"type":"set_provider_enabled","provider_id":"$deepseekId","enabled":false}"""), s)))
        // A built-in service keeps its address, so its key is never sent elsewhere.
        assertTrue("built-in" in invalid(planAppSettingsChange(changes(
            """{"type":"set_provider_address","provider_id":"$deepseekId","base_url":"https://evil.example.com/v1"}"""), s)))
        assertTrue("user name" in invalid(planAppSettingsChange(changes(
            """{"type":"set_provider_address","provider_id":"${relay.id}","base_url":"https://u:p@relay2.example.com/v1"}"""), s)))
        assertTrue("follow_fast" !in invalid(planAppSettingsChange(changes(
            """{"type":"set_default_model","role":"chat","model":"follow_fast"}"""), s)))
        // The index tells the partner which change to fix.
        assertTrue(invalid(planAppSettingsChange(changes(addReasoner, """{"type":"bogus"}"""), s)).startsWith("changes[1]"))
    }

    @Test
    fun aModelOnAServiceThatIsOffCannotBeAssigned() {
        val off = settings().let { s -> s.copy(providers = s.providers.map { if (it.id == relay.id) it.copyProvider(enabled = false) else it }) }
        val use = """{"type":"set_partner_model","partner_id":"${partner.id}","model":{"provider_id":"${relay.id}","model_id":"relay-1"}}"""
        assertTrue("switched off" in invalid(planAppSettingsChange(changes(use), off)))
        ok(planAppSettingsChange(changes("""{"type":"set_provider_enabled","provider_id":"${relay.id}","enabled":true}""", use), off))
    }

    @Test
    fun movingACustomServiceClearsItsKey() {
        val plan = ok(planAppSettingsChange(changes(
            """{"type":"set_provider_address","provider_id":"${relay.id}","base_url":"https://relay2.example.com/v1/"}"""), settings()))
        val moved = plan.settings.providers.first { it.id == relay.id } as ProviderSetting.OpenAI
        assertEquals("https://relay2.example.com/v1", moved.baseUrl)
        assertEquals("", moved.apiKey)
        assertEquals(listOf(AppSettingsLine.ProviderAddress("Relay", "relay2.example.com", clearsKey = true)), plan.lines)
        val result = appSettingsResultJson(plan.lines)
        assertTrue("connect_provider" in result)
        assertEquals(plan.lines, appSettingsResultLines(result))
    }

    @Test
    fun theResultAndTheSnapshotsNeverHoldACredential() {
        val withSecrets = settings().let { s ->
            s.copy(providers = s.providers + ProviderSetting.OpenAI(name = "ChatGPT", authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
                codexCredentials = OpenAICodexCredentials(accessToken = "tok-$key", refreshToken = "ref-$key", accountId = "a",
                    email = "owner@example.com")) +
                ProviderSetting.Google(name = "Vertex", vertexAI = true, useServiceAccount = true, privateKey = "pk-$key",
                    serviceAccountEmail = "sa@example.iam.gserviceaccount.com"))
        }
        val plan = ok(planAppSettingsChange(changes(addReasoner, useReasoner,
            """{"type":"set_provider_address","provider_id":"${relay.id}","base_url":"https://relay2.example.com/v1"}"""), withSecrets))
        val texts = listOf(appSettingsResultJson(plan.lines), aiServicesSnapshot(withSecrets), aiServicesSnapshot(plan.settings))
        texts.forEach { text ->
            listOf(key, relayKey, "7f3a9c2e", "owner@example.com", "sa@example", "X-Secret").forEach { assertFalse("leaked $it", it in text) }
        }
    }

    @Test
    fun restoringKeepsTodaysKeysAndHeadersButNeverSendsAKeyToAnotherAddress() {
        val before = settings()
        val snapshot = aiServicesSnapshot(before)
        // Since then: the relay moved (losing its key) and got a new key for the new address; a header changed.
        val now = before.copy(providers = before.providers.map { provider ->
            when (provider.id) {
                relay.id -> relay.copy(baseUrl = "https://relay2.example.com/v1", apiKey = "sk-new-key-for-relay2")
                deepseekId -> (provider as ProviderSetting.OpenAI).copy(models = listOf(chat.copy(displayName = "Renamed",
                    customHeaders = listOf(CustomHeader("X-Secret", "newer")))))
                else -> provider
            }
        })
        val restored = now.restoreAiServices(snapshot)
        val deepseek = restored.providers.first { it.id == deepseekId } as ProviderSetting.OpenAI
        assertEquals(key, deepseek.apiKey)
        assertEquals("DeepSeek V3", deepseek.models.single().displayName)
        assertEquals("newer", deepseek.models.single().customHeaders.single().value)
        val back = restored.providers.first { it.id == relay.id } as ProviderSetting.OpenAI
        assertEquals("https://relay.example.com/v1", back.baseUrl)
        assertEquals("", back.apiKey)
        assertEquals(before.providers.map { it.id }, restored.providers.map { it.id })
    }

    // End to end through the revision service: the partner's change is undone and redone as one, keys untouched.

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
    fun undoTakesBackModelsAndPartnerModelTogetherAndKeepsTheKey() = runBlocking {
        val revisions = RevisionRepository(FakeStore())
        val assistants = AssistantRevisionRecorder(revisions)
        val services = AiServicesRevisionRecorder(revisions)
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
        val service = RevisionService(revisions, store, mockk<MemoryRepository>(relaxed = true))
        val original = state.value
        withContext(CurrentToolCall("c1", approved = true) + RevisionOrigin(RevisionAuthor.AGENT)) {
            store.update { ok(planAppSettingsChange(changes(addReasoner, useReasoner), it)).settings }
        }
        // Later, unrelated: the owner replaces the DeepSeek key and renames the relay.
        store.update { s -> s.copy(providers = s.providers.map { p ->
            when (p.id) {
                deepseekId -> (p as ProviderSetting.OpenAI).copy(apiKey = "sk-replaced-by-owner")
                relay.id -> p.copyProvider(name = "My relay")
                else -> p
            }
        }) }
        val change = service.observeToolCallChange("c1").first()!!
        assertEquals(ToolCallChangeState.Undoable, change.state)
        assertEquals(setOf(RevisionSubject.AI_SERVICES, RevisionSubject.ASSISTANT), change.revisions.map { it.subject }.toSet())

        assertEquals(RestoreResult.Restored, service.undoToolCall("c1"))
        val undone = state.value
        assertEquals(original.providers.first { it.id == deepseekId }.models, undone.providers.first { it.id == deepseekId }.models)
        assertNull(undone.assistants.single().chatModelId)
        assertEquals("sk-replaced-by-owner", (undone.providers.first { it.id == deepseekId } as ProviderSetting.OpenAI).apiKey)
        assertEquals("My relay", undone.providers.first { it.id == relay.id }.name)
        assertEquals(ToolCallChangeState.Undone, service.observeToolCallChange("c1").first()!!.state)

        assertEquals(RestoreResult.Restored, service.redoToolCall("c1"))
        val redone = state.value
        val reasoner = redone.providers.first { it.id == deepseekId }.models.single { it.modelId == "deepseek-reasoner" }
        assertEquals(reasoner.id, redone.assistants.single().chatModelId)
        assertEquals("sk-replaced-by-owner", (redone.providers.first { it.id == deepseekId } as ProviderSetting.OpenAI).apiKey)
        // Snapshots in the history never held either key.
        (revisions.history(RevisionSubject.AI_SERVICES, "app").first()).forEach { revision ->
            assertFalse(key in revision.snapshot)
            assertFalse("sk-replaced-by-owner" in revision.snapshot)
            assertTrue(Json.parseToJsonElement(revision.snapshot) is JsonObject)
        }
    }
}
