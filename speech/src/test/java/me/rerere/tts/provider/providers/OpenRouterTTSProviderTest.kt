package me.rerere.tts.provider.providers

import me.rerere.tts.model.AudioFormat
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterTTSProviderTest {
    @Test
    fun request_uses_openrouter_tts_fields() {
        val body = buildOpenRouterTTSRequestBody(
            setting = TTSProviderSetting.OpenRouter(
                model = "elevenlabs/eleven-turbo-v2",
                voice = "Rachel",
                responseFormat = "PCM",
                speed = 1.25f,
            ),
            text = "Hello",
        )

        assertEquals("elevenlabs/eleven-turbo-v2", body["model"]?.toString()?.trim('"'))
        assertEquals("Hello", body["input"]?.toString()?.trim('"'))
        assertEquals("Rachel", body["voice"]?.toString()?.trim('"'))
        assertEquals("pcm", body["response_format"]?.toString()?.trim('"'))
        assertEquals("1.25", body["speed"]?.toString())
    }

    @Test
    fun blank_voice_is_omitted_for_models_with_a_default() {
        val body = buildOpenRouterTTSRequestBody(
            setting = TTSProviderSetting.OpenRouter(voice = ""),
            text = "Hello",
        )

        assertFalse(body.containsKey("voice"))
    }

    @Test
    fun response_format_maps_to_player_format_and_mime_type() {
        val mp3 = openRouterAudioFormat("mp3")
        val pcm = openRouterAudioFormat("PCM")

        assertEquals(AudioFormat.MP3, mp3.audioFormat)
        assertEquals("mp3", mp3.apiValue)
        assertEquals("audio/mpeg", mp3.mimeType)
        assertEquals(AudioFormat.PCM, pcm.audioFormat)
        assertEquals("pcm", pcm.apiValue)
        assertEquals("audio/pcm", pcm.mimeType)
        assertTrue(openRouterAudioFormat("unknown").audioFormat == AudioFormat.MP3)
    }
}
