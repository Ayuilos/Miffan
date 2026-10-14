package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URI
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal fun ProviderSetting.OpenAI.isOpenRouter(): Boolean = baseUrl.toHttpUrlOrNull()?.host == "openrouter.ai"

/** Also covers image-only models saved as CHAT before the dedicated Images API was supported. */
fun Model.usesOpenRouterImagesApi(provider: ProviderSetting): Boolean =
    provider is ProviderSetting.OpenAI && provider.isOpenRouter() &&
        Modality.IMAGE in outputModalities && Modality.TEXT !in outputModalities

internal fun openRouterImagesRequestBuilder(
    provider: ProviderSetting.OpenAI,
    model: Model,
    prompt: String,
    n: Int,
    size: String,
    images: List<String> = emptyList(),
    customHeaders: List<CustomHeader> = emptyList(),
    customBody: List<CustomBody> = emptyList(),
): Request.Builder {
    val body = buildJsonObject {
        put("model", model.modelId)
        put("prompt", prompt)
        put("n", n)
        if (size.isNotBlank()) put("size", size)
        if (images.isNotEmpty()) putJsonArray("input_references") {
            images.forEach { source ->
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", openRouterImageReference(source)) })
                })
            }
        }
    }.mergeCustomBody(customBody)
    return Request.Builder()
        .url("${provider.baseUrl.trimEnd('/')}/images")
        .headers(customHeaders.toHeaders())
        .header("Content-Type", "application/json")
        .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
}

@OptIn(ExperimentalEncodingApi::class)
private fun openRouterImageReference(source: String): String {
    if (source.startsWith("https://") || source.startsWith("http://") || source.startsWith("data:image/")) return source
    val file = if (source.startsWith("file:")) File(URI(source)) else File(source)
    require(file.isFile) { "Reference image file does not exist" }
    val mimeType = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "avif" -> "image/avif"
        "heic", "heif" -> "image/heic"
        "svg" -> "image/svg+xml"
        else -> "image/png"
    }
    return "data:$mimeType;base64,${Base64.encode(file.readBytes())}"
}

/** OpenRouter uses media_type, Magpie uses mime_type, and OpenAI uses output_format. */
internal fun JsonObject.base64ImageOrNull(defaultFormat: String = "png"): ImageGenerationItem? {
    val data = get("b64_json")?.jsonPrimitive?.contentOrNull ?: return null
    val format = get("output_format")?.jsonPrimitive?.contentOrNull ?: defaultFormat
    val mediaType = listOf("media_type", "mime_type").firstNotNullOfOrNull { key ->
        get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("image/") }
    } ?: when (format.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }
    return ImageGenerationItem(data = data, mimeType = mediaType)
}
