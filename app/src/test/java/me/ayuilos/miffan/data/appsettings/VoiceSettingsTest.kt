package me.ayuilos.miffan.data.appsettings

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.data.ai.tools.buildAppSettingsTools
import me.ayuilos.miffan.data.datastore.DEFAULT_SYSTEM_TTS_ID
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.repository.MemoryRepository
import me.ayuilos.miffan.data.revision.AiServicesRevisionRecorder
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
import me.ayuilos.miffan.service.ChatService
import me.rerere.asr.ASRProviderSetting
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceSettingsTest {
    private val elevenKey = "el-SENTINEL-4b6a5e7b9c2e81d0"
    private val asrKey = "dash-SENTINEL-81d04b6a5e7b9c2e"
    private val system = TTSProviderSetting.SystemTTS(id = DEFAULT_SYSTEM_TTS_ID, name = "")
    private val eleven = TTSProviderSetting.ElevenLabs(name = "ElevenLabs", apiKey = elevenKey)
    private val openaiNoKey = TTSProviderSetting.OpenAI(name = "OpenAI TTS")
    private val qwen = TTSProviderSetting.Qwen(apiKey = "qwen-key")
    private val selfHosted = TTSProviderSetting.OpenAI(name = "Home TTS", baseUrl = "https://tts.home.example/v1", apiKey = "home-key")
    private val dash = ASRProviderSetting.DashScope(apiKey = asrKey)

    private fun settings() = Settings(ttsProviders = listOf(system, eleven, openaiNoKey, qwen, selfHosted),
        selectedTTSProviderId = system.id, asrProviders = listOf(dash))

    private fun json(text: String) = Json.parseToJsonElement(text)

    private fun changes(vararg items: String) = json("""{"changes":[${items.joinToString(",")}],"reason":"r"}""")

    private fun ok(plan: AppSettingsPlan) = plan as? AppSettingsPlan.Ok ?: error("expected Ok, got $plan")

    private fun invalid(plan: AppSettingsPlan) = (plan as? AppSettingsPlan.Invalid ?: error("expected Invalid, got $plan")).reason

    private fun target(input: String, settings: Settings = settings()) =
        (resolveVoiceTarget(json(input), settings) as? ConnectTargetResult.Ok)?.target as? VoiceTarget

    private fun refused(input: String) = (resolveVoiceTarget(json(input), settings()) as ConnectTargetResult.Invalid).reason

    @Test
    fun keyCardsOnlyGoToServicesWithAFixedAddress() {
        val new = target("""{"kind":"tts","type":"gemini"}""")!!
        assertNull(new.serviceId)
        assertEquals("generativelanguage.googleapis.com", new.origin)
        // One of each kind: asking for ElevenLabs again replaces the key of the one there.
        val again = target("""{"kind":"tts","type":"elevenlabs"}""")!!
        assertEquals(eleven.id, again.serviceId)
        assertTrue(again.hasKey)
        assertEquals("api.elevenlabs.io", again.origin)
        // Voice input addresses are WebSockets; the card shows their host.
        assertEquals("dashscope.aliyuncs.com", target("""{"kind":"asr","service_id":"${dash.id}"}""")!!.origin)
        assertTrue("speech settings page" in refused("""{"kind":"tts","service_id":"${qwen.id}"}"""))
        assertTrue("speech settings page" in refused("""{"kind":"tts","service_id":"${selfHosted.id}"}"""))
        assertTrue("speech settings page" in refused("""{"kind":"tts","service_id":"${system.id}"}"""))
        assertTrue("kind" in refused("""{"type":"gemini"}"""))
        assertTrue("one of" in refused("""{"kind":"asr","type":"elevenlabs"}"""))
    }

    @Test
    fun savingAVoiceKeyAddsWithoutSwitchingAndNeverEchoesTheKey() {
        val id = Uuid.random()
        val saved = settings().withVoiceKey(target("""{"kind":"tts","type":"gemini"}""")!!, "gm-NEW-KEY-123456", checked = true, newId = id)
            as SecretSave.Saved
        val added = saved.settings.ttsProviders.single { it.id == id }
        assertEquals("gm-NEW-KEY-123456", added.ttsApiKey())
        assertEquals(system.id, saved.settings.selectedTTSProviderId)
        assertFalse("gm-NEW-KEY" in saved.result)
        assertTrue("select_tts_service" in saved.result)
        // Replacing a voice input key keeps the service and its choice.
        val asr = settings().withVoiceKey(target("""{"kind":"asr","type":"dashscope"}""")!!, "dash-REPLACED-9876", checked = false)
            as SecretSave.Saved
        assertEquals(listOf(dash.id), asr.settings.asrProviders.map { it.id })
        assertEquals("dash-REPLACED-9876", asr.settings.asrProviders.single().asrApiKey())
        assertTrue("\"status\":\"saved\"" in asr.result)
        // Removed in between: nothing is stored anywhere.
        val gone = settings().copy(ttsProviders = listOf(system)).withVoiceKey(target("""{"kind":"tts","type":"elevenlabs"}""")!!, "x", true)
        assertEquals(SecretSave.TargetChanged, gone)
    }

    @Test
    fun theCatalogListsVoicesButNoKey() {
        val catalog = buildAppSettingsCatalog(settings()).toString()
        assertFalse(elevenKey in catalog)
        assertFalse(asrKey in catalog)
        assertFalse("qwen-key" in catalog || "home-key" in catalog)
        val tts = Json.parseToJsonElement(catalog).let { it as kotlinx.serialization.json.JsonObject }["voice"]!!
            .let { it as kotlinx.serialization.json.JsonObject }["tts"].toString()
        assertTrue("\"Rachel, calm American woman\"" in tts || "Rachel" in tts)
        assertTrue("\"key_card\":true" in tts && "\"key_card\":false" in tts)
    }

    @Test
    fun choosingServiceVoiceAndRules() {
        val plan = ok(planAppSettingsChange(changes(
            """{"type":"select_tts_service","service_id":"${eleven.id}"}""",
            """{"type":"set_tts_voice","voice":"21m00Tcm4TlvDq8ikWAM"}""",
            """{"type":"set_read_aloud","speed":1.25,"only_quoted":true}""",
            """{"type":"select_asr_service","service_id":"${dash.id}"}"""), settings()))
        assertEquals(eleven.id, plan.settings.selectedTTSProviderId)
        // The voice went to the service just put in use, earlier in the same changes.
        assertEquals("21m00Tcm4TlvDq8ikWAM", plan.settings.ttsProviders.first { it.id == eleven.id }.voice())
        assertEquals(1.25f, plan.settings.defaultTTSPlaybackSpeed)
        assertTrue(plan.settings.displaySetting.ttsOnlyReadQuoted)
        assertEquals(dash.id, plan.settings.selectedASRProviderId)
        assertEquals(listOf(
            AppSettingsLine.SettingChanged("tts_service", "ElevenLabs"),
            AppSettingsLine.TtsVoice("ElevenLabs", "elevenlabs", "21m00Tcm4TlvDq8ikWAM"),
            AppSettingsLine.SettingChanged("tts_speed", "1.25"),
            AppSettingsLine.SettingChanged("read_quoted", "on"),
            AppSettingsLine.SettingChanged("asr_service", "DashScope ASR"),
        ), plan.lines)
        // A voice outside the list is allowed; the card marks it.
        assertTrue(ok(planAppSettingsChange(changes("""{"type":"set_tts_voice","service_id":"${eleven.id}","voice":"myClonedVoice"}"""),
            settings())).lines.single() is AppSettingsLine.TtsVoice)
        assertTrue("no key" in invalid(planAppSettingsChange(changes("""{"type":"select_tts_service","service_id":"${openaiNoKey.id}"}"""), settings())))
        assertTrue("pitch" in invalid(planAppSettingsChange(changes("""{"type":"set_tts_voice","service_id":"${eleven.id}","pitch":1.2}"""), settings())))
        val pitched = ok(planAppSettingsChange(changes("""{"type":"set_tts_voice","pitch":1.2}"""), settings()))
        assertEquals(1.2f, (pitched.settings.ttsProviders.first() as TTSProviderSetting.SystemTTS).pitch)
        assertTrue("speed" in invalid(planAppSettingsChange(changes("""{"type":"set_read_aloud","speed":4}"""), settings())))
    }

    @Test
    fun snapshotsLeaveVoiceKeysOutAndRestoreKeepsThemOnlyWhereTheyWereEntered() {
        val before = settings()
        val snapshot = aiServicesSnapshot(before)
        assertFalse(elevenKey in snapshot || asrKey in snapshot || "home-key" in snapshot)
        val changed = ok(planAppSettingsChange(changes(
            """{"type":"select_tts_service","service_id":"${eleven.id}"}""",
            """{"type":"set_tts_voice","voice":"EXAVITQu4vr4xnSDxMaL"}""",
            """{"type":"set_read_aloud","skip_brackets":true}"""), before)).settings
        val restored = changed.restoreAiServices(snapshot)
        assertEquals(before.ttsProviders, restored.ttsProviders)
        assertEquals(system.id, restored.selectedTTSProviderId)
        assertFalse(restored.displaySetting.ttsOnlyReadOutsideBrackets)
        // The owner moved the home service elsewhere since: its old address comes back without the key.
        val moved = changed.copy(ttsProviders = changed.ttsProviders.map {
            if (it.id == selfHosted.id) (it as TTSProviderSetting.OpenAI).copy(baseUrl = "https://other.example/v1") else it
        })
        assertEquals("", moved.restoreAiServices(snapshot).ttsProviders.first { it.id == selfHosted.id }.ttsApiKey())
        // A service added after the snapshot stays.
        val added = TTSProviderSetting.Groq(apiKey = "groq-key")
        assertTrue(changed.copy(ttsProviders = changed.ttsProviders + added).restoreAiServices(snapshot).ttsProviders.any { it.id == added.id })
        // A snapshot from before voice was kept changes no voice setting.
        val old = (json(snapshot) as kotlinx.serialization.json.JsonObject)
            .filterKeys { !it.startsWith("tts") && !it.startsWith("asr") && !it.startsWith("display:tts") }
        val untouched = changed.restoreAiServices(kotlinx.serialization.json.JsonObject(old).toString())
        assertEquals(changed.ttsProviders, untouched.ttsProviders)
        assertEquals(changed.selectedTTSProviderId, untouched.selectedTTSProviderId)
    }

    @Test
    fun voiceKeysAreKeptEncrypted() {
        val lock: (String) -> String = { if (it.isBlank()) it else "enc:$it" }
        assertEquals("enc:$elevenKey", eleven.transformTtsSecret(lock).ttsApiKey())
        assertEquals(system, system.transformTtsSecret(lock))
        assertEquals("enc:$asrKey", dash.transformAsrSecret(lock).asrApiKey())
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

    private fun store(state: MutableStateFlow<Settings>, recorder: AiServicesRevisionRecorder? = null) = mockk<SettingsStore>().also { store ->
        every { store.settingsFlow } returns state
        coEvery { store.update(any<(Settings) -> Settings>()) } coAnswers {
            val old = state.value
            val next = firstArg<(Settings) -> Settings>()(old)
            state.value = next
            recorder?.onSettingsChanged(old, next)
        }
    }

    @Test
    fun switchingTheVoiceIsUndoneAsOneAndKeepsTheKey() = runBlocking {
        val revisions = RevisionRepository(FakeStore())
        val recorder = AiServicesRevisionRecorder(revisions)
        val state = MutableStateFlow(settings())
        val store = store(state, recorder)
        val service = RevisionService(revisions, store, mockk<MemoryRepository>(relaxed = true))
        val tool = buildAppSettingsTools(store, recorder = recorder).first { it.name == CHANGE_APP_SETTINGS_TOOL }
        withContext(CurrentToolCall("v1", approved = true) + RevisionOrigin(RevisionAuthor.AGENT)) {
            tool.execute(changes("""{"type":"select_tts_service","service_id":"${eleven.id}"}""",
                """{"type":"set_tts_voice","voice":"EXAVITQu4vr4xnSDxMaL"}""", """{"type":"set_read_aloud","speed":0.8}"""))
        }
        assertEquals(eleven.id, state.value.selectedTTSProviderId)
        assertEquals(ToolCallChangeState.Undoable, service.observeToolCallChange("v1").first()!!.state)
        assertEquals(RestoreResult.Restored, service.undoToolCall("v1"))
        assertEquals(system.id, state.value.selectedTTSProviderId)
        assertEquals(1.0f, state.value.defaultTTSPlaybackSpeed)
        val back = state.value.ttsProviders.first { it.id == eleven.id }
        assertEquals(eleven.voiceId, back.voice())
        assertEquals(elevenKey, back.ttsApiKey())
    }

    private class FakeSampler(private val fail: Exception? = null) : VoiceSampler {
        var played = 0
        var usedKey: String? = null
        override suspend fun synthesize(setting: TTSProviderSetting): TTSResponse? {
            usedKey = setting.ttsApiKey()
            fail?.let { throw it }
            return TTSResponse(byteArrayOf(1, 2, 3), AudioFormat.MP3)
        }
        override suspend fun play(response: TTSResponse) { played++ }
    }

    private fun cards(state: MutableStateFlow<Settings>, sampler: VoiceSampler, results: MutableList<String>): Pair<ProviderConnectCards, CoroutineScope> {
        val chat = mockk<ChatService>()
        coEvery { chat.completeSecretCard(any(), any(), any()) } coAnswers { results += thirdArg<String>(); true }
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        return ProviderConnectCards(store(state), mockk(relaxed = true), chat, scope, sampler) to scope
    }

    @Test
    fun aReadAloudKeyIsCheckedWithASampleTheOwnerHears() = runBlocking {
        val state = MutableStateFlow(settings())
        val results = mutableListOf<String>()
        val sampler = FakeSampler()
        val (cards, scope) = cards(state, sampler, results)
        val target = target("""{"kind":"tts","type":"gemini"}""", state.value)!!
        cards.connect(Uuid.random(), "c1", target, "  gm-GOOD-KEY-0001  ")
        scope.coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals("gm-GOOD-KEY-0001", sampler.usedKey)
        assertEquals(1, sampler.played)
        assertTrue(state.value.ttsProviders.any { it is TTSProviderSetting.Gemini && it.apiKey == "gm-GOOD-KEY-0001" })
        assertTrue("\"status\":\"connected\"" in results.single() && "gm-GOOD" !in results.single())

        // A rejected key is not stored, and the card says why without the key.
        val rejected = FakeSampler(IllegalStateException("401 Unauthorized: invalid api key gm-BAD-KEY-0002"))
        val (badCards, badScope) = cards(state, rejected, results)
        badCards.connect(Uuid.random(), "c2", target("""{"kind":"tts","type":"openai"}""", state.value)!!, "gm-BAD-KEY-0002")
        badScope.coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(ProviderConnectCards.Phase.KeyRejected, badCards.phases.value["c2"])
        assertEquals(0, rejected.played)
        assertTrue(state.value.ttsProviders.none { it.ttsApiKey() == "gm-BAD-KEY-0002" })
    }
}
