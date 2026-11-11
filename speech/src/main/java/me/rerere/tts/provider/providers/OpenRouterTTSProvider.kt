package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "OpenRouterTTSProvider"
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

class OpenRouterTTSProvider : TTSProvider<TTSProviderSetting.OpenRouter> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.OpenRouter,
        request: TTSRequest,
    ): Flow<AudioChunk> = flow {
        val setting = providerSetting.withCurrentModel()
        val requestBody = buildOpenRouterTTSRequestBody(setting, request.text)
        val responseFormat = openRouterAudioFormat(openRouterResponseFormat(setting))

        Log.i(
            TAG,
            "generateSpeech: model=${setting.model} voice=${setting.voice} " +
                "format=${setting.responseFormat}",
        )

        val httpRequest = Request.Builder()
            .url("${setting.baseUrl.trimEnd('/')}/audio/speech")
            .addHeader("Authorization", "Bearer ${setting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", responseFormat.mimeType)
            .addHeader("X-OpenRouter-Title", "Miffan")
            .addHeader("HTTP-Referer", "https://miffan.ayuilos.me")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = runCatching { response.body.string() }.getOrNull().orEmpty()
                throw Exception(
                    "OpenRouter TTS request failed: HTTP ${response.code} ${response.message}. " +
                        "body=$errorBody",
                )
            }

            val audioData = response.body.bytes()
            if (audioData.isEmpty()) {
                throw Exception("OpenRouter TTS returned 0 bytes")
            }

            emit(
                AudioChunk(
                    data = audioData,
                    format = responseFormat.audioFormat,
                    // OpenRouter documents its pcm as 24 kHz 16-bit mono, the rate the player also assumes.
                    sampleRate = if (responseFormat.audioFormat == AudioFormat.PCM) 24_000 else null,
                    isLast = true,
                    metadata = mapOf(
                        "provider" to "openrouter",
                        "model" to setting.model,
                        "voice" to setting.voice,
                        "responseFormat" to setting.responseFormat,
                    ),
                ),
            )
        }
    }
}

internal fun buildOpenRouterTTSRequestBody(
    setting: TTSProviderSetting.OpenRouter,
    text: String,
): JsonObject = buildJsonObject {
    put("model", setting.model)
    put("input", text)
    if (setting.voice.isNotBlank()) {
        put("voice", setting.voice)
    }
    put("response_format", openRouterAudioFormat(openRouterResponseFormat(setting)).apiValue)
    // Models without speed control reject any value but the default, so leave it out unless it was changed.
    if (setting.speed != 1.0f) {
        put("speed", setting.speed)
    }
}

/**
 * The format [setting]'s model can return: Gemini and HeyGen only return pcm and MiniMax only mp3, and each answers the
 * other with a 400; other models take what was chosen.
 */
internal fun openRouterResponseFormat(setting: TTSProviderSetting.OpenRouter): String {
    val model = setting.model.trim().lowercase()
    return when {
        model.startsWith("google/") || model.startsWith("heygen/") -> "pcm"
        model.startsWith("minimax/") -> "mp3"
        else -> setting.responseFormat
    }
}

internal data class OpenRouterAudioFormat(
    val apiValue: String,
    val audioFormat: AudioFormat,
    val mimeType: String,
)

internal fun openRouterAudioFormat(responseFormat: String): OpenRouterAudioFormat =
    when (responseFormat.lowercase()) {
        "pcm" -> OpenRouterAudioFormat("pcm", AudioFormat.PCM, "audio/pcm")
        else -> OpenRouterAudioFormat("mp3", AudioFormat.MP3, "audio/mpeg")
    }
