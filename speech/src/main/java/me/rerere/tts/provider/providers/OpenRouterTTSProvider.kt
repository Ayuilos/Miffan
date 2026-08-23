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
        val requestBody = buildOpenRouterTTSRequestBody(providerSetting, request.text)
        val responseFormat = openRouterAudioFormat(providerSetting.responseFormat)

        Log.i(
            TAG,
            "generateSpeech: model=${providerSetting.model} voice=${providerSetting.voice} " +
                "format=${providerSetting.responseFormat}",
        )

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl.trimEnd('/')}/audio/speech")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", responseFormat.mimeType)
            .addHeader("X-Title", "Miffan")
            .addHeader("HTTP-Referer", "https://rikka-ai.com")
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
                    isLast = true,
                    metadata = mapOf(
                        "provider" to "openrouter",
                        "model" to providerSetting.model,
                        "voice" to providerSetting.voice,
                        "responseFormat" to providerSetting.responseFormat,
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
    put("response_format", openRouterAudioFormat(setting.responseFormat).apiValue)
    put("speed", setting.speed)
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
