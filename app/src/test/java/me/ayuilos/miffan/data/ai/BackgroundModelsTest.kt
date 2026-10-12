package me.ayuilos.miffan.data.ai

import me.ayuilos.miffan.data.ai.openrouter.withOpenRouterConnection
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.InterfaceMode
import me.ayuilos.miffan.data.model.InterfaceModeState
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class BackgroundModelsTest {
    private val chat = Model(modelId = "chat")
    private val partnerChat = Model(modelId = "partner-chat")
    private val fast = Model(modelId = "fast")
    private val compress = Model(modelId = "compress")
    private val title = Model(modelId = "title")
    private val missing = Uuid.random()
    private val assistant = Assistant(chatModelId = partnerChat.id)

    private fun settings(mode: InterfaceMode, models: List<Model>) = Settings(
        interfaceMode = InterfaceModeState(mode = mode),
        providers = listOf(ProviderSetting.OpenAI(models = models)),
        chatModelId = chat.id,
        fastModelId = fast.id,
        compressModelId = compress.id,
        titleModelId = title.id,
    )

    @Test
    fun configuredModelMatrixWorksInBothModes() {
        InterfaceMode.entries.forEach { mode ->
            val settings = settings(mode, listOf(chat, partnerChat, fast, compress, title))
            BackgroundTask.entries.forEach { task ->
                val expected = when (task) {
                    BackgroundTask.SUMMARY -> compress
                    BackgroundTask.TITLE -> title
                    else -> fast
                }
                assertEquals(BackgroundModelChoice(expected, BackgroundModelSource.FAST), settings.backgroundModelFor(task, assistant))
            }
            assertEquals(BackgroundModelSource.FAST, settings.backgroundModelSource(assistant))
            val fastOnly = settings.copy(compressModelId = missing, titleModelId = null)
            BackgroundTask.entries.forEach { task ->
                assertEquals(fast, fastOnly.backgroundModelFor(task, assistant)?.model)
            }
        }
    }

    @Test
    fun missingAndDeletedFastModelsOnlyAllowAsynchronousFallbackInEasyChat() {
        InterfaceMode.entries.forEach { mode ->
            val deleted = settings(mode, listOf(chat, partnerChat))
            listOf(deleted, deleted.copy(fastModelId = missing)).forEach { settings ->
                BackgroundTask.entries.forEach { task ->
                    val expected = if (mode == InterfaceMode.IM && task.allowsChatFallback) {
                        BackgroundModelChoice(partnerChat, BackgroundModelSource.CHAT_FALLBACK)
                    } else null
                    assertEquals(expected, settings.backgroundModelFor(task, assistant))
                }
                assertEquals(
                    if (mode == InterfaceMode.IM) BackgroundModelSource.CHAT_FALLBACK else BackgroundModelSource.NONE,
                    settings.backgroundModelSource(assistant),
                )
            }
        }
    }

    @Test
    fun assistantChatOverridesGlobalAndNullAssistantUsesGlobal() {
        val settings = settings(InterfaceMode.IM, listOf(chat, partnerChat))
        listOf(BackgroundTask.MEMORY, BackgroundTask.SUMMARY).forEach { task ->
            assertEquals(partnerChat, settings.backgroundModelFor(task, assistant)?.model)
            assertEquals(chat, settings.backgroundModelFor(task, null)?.model)
            assertEquals(chat, settings.backgroundModelFor(task, assistant.copy(chatModelId = null))?.model)
            assertNull(settings.backgroundModelFor(task, assistant.copy(chatModelId = missing)))
        }
    }

    @Test
    fun unusableConfiguredModelsAreSkippedBeforeFastOrChatFallback() {
        val image = compress.copy(type = ModelType.IMAGE)
        val embedding = title.copy(type = ModelType.EMBEDDING)
        val settings = settings(InterfaceMode.IM, listOf(chat, fast, image, embedding))
        assertEquals(fast, settings.backgroundModelFor(BackgroundTask.SUMMARY, null)?.model)
        assertEquals(fast, settings.backgroundModelFor(BackgroundTask.TITLE, null)?.model)
        val noFast = settings.copy(fastModelId = missing)
        assertEquals(chat, noFast.backgroundModelFor(BackgroundTask.SUMMARY, null)?.model)
        assertNull(noFast.backgroundModelFor(BackgroundTask.TITLE, null))
        val badFast = settings.copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(chat, fast.copy(type = ModelType.IMAGE)))))
        assertEquals(chat, badFast.backgroundModelFor(BackgroundTask.MEMORY, null)?.model)
        assertNull(badFast.backgroundModelFor(BackgroundTask.ROUTING, null))
    }

    @Test
    fun missingOrNonChatFallbackIsUnusable() {
        listOf(emptyList(), listOf(chat.copy(type = ModelType.IMAGE)), listOf(chat.copy(type = ModelType.EMBEDDING))).forEach { models ->
            val settings = settings(InterfaceMode.IM, models)
            BackgroundTask.entries.forEach { task -> assertNull(settings.backgroundModelFor(task, null)) }
            assertEquals(BackgroundModelSource.NONE, settings.backgroundModelSource(null))
        }
    }

    @Test
    fun disabledProvidersResolveExactlyAsTheyDoForMainChat() {
        val settings = settings(InterfaceMode.IM, listOf(chat, fast, compress, title)).let {
            it.copy(providers = it.providers.map { provider -> provider.copyProvider(enabled = false) })
        }
        BackgroundTask.entries.forEach { task -> assertNotNull(settings.backgroundModelFor(task, null)) }
        val fallback = settings.copy(fastModelId = missing, compressModelId = missing)
        assertEquals(chat, fallback.backgroundModelFor(BackgroundTask.MEMORY, null)?.model)
        assertEquals(chat, fallback.backgroundModelFor(BackgroundTask.SUMMARY, null)?.model)
    }

    @Test
    fun usableFastModelIsKeptWithoutCopyingSettings() {
        val settings = settings(InterfaceMode.IM, listOf(chat, fast))
        val provider = ProviderSetting.OpenAI(models = listOf(chat, Model(modelId = "gpt-nano")))
        assertSame(settings, settings.withSuggestedFastModel(provider, chat))
    }

    @Test
    fun missingDeletedAndNonChatFastModelsCanBeReplacedByAnUnconnectedDisabledProvider() {
        val suggestion = Model(modelId = "gpt-mini")
        val provider = ProviderSetting.OpenAI(enabled = false, models = listOf(chat, suggestion))
        val deleted = settings(InterfaceMode.IM, listOf(chat))
        val wrongType = settings(InterfaceMode.IM, listOf(chat, fast.copy(type = ModelType.IMAGE)))
        listOf(deleted, deleted.copy(fastModelId = missing), wrongType).forEach { settings ->
            assertEquals(suggestion.id, settings.withSuggestedFastModel(provider, chat).fastModelId)
        }
    }

    @Test
    fun absentSuggestionLeavesSettingsUnchangedEvenWithChatFallback() {
        val settings = settings(InterfaceMode.IM, listOf(chat))
        assertSame(settings, settings.withSuggestedFastModel(ProviderSetting.OpenAI(models = listOf(chat)), chat))
    }

    @Test
    fun openRouterFreeOnlyConnectionDoesNotSpendQuotaOnRouting() {
        val settings = settings(InterfaceMode.IM, emptyList())
        val connected = settings.withOpenRouterConnection("test-key")
        assertEquals(settings.fastModelId, connected.fastModelId)
        assertNull(connected.backgroundModelFor(BackgroundTask.ROUTING, null))
        assertEquals(BackgroundModelSource.CHAT_FALLBACK, connected.backgroundModelSource(null))
    }

    @Test
    fun openRouterConnectionSuggestsAnExistingLightModel() {
        val mini = Model(modelId = "openai/gpt-4o-mini")
        val connected = settings(InterfaceMode.IM, emptyList()).withOpenRouterConnection("test-key")
        val withMini = connected.copy(providers = connected.providers.map { it.addModel(mini) })
            .withOpenRouterConnection("replacement-key")
        assertEquals(mini.id, withMini.fastModelId)
    }
}
