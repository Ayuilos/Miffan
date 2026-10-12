package me.ayuilos.miffan.data.thread

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import me.ayuilos.miffan.data.ai.BackgroundModelChoice
import me.ayuilos.miffan.data.ai.BackgroundModelHealth
import me.ayuilos.miffan.data.ai.BackgroundModelSource
import me.ayuilos.miffan.data.ai.BackgroundTask
import me.ayuilos.miffan.data.ai.backgroundModelFor
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.findProvider
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.service.backgroundTextGenerationParams
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/** One-shot background completions for thread bookkeeping (routing, summaries). */
class ThreadModels(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val scope: CoroutineScope,
    private val backgroundModelHealth: BackgroundModelHealth,
) {
    /**
     * The fast model's answer to [prompt], or null on timeout, misconfiguration or failure.
     * [reasoningLevel] defaults to the user's fast-model setting; quick decisions pass OFF.
     */
    suspend fun fast(prompt: String, timeoutMillis: Long, reasoningLevel: ReasoningLevel? = null): String? {
        val settings = settingsStore.settingsFlow.value
        val choice = settings.backgroundModelFor(BackgroundTask.ROUTING, null) ?: return null
        return complete(BackgroundTask.ROUTING, choice, prompt, timeoutMillis, reasoningLevel ?: settings.fastModelReasoningLevel)
    }

    /** The compression model's answer, with fast-model and easy-chat partner fallbacks. */
    suspend fun compress(prompt: String, timeoutMillis: Long, assistantId: Uuid?): String? {
        val settings = settingsStore.settingsFlow.value
        val assistant = assistantId?.let(settings::getAssistantById)
        val choice = settings.backgroundModelFor(BackgroundTask.SUMMARY, assistant) ?: return null
        // LOW avoids OFF's rejected none/minimal effort on always-reasoning chat models.
        val reasoningLevel = if (choice.source == BackgroundModelSource.CHAT_FALLBACK) ReasoningLevel.LOW else ReasoningLevel.AUTO
        return complete(BackgroundTask.SUMMARY, choice, prompt, timeoutMillis, reasoningLevel)
    }

    private suspend fun complete(
        task: BackgroundTask,
        choice: BackgroundModelChoice,
        prompt: String,
        timeoutMillis: Long,
        reasoningLevel: ReasoningLevel,
    ): String? {
        val settings = settingsStore.settingsFlow.value
        val model = choice.model
        val provider = model.findProvider(settings.providers) ?: return null
        // The provider call can block past cancellation, so it runs outside the caller and is
        // abandoned at the deadline; the caller never waits longer than [timeoutMillis].
        val request = scope.async(Dispatchers.IO) {
            providerManager.getProviderByType(provider).generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model, reasoningLevel),
            ).message.toText().trim().takeIf { it.isNotEmpty() }
        }
        return try {
            val answer = withTimeoutOrNull(timeoutMillis) { request.await().orEmpty() }
            if (answer == null) {
                request.cancel()
                Log.w(TAG, "${model.modelId} gave no answer within ${timeoutMillis}ms")
            } else {
                backgroundModelHealth.reportSuccess(task)
            }
            answer?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            request.cancel()
            throw e
        } catch (e: Exception) {
            backgroundModelHealth.reportFailure(task, model, e)
            e.printStackTrace()
            null
        }
    }

    private companion object {
        const val TAG = "ThreadModels"
    }
}
