package me.ayuilos.miffan.data.ai

import android.util.Log
import io.mockk.*
import org.junit.Before
import org.junit.After
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ayuilos.miffan.data.datastore.Settings
import kotlinx.coroutines.currentCoroutineContext
import me.ayuilos.miffan.data.repository.MemoryRepository
import me.ayuilos.miffan.data.revision.RevisionOrigin
import me.ayuilos.miffan.data.model.MessageRef
import kotlin.uuid.Uuid
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test

class GenerationHandlerOriginTest {
    @Before fun mockAndroidLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
    }
    @After fun restoreAndroidLog() = unmockkStatic(Log::class)

    @Test fun nonOwnerTurnDoesNotOfferMemoryToolsToProvider() = runTest {
        val model = Model(modelId = "test")
        val setting = ProviderSetting.OpenAI(models = listOf(model))
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        val manager = mockk<ProviderManager>()
        every { manager.getProviderByType(setting) } returns provider
        val params = slot<TextGenerationParams>()
        coEvery { provider.generateText(setting, any(), capture(params)) } returns
            TextGenerationResult("test", "test", UIMessage.assistant("done"))
        val memoryRepo = mockk<MemoryRepository>()
        var actualTrigger: MessageRef? = null
        coEvery { memoryRepo.deleteMemory(7, any()) } coAnswers {
            actualTrigger = currentCoroutineContext()[RevisionOrigin]?.trigger
            null
        }
        val handler = GenerationHandler(mockk(), manager, JsonInstant, memoryRepo, mockk(), mockk())
        val settings = Settings(providers = listOf(setting))
        val assistant = Assistant(enableMemory = true, streamOutput = false)
        handler.generateText(settings, model, listOf(UIMessage.user("remember event")),
            assistant = assistant, access = AgentAccess.forAssistant(assistant, false)).toList()
        assertFalse(params.captured.tools.any { it.name == "memory_tool" })
        handler.generateText(settings, model, listOf(UIMessage.user("remember owner")),
            assistant = assistant, access = AgentAccess.forAssistant(assistant, true)).toList()
        assertTrue(params.captured.tools.any { it.name == "memory_tool" })
        params.captured.tools.single { it.name == "memory_tool" }
            .execute(JsonInstant.parseToJsonElement("""{"action":"delete","id":7}"""))
        assertNull(actualTrigger) // No trigger may be inferred from the USER API message.
        val passedTrigger = MessageRef(Uuid.random(), Uuid.random())
        handler.generateText(settings, model, listOf(UIMessage.user("unrelated API message")),
            assistant = assistant, access = AgentAccess.forAssistant(assistant, true), memoryTrigger = passedTrigger).toList()
        params.captured.tools.single { it.name == "memory_tool" }
            .execute(JsonInstant.parseToJsonElement("""{"action":"delete","id":7}"""))
        assertEquals(passedTrigger, actualTrigger)
    }
}
