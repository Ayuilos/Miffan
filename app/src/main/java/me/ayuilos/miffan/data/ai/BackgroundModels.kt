package me.ayuilos.miffan.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.Assistant
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting

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
fun Settings.backgroundModelFor(task: BackgroundTask, assistant: Assistant?): BackgroundModelChoice? =
    TODO("feature/background-model-data")

/** How memory and summaries currently run for [assistant]; drives the easy-chat status UI. */
fun Settings.backgroundModelSource(assistant: Assistant?): BackgroundModelSource =
    TODO("feature/background-model-data")

/**
 * A light chat model of this provider to use as the fast model, or null when the provider has
 * none besides [chatModel]. Never returns [chatModel] itself.
 */
fun ProviderSetting.suggestFastModel(chatModel: Model?): Model? =
    TODO("feature/background-model-data")

/**
 * Sets the fast model to [provider]'s suggestion for [chatModel] when the current fast model is
 * missing or unusable; otherwise returns these settings unchanged. Used when easy chat connects a
 * provider.
 */
fun Settings.withSuggestedFastModel(provider: ProviderSetting, chatModel: Model): Settings =
    TODO("feature/background-model-data")

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
        _lastFailure.value = BackgroundFailure(
            task = task,
            modelName = model.displayName.ifBlank { model.modelId },
            message = error.message ?: error.toString(),
            at = System.currentTimeMillis(),
        )
    }

    /** A later success clears the failure of the same task. */
    fun reportSuccess(task: BackgroundTask) {
        _lastFailure.value = _lastFailure.value?.takeIf { it.task != task }
    }

    fun dismiss() {
        _lastFailure.value = null
    }
}
