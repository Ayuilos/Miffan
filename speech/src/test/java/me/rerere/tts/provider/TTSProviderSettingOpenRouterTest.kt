package me.rerere.tts.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSProviderSettingOpenRouterTest {
    private val json = Json {
        classDiscriminator = "type"
    }

    @Test
    fun openrouter_defaults_are_expected() {
        val setting = TTSProviderSetting.OpenRouter()

        assertEquals("OpenRouter TTS", setting.name)
        assertEquals("https://openrouter.ai/api/v1", setting.baseUrl)
        assertEquals("google/gemini-3.8-flash-tts", setting.model)
        assertEquals("Kore", setting.voice)
        assertEquals("pcm", setting.responseFormat)
        assertEquals(1.0f, setting.speed)
        assertTrue(TTSProviderSetting.Types.contains(TTSProviderSetting.OpenRouter::class))
    }

    @Test
    fun a_retired_openai_model_moves_to_the_current_default() {
        val old = TTSProviderSetting.OpenRouter(
            apiKey = "sk-or-v1-test",
            model = "openai/gpt-4o-mini-tts-2025-12-15",
            voice = "alloy",
            responseFormat = "mp3",
        )

        val current = old.withCurrentModel()

        assertEquals(OPENROUTER_TTS_DEFAULT_MODEL, current.model)
        assertEquals(OPENROUTER_TTS_DEFAULT_VOICE, current.voice)
        assertEquals("pcm", current.responseFormat)
        assertEquals("sk-or-v1-test", current.apiKey)
        val other = TTSProviderSetting.OpenRouter(model = "elevenlabs/eleven-v3", voice = "george")
        assertEquals(other, other.withCurrentModel())
    }

    @Test
    fun openrouter_setting_round_trips_through_serialization() {
        val original = TTSProviderSetting.OpenRouter(
            name = "My OpenRouter",
            apiKey = "sk-or-v1-test",
            model = "elevenlabs/eleven-turbo-v2",
            voice = "Rachel",
            responseFormat = "pcm",
            speed = 1.25f,
        )

        val encoded = json.encodeToString(TTSProviderSetting.serializer(), original)
        val decoded = json.decodeFromString(TTSProviderSetting.serializer(), encoded)
            as TTSProviderSetting.OpenRouter

        assertEquals(original, decoded)
    }
}
