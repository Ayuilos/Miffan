package me.ayuilos.miffan.data.ai

import android.util.Log
import io.mockk.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.revision.CurrentToolCall
import me.ayuilos.miffan.data.revision.RevisionAuthor
import me.ayuilos.miffan.data.revision.RevisionOrigin
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationToolCallContextTest {
    @Test fun executionInstallsEachCallsIdAndNestedToolOriginsKeepIt() = runTest {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        try {
            val assistant = Assistant(streamOutput = false)
            val model = Model(modelId = "test", abilities = listOf(ModelAbility.TOOL))
            val setting = ProviderSetting.OpenAI(models = listOf(model))
            val provider = mockk<Provider<ProviderSetting.OpenAI>>()
            val manager = mockk<ProviderManager>()
            every { manager.getProviderByType(setting) } returns provider
            coEvery { provider.generateText(setting, any(), any()) } returns TextGenerationResult(
                "test", "test", UIMessage.assistant("").copy(parts = listOf(
                    UIMessagePart.Tool("first", "change", "{}"), UIMessagePart.Tool("second", "change", "{}"),
                )),
            )
            val observed = mutableListOf<String?>()
            val tool = Tool("change", "change", execute = {
                withContext(RevisionOrigin(RevisionAuthor.AGENT)) {
                    observed += currentCoroutineContext()[CurrentToolCall]?.id
                }
                listOf(UIMessagePart.Text("done"))
            })
            GenerationHandler(mockk(), manager, JsonInstant, mockk(), mockk(), mockk()).generateText(
                Settings(providers = listOf(setting)), model, listOf(UIMessage.user("change")),
                assistant = assistant, tools = listOf(tool), maxSteps = 1, access = AgentAccess.forAssistant(assistant, true),
            ).toList()
            assertEquals(listOf("first", "second"), observed)
            assertNull(currentCoroutineContext()[CurrentToolCall])
        } finally { unmockkStatic(Log::class) }
    }
}
