package me.ayuilos.miffan.ui.im

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.ayuilos.miffan.data.ai.tools.createImageGenerationTool
import me.ayuilos.miffan.data.ai.tools.imageIdForUrl
import me.ayuilos.miffan.data.ai.tools.imageToolModelOrNull
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.entity.GenMediaEntity
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.data.repository.GenMediaRepository
import me.ayuilos.miffan.ui.pages.imggen.GeneratedImage
import me.ayuilos.miffan.ui.pages.imggen.toGeneratedImage
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import java.io.File
import java.net.URI

/**
 * Easy chat's drawing page. It draws with the same model and backend as partners' generate_image
 * tool (Me → service → drawing), so any model chosen there works here too, and keeps every picture in
 * the album the full drawing page shares.
 */
class ImDrawVM(
    application: Application,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val filesManager: FilesManager,
    private val httpClient: OkHttpClient,
    private val genMediaRepository: GenMediaRepository,
) : AndroidViewModel(application) {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt.asStateFlow()
    private val _count = MutableStateFlow(1)
    val count: StateFlow<Int> = _count.asStateFlow()
    private val _size = MutableStateFlow(ImageGenSize.AUTO)
    val size: StateFlow<ImageGenSize> = _size.asStateFlow()
    private val _references = MutableStateFlow<List<String>>(emptyList())
    val references: StateFlow<List<String>> = _references.asStateFlow()
    private val _drawing = MutableStateFlow(false)
    val drawing: StateFlow<Boolean> = _drawing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** This visit's last request and what came back. */
    private val _session = MutableStateFlow<Pair<String, List<GeneratedImage>>?>(null)
    val session: StateFlow<Pair<String, List<GeneratedImage>>?> = _session.asStateFlow()

    val album: Flow<PagingData<GeneratedImage>> = Pager(PagingConfig(pageSize = 30, enablePlaceholders = false)) {
        genMediaRepository.getAllMedia()
    }.flow.map { page -> page.map { it.toGeneratedImage(filesManager) } }.cachedIn(viewModelScope)

    private var job: Job? = null

    fun updatePrompt(value: String) { _prompt.value = value }
    fun updateCount(value: Int) { _count.value = value }
    fun updateSize(value: ImageGenSize) { _size.value = value }
    fun addReferences(paths: List<String>) { _references.value = (_references.value + paths).distinct().take(MAX_REFERENCES) }
    fun removeReference(path: String) { _references.value -= path }
    fun clearError() { _error.value = null }

    fun cancel() {
        job?.cancel()
        _drawing.value = false
    }

    fun draw() {
        val request = _prompt.value.trim()
        if (request.isEmpty() || _drawing.value) return
        val model = settings.value.imageToolModelOrNull() ?: return
        val references = _references.value.map { "file://$it" }
        val conversation = listOf(UIMessage(role = MessageRole.USER, parts = references.map { UIMessagePart.Image(it) }))
        val tool = createImageGenerationTool(
            settings = { settingsStore.settingsFlow.value },
            messages = { conversation },
            providerManager = providerManager,
            filesManager = filesManager,
            httpClient = httpClient,
        )
        val args = buildJsonObject {
            put("prompt", request)
            if (references.isNotEmpty()) putJsonArray("reference_images") { references.forEach { add(imageIdForUrl(it)) } }
            // Only the Images API takes a size; chat models that draw would reject the extra field.
            if (_size.value != ImageGenSize.AUTO && model.type == ModelType.IMAGE) putJsonObject("options") { put("size", _size.value.value) }
        }
        _drawing.value = true
        _error.value = null
        _session.value = request to emptyList()
        job = viewModelScope.launch {
            try {
                // One picture per call: chat models that draw return one image at a time.
                val outputs = List(_count.value) { async { tool.execute(args) } }.awaitAll()
                val pictures = outputs.flatMap { parts -> parts.filterIsInstance<UIMessagePart.Image>() }
                    .map { keep(it.url, request, model.displayName.ifBlank { model.modelId }) }
                if (pictures.isEmpty()) _error.value = outputs.firstNotNullOfOrNull { failure(it) } ?: "No image came back"
                _session.value = request to pictures
                if (pictures.isNotEmpty()) _prompt.value = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _drawing.value = false
            }
        }
    }

    fun delete(image: GeneratedImage) {
        viewModelScope.launch(Dispatchers.IO) {
            genMediaRepository.deleteMedia(image.id)
            File(image.filePath).delete()
        }
    }

    /** Copies a drawn picture into the album's folder and records it there. */
    private suspend fun keep(url: String, prompt: String, modelName: String): GeneratedImage = withContext(Dispatchers.IO) {
        val source = File(URI(url))
        val now = System.currentTimeMillis()
        val target = File(filesManager.getImagesDir(), "${now}_${source.name}")
        source.copyTo(target, overwrite = true)
        genMediaRepository.insertMedia(GenMediaEntity(path = "images/${target.name}", modelId = modelName, prompt = prompt, createAt = now))
        GeneratedImage(id = 0, prompt = prompt, filePath = target.absolutePath, timestamp = now, model = modelName)
    }

    /** The tool reports problems as JSON text: an error, or the setup it is missing. */
    private fun failure(parts: List<UIMessagePart>): String? = parts.filterIsInstance<UIMessagePart.Text>().firstNotNullOfOrNull { text ->
        runCatching { Json.parseToJsonElement(text.text) as JsonObject }.getOrNull()?.let { json ->
            (json["error"] ?: json["message"])?.jsonPrimitive?.contentOrNull
        }
    }

    private companion object {
        const val MAX_REFERENCES = 4
    }
}
