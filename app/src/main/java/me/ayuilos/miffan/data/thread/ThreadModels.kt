package me.ayuilos.miffan.data.thread

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.findModelById
import me.ayuilos.miffan.data.datastore.findProvider
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
) {
    /**
     * The fast model's answer to [prompt], or null on timeout, misconfiguration or failure.
     * [reasoningLevel] defaults to the user's fast-model setting; quick decisions pass OFF.
     */
    suspend fun fast(prompt: String, timeoutMillis: Long, reasoningLevel: ReasoningLevel? = null): String? {
        val settings = settingsStore.settingsFlow.value
        return complete(settings.fastModelId, prompt, timeoutMillis, reasoningLevel ?: settings.fastModelReasoningLevel)
    }

    /** The compression model's answer to [prompt], falling back to the fast model. */
    suspend fun compress(prompt: String, timeoutMillis: Long): String? {
        val settings = settingsStore.settingsFlow.value
        return complete(settings.compressModelId, prompt, timeoutMillis, ReasoningLevel.AUTO, fallback = settings.fastModelId)
    }

    private suspend fun complete(
        modelId: Uuid,
        prompt: String,
        timeoutMillis: Long,
        reasoningLevel: ReasoningLevel,
        fallback: Uuid? = null,
    ): String? {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(modelId, fallback = fallback) ?: return null
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
            }
            answer?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            request.cancel()
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private companion object {
        const val TAG = "ThreadModels"
    }
}
