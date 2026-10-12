package me.ayuilos.miffan.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.findModelById
import me.ayuilos.miffan.data.datastore.findProvider
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.isImMode
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import java.io.InterruptedIOException
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * Chores that run outside the chat reply. Only the asynchronous ones may borrow the partner's
 * chat model in easy chat: routing blocks the reply and titles are cosmetic, so both wait for a
 * real fast model.
 */
enum class BackgroundTask(val allowsChatFallback: Boolean) {
    MEMORY(true),
    SUMMARY(true),
    ROUTING(false),
    TITLE(false),
}

enum class BackgroundModelSource {
    /** A configured, reachable fast (or task-specific) model. */
    FAST,

    /** No usable fast model; easy chat borrows the partner's chat model for memory and summaries. */
    CHAT_FALLBACK,

    /** Nothing can run background chores. */
    NONE,
}

data class BackgroundModelChoice(val model: Model, val source: BackgroundModelSource)

/** The model that runs [task] for [assistant], or null when the task cannot run. */
fun Settings.backgroundModelFor(task: BackgroundTask, assistant: Assistant?): BackgroundModelChoice? {
    val configuredIds = when (task) {
        BackgroundTask.SUMMARY -> listOf(compressModelId, fastModelId)
        BackgroundTask.TITLE -> listOf(titleModelId, fastModelId)
        BackgroundTask.MEMORY, BackgroundTask.ROUTING -> listOf(fastModelId)
    }
    configuredIds.forEach { id ->
        usableBackgroundModel(id)?.let { return BackgroundModelChoice(it, BackgroundModelSource.FAST) }
    }
    if (task.allowsChatFallback && isImMode) {
        usableBackgroundModel(assistant?.chatModelId ?: chatModelId)?.let {
            return BackgroundModelChoice(it, BackgroundModelSource.CHAT_FALLBACK)
        }
    }
    return null
}

private fun Settings.usableBackgroundModel(id: Uuid?): Model? = findModelById(id)?.takeIf {
    // Main chat resolves saved models without checking ProviderSetting.enabled.
    it.type == ModelType.CHAT && it.findProvider(providers) != null
}

/** How memory and summaries currently run for [assistant]; drives the easy-chat status UI. */
fun Settings.backgroundModelSource(assistant: Assistant?): BackgroundModelSource =
    backgroundModelFor(BackgroundTask.MEMORY, assistant)?.source ?: BackgroundModelSource.NONE

/**
 * A light chat model of this provider to use as the fast model, or null when the provider has
 * none besides [chatModel]. Never returns [chatModel] itself.
 */
fun ProviderSetting.suggestFastModel(chatModel: Model?): Model? {
    val candidates = models.filter { model ->
        model.type == ModelType.CHAT && model.id != chatModel?.id &&
            FAST_MODEL_SKIP_WORDS.none { it in model.fastModelName() }
    }
    FAST_MODEL_TIERS.forEach { tier ->
        candidates.firstOrNull { model ->
            val name = model.fastModelName()
            if (tier == "mini") MINI_TOKEN.containsMatchIn(name) else tier in name
        }?.let { return it }
    }
    return null
}

private fun Model.fastModelName(): String = "$modelId $displayName".lowercase()
private val FAST_MODEL_SKIP_WORDS = listOf("image", "tts", "audio", "embed", "realtime", "transcribe")
private val FAST_MODEL_TIERS = listOf("flash-lite", "nano", "haiku", "mini", "lite", "flash", "small")
private val MINI_TOKEN = Regex("(^|[^a-z])mini([^a-z]|$)")

/**
 * Sets the fast model to [provider]'s suggestion for [chatModel] when the current fast model is
 * missing or unusable; otherwise returns these settings unchanged. Used when easy chat connects a
 * provider.
 */
fun Settings.withSuggestedFastModel(provider: ProviderSetting, chatModel: Model): Settings {
    if (backgroundModelFor(BackgroundTask.ROUTING, null) != null) return this
    val suggestion = provider.suggestFastModel(chatModel) ?: return this
    return copy(fastModelId = suggestion.id)
}

data class BackgroundFailure(
    val task: BackgroundTask,
    val modelName: String,
    val message: String,
    val at: Long,
)

/** Latest background chore failure, kept in memory for the easy-chat status row. */
class BackgroundModelHealth {
    private val _lastFailure = MutableStateFlow<BackgroundFailure?>(null)
    val lastFailure: StateFlow<BackgroundFailure?> = _lastFailure.asStateFlow()

    fun reportFailure(task: BackgroundTask, model: Model, error: Throwable) {
        // Deadline/network timeouts and cancellation are expected, not model health failures.
        if (generateSequence(error) { it.cause }.any {
                it is CancellationException || it is InterruptedIOException || it is TimeoutException
            }) return
        _lastFailure.value = BackgroundFailure(
            task = task,
            modelName = model.displayName.ifBlank { model.modelId },
            message = error.message ?: error.toString(),
            at = System.currentTimeMillis(),
        )
    }

    /** A later success clears the failure of the same task. */
    fun reportSuccess(task: BackgroundTask) {
        _lastFailure.update { failure -> failure?.takeIf { it.task != task } }
    }

    fun dismiss() {
        _lastFailure.value = null
    }
}
