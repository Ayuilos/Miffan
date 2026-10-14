package me.ayuilos.miffan.data.ai.openrouter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.datastore.Settings
import me.rerere.ai.provider.DiscoveredModelCapabilities
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.registry.ModelRegistry
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.uuid.Uuid

data class OpenRouterImageModel(
    val id: String,
    val name: String,
    val description: String?,
    val inputModalities: List<Modality>,
    val outputModalities: List<Modality>,
    val pricing: Map<String, String>,
    val contextLength: Int?,
    val created: Long?,
)

class OpenRouterImageModelService(private val httpClient: OkHttpClient) {
    private val mutex = Mutex()
    private var cached: List<OpenRouterImageModel>? = null
    private var cachedAtNanos = 0L

    suspend fun list(apiKey: String?, forceRefresh: Boolean = false): Result<List<OpenRouterImageModel>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = System.nanoTime()
                cached?.takeIf { !forceRefresh && now - cachedAtNanos < CACHE_NANOS }?.let {
                    return@withLock Result.success(it)
                }
                try {
                    val request = Request.Builder()
                        .url("https://openrouter.ai/api/v1/models?output_modalities=image")
                        .header("Accept", "application/json")
                        .apply {
                            apiKey?.trim()?.takeIf { it.isNotBlank() }?.let {
                                header("Authorization", "Bearer $it")
                            }
                        }.build()
                    val models = httpClient.newCall(request).await().use { response ->
                        check(response.isSuccessful) { "OpenRouter image models request failed (HTTP ${response.code})" }
                        parseOpenRouterImageModels(response.body.string())
                    }
                    cached = models
                    cachedAtNanos = System.nanoTime()
                    Result.success(models)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }

    private companion object {
        const val CACHE_NANOS = 10 * 60 * 1_000_000_000L
    }
}

internal fun parseOpenRouterImageModels(body: String): List<OpenRouterImageModel> {
    val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
        ?: error("OpenRouter models response is missing data")
    return data.mapNotNull { entry ->
        val model = entry.jsonObject
        val architecture = model["architecture"] as? JsonObject ?: return@mapNotNull null
        fun modalities(key: String) = (architecture[key] as? JsonArray).orEmpty().mapNotNull { value ->
            Modality.entries.firstOrNull { it.name.equals(value.jsonPrimitive.content, ignoreCase = true) }
        }.distinct()
        val outputs = modalities("output_modalities")
        if (Modality.IMAGE !in outputs) return@mapNotNull null
        val id = model["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        OpenRouterImageModel(
            id = id,
            name = model["name"]?.jsonPrimitive?.contentOrNull ?: id,
            description = model["description"]?.jsonPrimitive?.contentOrNull,
            inputModalities = modalities("input_modalities"),
            outputModalities = outputs,
            pricing = (model["pricing"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
            }.toMap(),
            contextLength = model["context_length"]?.jsonPrimitive?.intOrNull,
            created = model["created"]?.jsonPrimitive?.longOrNull,
        )
    }.sortedByDescending { it.created ?: Long.MIN_VALUE }
}

fun Settings.withOpenRouterImageModel(model: OpenRouterImageModel): Pair<Settings, Uuid>? {
    val provider = openRouterProvider()?.takeIf { it.enabled && it.apiKey.isNotBlank() } ?: return null
    require(Modality.IMAGE in model.outputModalities) { "The selected model cannot output images" }
    val existing = provider.models.firstOrNull { it.modelId == model.id }
    val stored = ModelRegistry.resolveCapabilities(
        (existing ?: Model(modelId = model.id, displayName = model.name)).copy(
            type = if (Modality.TEXT in model.outputModalities) ModelType.CHAT else ModelType.IMAGE,
            discoveredCapabilities = DiscoveredModelCapabilities(
                inputModalities = model.inputModalities,
                outputModalities = model.outputModalities,
                abilities = existing?.abilities,
            ),
        )
    )
    val models = if (existing == null) provider.models + stored else provider.models.map {
        if (it.id == existing.id) stored else it
    }
    return copy(
        providers = providers.map { if (it.id == provider.id) provider.copy(models = models) else it },
        imageToolModelId = stored.id,
    ) to stored.id
}
