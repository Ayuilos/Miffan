package me.ayuilos.miffan.data.ai.tools

import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.findModelById
import me.ayuilos.miffan.data.datastore.findProvider
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.data.files.saveUploadFromBytes
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.usesOpenRouterImagesApi
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.security.MessageDigest
import java.io.File
import java.net.URI
import java.util.Base64
import kotlin.uuid.Uuid

const val GENERATE_IMAGE_TOOL = "generate_image"
const val IMAGE_SETUP_APPROVAL_REASON = "image_setup"
private val RESERVED_IMAGE_OPTIONS = setOf(
    "model", "prompt", "messages", "input", "input_references", "image", "images", "n", "stream", "tools",
)

fun Model.canOutputImage(): Boolean = Modality.IMAGE in outputModalities ||
    BuiltInTools.ImageGeneration in tools || type == ModelType.IMAGE

private fun Settings.subscriptionImageProvider(model: Model): ProviderSetting.OpenAI? =
    (model.findProvider(providers) as? ProviderSetting.OpenAI)?.takeIf {
        model.type == ModelType.CHAT && it.enabled && it.authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION
    }

fun Settings.canDrawWith(model: Model): Boolean = model.canOutputImage() || subscriptionImageProvider(model) != null

fun Settings.imageToolModelOrNull(): Model? = findModelById(imageToolModelId)?.takeIf { canDrawWith(it) }

data class SubscriptionImageOption(val providerId: Uuid, val providerName: String, val model: Model)

/** Reuse a light chat model to drive Responses image generation; no duplicate model configurations. */
fun Settings.subscriptionImageOptions(): List<SubscriptionImageOption> = providers
    .filterIsInstance<ProviderSetting.OpenAI>()
    .filter { it.enabled && it.authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION }
    .mapNotNull { provider ->
        val chatModels = provider.models.filter { it.type == ModelType.CHAT }
        val model = chatModels.firstOrNull { it.modelId.contains("mini", ignoreCase = true) }
            ?: chatModels.firstOrNull() ?: return@mapNotNull null
        SubscriptionImageOption(provider.id, provider.name, model)
    }

/** Stable for both attachments and tool outputs; never exposes a local path to the model. */
fun imageIdForUrl(url: String): String = "img_" + MessageDigest.getInstance("SHA-256")
    .digest(url.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }

internal fun availableImageReferences(messages: List<UIMessage>): Map<String, UIMessagePart.Image> = buildMap {
    fun collect(parts: List<UIMessagePart>) {
        parts.forEach { part ->
            when (part) {
                is UIMessagePart.Image -> put(imageIdForUrl(part.url), part)
                is UIMessagePart.Tool -> collect(part.output)
                else -> Unit
            }
        }
    }
    messages.forEach { collect(it.parts) }
}

internal fun resolveImageReferences(ids: List<String>, messages: List<UIMessage>): List<UIMessagePart.Image> {
    require(ids.size <= 4) { "reference_images accepts at most 4 image ids" }
    val available = availableImageReferences(messages)
    val unknown = ids.filter { it !in available && !isHttpImageUrl(it) }
    require(unknown.isEmpty()) {
        "Unknown image ids: ${unknown.joinToString()}. Available image ids: ${available.keys.joinToString().ifEmpty { "none" }}"
    }
    return ids.map { available[it] ?: UIMessagePart.Image(it) }
}

private fun isHttpImageUrl(value: String): Boolean =
    (value.startsWith("http://") || value.startsWith("https://")) && value.toHttpUrlOrNull() != null

fun createImageGenerationTool(
    settings: () -> Settings,
    messages: () -> List<UIMessage>,
    providerManager: ProviderManager,
    filesManager: FilesManager,
    httpClient: OkHttpClient,
): Tool = createImageGenerationTool(
    settings = settings,
    messages = messages,
    provider = { providerManager.getProviderByType(it) },
    saveImage = { bytes ->
        val mimeType = generatedImageMimeType(bytes)
        val extension = mimeType.substringAfter('/').let { if (it == "jpeg") "jpg" else it }
        val stored = filesManager.saveUploadFromBytes(bytes, "image.$extension", mimeType)
        filesManager.getFile(stored).toUri().toString()
    },
    downloadImage = { url ->
        httpClient.newCall(Request.Builder().url(url).build()).await().use { response ->
            check(response.isSuccessful) { "Image download failed (HTTP ${response.code})" }
            response.body.bytes()
        }
    },
    downloadReferenceImage = { ReferenceImageDownloader(httpClient).download(it) },
)

internal fun generatedImageMimeType(bytes: ByteArray): String = when {
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
    bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "GIF8" -> "image/gif"
    else -> "image/png"
}

/** Dependency boundary also lets JVM tests exercise real providers' message contract without Android IO. */
internal fun createImageGenerationTool(
    settings: () -> Settings,
    messages: () -> List<UIMessage>,
    provider: (ProviderSetting) -> Provider<ProviderSetting>,
    saveImage: suspend (ByteArray) -> String,
    downloadImage: suspend (String) -> ByteArray,
    downloadReferenceImage: suspend (String) -> ByteArray,
): Tool {
    val displayName = settings().imageToolModelOrNull()?.let { it.displayName.ifBlank { it.modelId } }
    return Tool(
        name = GENERATE_IMAGE_TOOL,
        needsApproval = { settings().imageToolModelOrNull() == null },
        approvalReason = if (displayName == null) IMAGE_SETUP_APPROVAL_REASON else null,
        description = if (displayName == null) {
            "Image drawing is not set up yet. If the user wants a picture, call this tool anyway: " +
                "the app pauses the call and asks the user to turn drawing on. If they do, the image is drawn " +
                "and returned to you as usual; if they decline, reply briefly without explaining settings."
        } else "Generate or edit images with $displayName. Write a detailed visual prompt. " +
            "To edit or combine images, pass image ids from this chat, or https image URLs, in reference_images (at most 4). " +
            "The app already shows the generated images to the user in your reply; do not re-embed them with Markdown. " +
            "Briefly tell the user what was made.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("prompt", buildJsonObject { put("type", "string") })
                    put("reference_images", buildJsonObject {
                        put("type", "array")
                        put("description", "Image ids from this chat, or http(s) image URLs (at most 4).")
                        put("items", buildJsonObject { put("type", "string") })
                        put("maxItems", 4)
                    })
                    put("options", buildJsonObject {
                        put("type", "object")
                        put("description", "options: extra request fields for the image API, e.g. {\"aspect_ratio\":\"16:9\"} or " +
                            "{\"resolution\":\"1K\"}. Leave it out unless the user asks for a size/shape/quality, or a previous call failed " +
                            "with an error naming the allowed values — then retry once with the values it names. " +
                            "For Responses models these fields go inside the image_generation tool object; otherwise they go in the request body. " +
                            "User custom body fields take precedence.")
                    })
                },
                required = listOf("prompt"),
            )
        },
        execute = { args ->
            withContext(Dispatchers.IO) {
                try {
                    val current = settings()
                    val model = current.imageToolModelOrNull() ?: return@withContext listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("setup_required", true)
                            put("message", "Image drawing is not set up yet. The user is shown a button to turn it on; tell them briefly and wait.")
                        }.toString()
                    ))
                    val resolvedProvider = model.findProvider(current.providers)
                        ?: error("Image generation isn't configured: the image model's provider is missing.")
                    val builtInImageTool = BuiltInTools.ImageGeneration in model.tools ||
                        current.subscriptionImageProvider(model) != null
                    val useImagesApi = model.type == ModelType.IMAGE || model.usesOpenRouterImagesApi(resolvedProvider)
                    val providerSetting = if (builtInImageTool && !useImagesApi && resolvedProvider is ProviderSetting.OpenAI) {
                        resolvedProvider.copy(useResponseApi = true)
                    } else resolvedProvider
                    val input = args.jsonObject
                    val prompt = (input["prompt"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?.takeIf { it.isNotBlank() } ?: error("prompt must be a non-empty string")
                    val options = input["options"]?.let { it as? JsonObject ?: error("options must be a JSON object") }
                        ?: JsonObject(emptyMap())
                    val reserved = options.keys.intersect(RESERVED_IMAGE_OPTIONS)
                    require(reserved.isEmpty()) { "Reserved options keys cannot be set: ${reserved.joinToString()}" }
                    // User custom bodies are fixed choices, including null removals: agent options cannot override their keys.
                    val userKeys = model.customBodies.map { it.key }.toSet()
                    val agentOptions = options.filterKeys { it !in userKeys }.map { (key, value) -> CustomBody(key, value) }
                    val requestCustomBody = agentOptions + model.customBodies
                    val ids = input["reference_images"]?.let { value ->
                        (value as? JsonArray ?: error("reference_images must be an array of image id strings")).map {
                            (it as? JsonPrimitive)?.takeIf { id -> id.isString }?.content
                                ?: error("reference_images must contain image id strings")
                        }
                    }.orEmpty()
                    val references = resolveImageReferences(ids, messages()).mapIndexed { index, reference ->
                        if (isHttpImageUrl(reference.url)) localizeImage(reference, saveImage,
                            if (isHttpImageUrl(ids[index])) downloadReferenceImage else downloadImage, fallbackToUrl = false)
                        else reference
                    }
                    require(references.isEmpty() || useImagesApi || builtInImageTool || Modality.IMAGE in model.inputModalities) {
                        "The configured image model does not accept reference images. Select a model with image input support."
                    }
                    val implementation = provider(providerSetting)
                    val response = if (useImagesApi) {
                        val flow = if (references.isEmpty()) {
                            implementation.generateImage(providerSetting, ImageGenerationParams(
                                model = model,
                                prompt = prompt,
                                customHeaders = model.customHeaders,
                                customBody = requestCustomBody,
                            ))
                        } else {
                            val paths = references.map { reference ->
                                val local = localizeImage(reference, saveImage, downloadImage, fallbackToUrl = false)
                                require(local.url.startsWith("file:")) { "Images API editing requires a local reference image" }
                                File(URI(local.url)).absolutePath
                            }
                            implementation.editImage(providerSetting, ImageEditParams(
                                model = model,
                                prompt = prompt,
                                images = paths,
                                customHeaders = model.customHeaders,
                                customBody = requestCustomBody,
                            ))
                        }
                        UIMessage(role = MessageRole.ASSISTANT, parts = flow.filter { !it.partial }.map {
                            UIMessagePart.Image("data:${it.mimeType};base64,${it.data}")
                        }.toList())
                    } else {
                        implementation.generateText(
                            providerSetting = providerSetting,
                            messages = listOf(UIMessage(
                                role = MessageRole.USER,
                                parts = listOf(UIMessagePart.Text(if (builtInImageTool)
                                    "Generate or edit the image with the image_generation tool.\n\n$prompt" else prompt)) + references,
                            )),
                            params = TextGenerationParams(
                                model = model.copy(tools = if (builtInImageTool) setOf(BuiltInTools.ImageGeneration) else emptySet()),
                                customHeaders = model.customHeaders,
                                customBody = if (builtInImageTool) model.customBodies else requestCustomBody,
                                imageGenerationOptions = if (builtInImageTool) agentOptions else emptyList(),
                            ),
                        ).message
                    }
                    val text = response.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val returned = response.parts.filterIsInstance<UIMessagePart.Image>()
                    check(returned.isNotEmpty()) {
                        "Image generation returned no images from ${model.displayName.ifBlank { model.modelId }}." +
                            text.takeIf { it.isNotBlank() }?.let { " Model reply: $it" }.orEmpty()
                    }
                    val images = returned.map { localizeImage(it, saveImage, downloadImage, fallbackToUrl = true) }
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("model", model.modelId)
                        put("images", buildJsonArray {
                            images.forEach { add(buildJsonObject { put("image_id", imageIdForUrl(it.url)) }) }
                        })
                        put("text", text)
                    }.toString())) + images
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", e.message ?: "Image generation failed")
                    }.toString()))
                }
            }
        },
    )
}

private suspend fun localizeImage(
    part: UIMessagePart.Image,
    saveImage: suspend (ByteArray) -> String,
    downloadImage: suspend (String) -> ByteArray,
    fallbackToUrl: Boolean,
): UIMessagePart.Image = when {
    part.url.startsWith("data:") -> {
        require(part.url.substringBefore(',').contains(";base64")) { "Unsupported image data encoding" }
        val encoded = part.url.substringAfter(',')
        require(encoded.isNotBlank()) { "Image generation returned empty image data" }
        part.copy(url = saveImage(Base64.getDecoder().decode(encoded)))
    }
    part.url.startsWith("https://") || part.url.startsWith("http://") -> {
        try {
            part.copy(url = saveImage(downloadImage(part.url)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (fallbackToUrl) part else throw e
        }
    }
    else -> part
}
