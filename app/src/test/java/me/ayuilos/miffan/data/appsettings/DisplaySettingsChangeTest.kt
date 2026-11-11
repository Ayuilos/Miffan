package me.ayuilos.miffan.data.appsettings

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.data.ai.tools.buildAppSettingsTools
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
import me.ayuilos.miffan.data.revision.appearanceIn
import me.ayuilos.miffan.data.revision.restoreAiServices
import me.ayuilos.miffan.ui.theme.CustomTheme
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySettingsChangeTest {
    private val chat = Model(modelId = "chat-1", displayName = "Chat One")
    private val painter = Model(modelId = "painter", displayName = "Painter", outputModalities = listOf(Modality.TEXT, Modality.IMAGE))
    private val provider = ProviderSetting.OpenAI(name = "Relay", baseUrl = "https://relay.example.com/v1", apiKey = "sk-relay-key-123456",
        models = listOf(chat, painter))

    private fun settings() = Settings(providers = listOf(provider), chatModelId = chat.id, fastModelId = chat.id, themeId = "sakura",
        dynamicColor = false, customThemes = listOf(CustomTheme(id = "mine", name = "My Teal")))

    private fun changes(vararg items: String) = Json.parseToJsonElement("""{"changes":[${items.joinToString(",")}],"reason":"r"}""")

    private fun ok(plan: AppSettingsPlan) = plan as? AppSettingsPlan.Ok ?: error("expected Ok, got $plan")

    private fun invalid(plan: AppSettingsPlan) = (plan as? AppSettingsPlan.Invalid ?: error("expected Invalid, got $plan")).reason

    @Test
    fun displayAndNotificationsChangeOnlyWhatIsAsked() {
        val plan = ok(planAppSettingsChange(changes(
            """{"type":"set_display","font_size":"large","latex":false}""",
            """{"type":"set_notifications","reply_done":false}""",
            """{"type":"set_owner_name","name":"小陈"}"""), settings()))
        val display = plan.settings.displaySetting
        assertEquals(1.15f, display.fontSizeRatio)
        assertFalse(display.enableLatexRendering)
        assertFalse(display.enableNotificationOnMessageGeneration)
        assertTrue(display.enableLiveUpdateNotification)
        assertEquals("小陈", display.userNickname)
        assertEquals(listOf(
            AppSettingsLine.SettingChanged("font_size", "large"),
            AppSettingsLine.SettingChanged("latex", "off"),
            AppSettingsLine.SettingChanged("reply_done_notice", "off"),
            AppSettingsLine.SettingChanged("owner_name", "小陈"),
        ), plan.lines)
        // A crop switch reads the way the owner says it: on means the app asks to crop.
        val crop = ok(planAppSettingsChange(changes("""{"type":"set_display","crop_before_send":true}"""), settings()))
        assertFalse(crop.settings.displaySetting.skipCropImage)
        assertTrue("one of" in invalid(planAppSettingsChange(changes("""{"type":"set_display","font_size":"huge"}"""), settings())))
        assertTrue("at most" in invalid(planAppSettingsChange(changes("""{"type":"set_owner_name","name":"${"长".repeat(21)}"}"""), settings())))
    }

    @Test
    fun themeAndLightOrDarkTravelTogether() {
        val plan = ok(planAppSettingsChange(changes("""{"type":"set_theme","theme":"mine","color_mode":"dark","pure_black":true}"""),
            settings(), appearance = Appearance()))
        assertEquals("mine", plan.settings.themeId)
        assertFalse(plan.settings.dynamicColor)
        assertEquals(Appearance(COLOR_MODE_DARK, amoled = true), plan.appearance)
        assertEquals(listOf(
            AppSettingsLine.SettingChanged("theme", "mine", label = "My Teal"),
            AppSettingsLine.SettingChanged("color_mode", "dark"),
            AppSettingsLine.SettingChanged("amoled", "on"),
        ), plan.lines)
        assertTrue(ok(planAppSettingsChange(changes("""{"type":"set_theme","theme":"wallpaper"}"""), settings())).settings.dynamicColor)
        // The whale character theme has its own entry; partners pick colours only.
        assertTrue("theme must be" in invalid(planAppSettingsChange(changes("""{"type":"set_theme","theme":"whale"}"""), settings())))
        assertTrue("color_mode" in invalid(planAppSettingsChange(changes("""{"type":"set_theme","color_mode":"sepia"}"""), settings())))
    }

    @Test
    fun theBackupReminderCanBeSwitchedOnAndUndone() {
        val before = settings().copy(backupReminderConfig = me.ayuilos.miffan.data.datastore.BackupReminderConfig(snoozedUntil = 99L))
        val plan = ok(planAppSettingsChange(changes("""{"type":"set_backup_reminder","enabled":true,"interval_days":14}"""), before))
        assertTrue(plan.settings.backupReminderConfig.enabled)
        assertEquals(14, plan.settings.backupReminderConfig.intervalDays)
        // Switched on again, an old "not today" no longer holds it back.
        assertEquals(0L, plan.settings.backupReminderConfig.snoozedUntil)
        assertEquals(listOf(AppSettingsLine.SettingChanged("backup_reminder", "on"), AppSettingsLine.SettingChanged("backup_interval", "14")), plan.lines)
        assertTrue("interval_days" in invalid(planAppSettingsChange(changes("""{"type":"set_backup_reminder","interval_days":365}"""), before)))
        val restored = plan.settings.restoreAiServices(aiServicesSnapshot(before))
        assertFalse(restored.backupReminderConfig.enabled)
        assertEquals(7, restored.backupReminderConfig.intervalDays)
    }

    @Test
    fun drawingModelAndFastThinking() {
        val draw = ok(planAppSettingsChange(changes(
            """{"type":"set_default_model","role":"image","model":{"provider_id":"${provider.id}","model_id":"painter"}}""",
            """{"type":"set_fast_thinking","level":"quick"}"""), settings()))
        assertEquals(painter.id, draw.settings.imageToolModelId)
        assertEquals(ReasoningLevel.OFF, draw.settings.fastModelReasoningLevel)
        assertTrue("cannot draw" in invalid(planAppSettingsChange(changes(
            """{"type":"set_default_model","role":"image","model":{"provider_id":"${provider.id}","model_id":"chat-1"}}"""), settings())))
        val cleared = ok(planAppSettingsChange(changes("""{"type":"set_default_model","role":"image","model":"none"}"""),
            settings().copy(imageToolModelId = painter.id)))
        assertNull(cleared.settings.imageToolModelId)
    }

    @Test
    fun theNewSettingsComeBackOnRestoreAndOldSnapshotsLeaveThemAlone() {
        val before = settings()
        val snapshot = aiServicesSnapshot(before, Appearance(COLOR_MODE_LIGHT, false))
        val changed = ok(planAppSettingsChange(changes(
            """{"type":"set_display","font_size":"larger","code_wrap":true}""",
            """{"type":"set_theme","theme":"ocean"}""",
            """{"type":"set_owner_name","name":"阿陈"}"""), before)).settings
        val restored = changed.restoreAiServices(snapshot)
        assertEquals(before.displaySetting, restored.displaySetting)
        assertEquals("sakura", restored.themeId)
        assertEquals(Appearance(COLOR_MODE_LIGHT, false), appearanceIn(snapshot))
        assertNull(appearanceIn(aiServicesSnapshot(before)))
        // A snapshot without the display keys (written before they were kept) changes none of them.
        val old = Json.parseToJsonElement(snapshot).let { it as kotlinx.serialization.json.JsonObject }
            .filterKeys { !it.startsWith("display:") && !it.startsWith("owner:") && !it.startsWith("theme:") }
        assertEquals(changed.displaySetting, changed.restoreAiServices(kotlinx.serialization.json.JsonObject(old).toString()).displaySetting)
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

    private class FakeAppearance : AppearanceStore {
        var value = Appearance()
        val events = MutableSharedFlow<Appearance>()
        override fun current() = value
        override fun write(appearance: Appearance) { value = appearance }
        override fun changes(): Flow<Appearance> = events
    }

    @Test
    fun aChangeRightAfterAnUpgradeCanStillBeUndone() = runBlocking {
        val revisions = RevisionRepository(FakeStore())
        val appearance = FakeAppearance()
        val recorder = AiServicesRevisionRecorder(revisions, appearance)
        val state = MutableStateFlow(settings())
        val store = mockk<SettingsStore>()
        every { store.settingsFlow } returns state
        coEvery { store.update(any<(Settings) -> Settings>()) } coAnswers {
            val old = state.value
            val next = firstArg<(Settings) -> Settings>()(old)
            state.value = next
            recorder.onSettingsChanged(old, next)
        }
        // The last version was written by a release whose snapshots did not keep display, theme or light / dark.
        val older = (Json.parseToJsonElement(aiServicesSnapshot(state.value, appearance.value)) as kotlinx.serialization.json.JsonObject)
            .filterKeys { !it.startsWith("display:") && !it.startsWith("theme:") && !it.startsWith("appearance:") && !it.startsWith("owner:") &&
                !it.startsWith("notify:") && !it.startsWith("fast:") }
        revisions.record(RevisionSubject.AI_SERVICES, "app", before = null, after = kotlinx.serialization.json.JsonObject(older).toString())
        val service = RevisionService(revisions, store, mockk<MemoryRepository>(relaxed = true), appearance)
        val tool = buildAppSettingsTools(store, appearancePrefs = appearance, recorder = recorder).first { it.name == CHANGE_APP_SETTINGS_TOOL }
        withContext(CurrentToolCall("c1", approved = true) + RevisionOrigin(RevisionAuthor.AGENT)) {
            tool.execute(changes("""{"type":"set_theme","color_mode":"dark"}""", """{"type":"set_display","font_size":"large"}"""))
        }
        assertEquals(ToolCallChangeState.Undoable, service.observeToolCallChange("c1").first()!!.state)
        assertEquals(RestoreResult.Restored, service.undoToolCall("c1"))
        assertEquals(COLOR_MODE_SYSTEM, appearance.value.colorMode)
        assertEquals(1.0f, state.value.displaySetting.fontSizeRatio)
    }

    @Test
    fun theToolSwitchesToDarkAndUndoBringsLightBackWithTheRest() = runBlocking {
        val revisions = RevisionRepository(FakeStore())
        val appearance = FakeAppearance()
        val recorder = AiServicesRevisionRecorder(revisions, appearance)
        val state = MutableStateFlow(settings())
        val store = mockk<SettingsStore>()
        every { store.settingsFlow } returns state
        coEvery { store.update(any<(Settings) -> Settings>()) } coAnswers {
            val old = state.value
            val next = firstArg<(Settings) -> Settings>()(old)
            state.value = next
            recorder.onSettingsChanged(old, next)
        }
        val service = RevisionService(revisions, store, mockk<MemoryRepository>(relaxed = true), appearance)
        val tool = buildAppSettingsTools(store, appearancePrefs = appearance, recorder = recorder).first { it.name == CHANGE_APP_SETTINGS_TOOL }
        val input = changes("""{"type":"set_theme","color_mode":"dark"}""", """{"type":"set_display","font_size":"large"}""")
        val output = withContext(CurrentToolCall("c1", approved = true) + RevisionOrigin(RevisionAuthor.AGENT)) { tool.execute(input) }
        assertTrue((output.single() as UIMessagePart.Text).text.contains("\"success\":true"))
        assertEquals(COLOR_MODE_DARK, appearance.value.colorMode)
        assertEquals(1.15f, state.value.displaySetting.fontSizeRatio)
        assertEquals(ToolCallChangeState.Undoable, service.observeToolCallChange("c1").first()!!.state)

        assertEquals(RestoreResult.Restored, service.undoToolCall("c1"))
        assertEquals(COLOR_MODE_SYSTEM, appearance.value.colorMode)
        assertEquals(1.0f, state.value.displaySetting.fontSizeRatio)
        assertEquals(ToolCallChangeState.Undone, service.observeToolCallChange("c1").first()!!.state)
        // The partner is told on its next turn; a call that changed nothing is never reported.
        assertEquals(setOf("c1"), service.undoneToolCalls(listOf("c1", "read-only")))

        assertEquals(RestoreResult.Restored, service.redoToolCall("c1"))
        assertEquals(COLOR_MODE_DARK, appearance.value.colorMode)
        assertEquals(emptySet<String>(), service.undoneToolCalls(listOf("c1")))

        // Switching back by hand on the appearance page is a later change: undo of the call no longer applies.
        appearance.value = Appearance()
        recorder.record(null, aiServicesSnapshot(state.value, appearance.value))
        assertEquals(ToolCallChangeState.Superseded, service.observeToolCallChange("c1").first()!!.state)

        // Without the owner's approval nothing is written, even for a valid call.
        val unapproved = withContext(CurrentToolCall("c2", approved = false)) { tool.execute(input) }
        assertTrue((unapproved.single() as UIMessagePart.Text).text.contains("\"success\":false"))
        assertEquals(COLOR_MODE_SYSTEM, appearance.value.colorMode)
    }
}
