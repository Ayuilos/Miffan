package me.ayuilos.miffan.data.ai

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ayuilos.miffan.data.datastore.Settings
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TranslationHandlerTest {
    @Test fun promptNeverShowsAsTheTranslationBeforeTheReplyStarts() = runTest {
        val model = Model(modelId = "test")
        val setting = ProviderSetting.OpenAI(models = listOf(model))
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        val manager = mockk<ProviderManager>()
        every { manager.getProviderByType(setting) } returns provider
        // Providers announce the connection before any reply text arrives.
        coEvery { provider.streamText(setting, any(), any()) } returns flowOf(
            StreamChunk.Connected,
            StreamChunk.TextStart("t"),
            StreamChunk.TextDelta("t", "你好"),
            StreamChunk.TextEnd("t"),
        )
        val settings = Settings(providers = listOf(setting), translateModeId = model.id)
        val updates = mutableListOf<String>()

        val emitted = TranslationHandler(manager)
            .translateText(settings, "Hello", Locale.SIMPLIFIED_CHINESE, updates::add).toList()

        assertEquals(listOf("你好"), updates.distinct())
        assertEquals(listOf("你好"), emitted.distinct())
    }
}
