package me.ayuilos.miffan.data.thread

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.ai.BackgroundModelHealth
import me.ayuilos.miffan.data.ai.BackgroundTask
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.InterfaceMode
import me.ayuilos.miffan.data.model.InterfaceModeState
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException

class ThreadModelsTest {
    private val chat = Model(modelId = "chat")
    private val partnerChat = Model(modelId = "partner-chat")
    private val fast = Model(modelId = "fast")
    private val compression = Model(modelId = "compression")
    private val assistant = Assistant(chatModelId = partnerChat.id)
    private val provider = ProviderSetting.OpenAI(models = listOf(chat, partnerChat, fast, compression))
    private val settings = MutableStateFlow(Settings(
        providers = listOf(provider),
        assistants = listOf(assistant),
        interfaceMode = InterfaceModeState(mode = InterfaceMode.IM),
        chatModelId = chat.id,
        fastModelId = fast.id,
        compressModelId = compression.id,
        fastModelReasoningLevel = ReasoningLevel.HIGH,
    ))
    private val store = mockk<SettingsStore>()
    private val manager = mockk<ProviderManager>()
    private val handler = mockk<Provider<ProviderSetting>>()
    private val health = BackgroundModelHealth()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val models = ThreadModels(store, manager, scope, health)
    private val answer = TextGenerationResult("answer", "model", UIMessage.assistant(" summary "))

    @Before
    fun setup() {
        every { store.settingsFlow } returns settings
        every { manager.getProviderByType(any<ProviderSetting>()) } returns handler
        coEvery { handler.generateText(any(), any(), any()) } returns answer
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun cleanup() {
        scope.cancel()
        unmockkStatic(Log::class)
    }

    @Test
    fun summaryUsesSegmentAssistantChatAndLowReasoningWhenConfiguredModelsAreMissing() = runBlocking {
        settings.value = settings.value.copy(providers = listOf(provider.copy(models = listOf(chat, partnerChat))))
        health.reportFailure(BackgroundTask.SUMMARY, partnerChat, IllegalStateException("old failure"))
        assertEquals("summary", models.compress("summarize", 5_000, assistant.id))
        coVerify(exactly = 1) {
            handler.generateText(any(), any(), match { it.model == partnerChat && it.reasoningLevel == ReasoningLevel.LOW })
        }
        assertNull(health.lastFailure.value)
        assertNull(models.fast("route", 5_000, ReasoningLevel.OFF))
        coVerify(exactly = 1) { handler.generateText(any(), any(), any()) }
    }

    @Test
    fun configuredModelsKeepExistingReasoningLevels() = runBlocking {
        assertEquals("summary", models.compress("summarize", 5_000, assistant.id))
        assertEquals("summary", models.fast("route", 5_000, ReasoningLevel.OFF))
        assertEquals("summary", models.fast("fast", 5_000))
        coVerify(exactly = 1) {
            handler.generateText(any(), any(), match { it.model == compression && it.reasoningLevel == ReasoningLevel.AUTO })
        }
        coVerify(exactly = 1) {
            handler.generateText(any(), any(), match { it.model == fast && it.reasoningLevel == ReasoningLevel.OFF })
        }
        coVerify(exactly = 1) {
            handler.generateText(any(), any(), match { it.model == fast && it.reasoningLevel == ReasoningLevel.HIGH })
        }
    }

    @Test
    fun providerFailureIsReportedButNetworkTimeoutAndDeadlineAreIgnored() = runBlocking {
        coEvery { handler.generateText(any(), any(), any()) } throws IllegalArgumentException("rejected")
        assertNull(models.compress("summarize", 5_000, assistant.id))
        val failure = requireNotNull(health.lastFailure.value)
        assertEquals(BackgroundTask.SUMMARY, failure.task)
        assertEquals(compression.modelId, failure.modelName)
        coEvery { handler.generateText(any(), any(), any()) } throws SocketTimeoutException("timeout")
        assertNull(models.fast("route", 5_000))
        assertEquals(failure, health.lastFailure.value)
        assertNull(models.fast("route", 0))
        assertEquals(failure, health.lastFailure.value)
    }

    @Test
    fun cancellationIsRethrownWithoutReportingFailure() = runBlocking {
        coEvery { handler.generateText(any(), any(), any()) } throws CancellationException("cancelled")
        try {
            models.compress("summarize", 5_000, assistant.id)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertNull(health.lastFailure.value)
        }
    }
}
