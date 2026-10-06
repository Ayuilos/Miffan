package me.ayuilos.miffan.data.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.registry.ModelCatalog
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Instant

private const val TAG = "ModelCatalogRepository"
private const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L

class ModelCatalogRepository internal constructor(
    private val cacheFile: File,
    private val loadAsset: () -> String,
    private val client: OkHttpClient,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val logFailure: (Throwable) -> Unit = { Log.w(TAG, "Model catalog unavailable", it) },
) {
    constructor(context: Context, client: OkHttpClient, scope: CoroutineScope) : this(
        cacheFile = File(context.filesDir, "model-catalog.json"),
        loadAsset = { context.assets.open("model-catalog.json").bufferedReader().use { it.readText() } },
        client = client,
        scope = scope,
    )

    private val mutableCatalog = MutableStateFlow(ModelCatalog.EMPTY)
    val catalog: StateFlow<ModelCatalog> = mutableCatalog.asStateFlow()
    private val refreshMutex = Mutex()
    private val metadataFile = File(cacheFile.parentFile, "${cacheFile.name}.metadata")
    private var lastSuccess: Long? = null
    private var etag: String? = null
    private val initialLoad = scope.async(dispatcher) {
        val cached = if (cacheFile.exists()) loadOrNull { cacheFile.readText() } else null
        mutableCatalog.value = cached ?: loadOrNull(loadAsset) ?: ModelCatalog.EMPTY
        // Only send a persisted validator when its cached representation was loaded.
        if (cached != null) {
            try {
                if (metadataFile.exists()) {
                    val metadata = Json.parseToJsonElement(metadataFile.readText()) as? JsonObject
                    lastSuccess = (metadata?.get("lastSuccess") as? JsonPrimitive)?.longOrNull
                    etag = (metadata?.get("etag") as? JsonPrimitive)?.takeIf { it.isString }?.content
                }
            } catch (e: Exception) {
                logFailure(e)
            }
        }
    }

    suspend fun awaitLoaded() {
        initialLoad.await()
    }

    suspend fun refreshIfStale() {
        awaitLoaded()
        withContext(dispatcher) {
            refreshMutex.withLock {
                val timestamp = now()
                if (lastSuccess?.let { timestamp - it < REFRESH_INTERVAL_MS } == true) return@withLock
                try {
                    val request = Request.Builder()
                        .url("https://models.dev/models.json")
                        .apply { etag?.let { header("If-None-Match", it) } }
                        .get()
                        .build()
                    client.newCall(request).await().use { response ->
                        when (response.code) {
                            200 -> {
                                val body = response.body.string()
                                val updated = ModelCatalog.parseModelsDev(body, aliasesFrom = catalog.value)
                                val successfulAt = now()
                                writeAtomically(cacheFile, updated.toSnapshot(Instant.ofEpochMilli(successfulAt).toString()))
                                saveMetadata(successfulAt, response.header("ETag"))
                                mutableCatalog.value = updated
                            }

                            304 -> saveMetadata(now(), response.header("ETag") ?: etag)
                            else -> throw IOException("Model catalog HTTP ${response.code}")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logFailure(e)
                }
            }
        }
    }

    private fun loadOrNull(read: () -> String): ModelCatalog? = try {
        ModelCatalog.parseSnapshot(read())
    } catch (e: Exception) {
        logFailure(e)
        null
    }

    private fun saveMetadata(timestamp: Long, validator: String?) {
        val metadata = buildJsonObject {
            put("lastSuccess", timestamp)
            validator?.let { put("etag", it) }
        }
        writeAtomically(metadataFile, metadata.toString())
        lastSuccess = timestamp
        etag = validator
    }

    private fun writeAtomically(file: File, content: String) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            temp.writeText(content)
            if (!temp.renameTo(file)) throw IOException("Unable to replace ${file.name}")
        } finally {
            temp.delete()
        }
    }
}
