package me.ayuilos.miffan.data.thread

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
) {
    /** The fast model's answer to [prompt], or null on timeout, misconfiguration or failure. */
    suspend fun fast(prompt: String, timeoutMillis: Long): String? {
        val settings = settingsStore.settingsFlow.value
        return complete(settings.fastModelId, prompt, timeoutMillis, settings.fastModelReasoningLevel)
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
    ): String? = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(modelId, fallback = fallback) ?: return@withContext null
        val provider = model.findProvider(settings.providers) ?: return@withContext null
        try {
            withTimeoutOrNull(timeoutMillis) {
                providerManager.getProviderByType(provider).generateText(
                    providerSetting = provider,
                    messages = listOf(UIMessage.user(prompt)),
                    params = backgroundTextGenerationParams(model, reasoningLevel),
                ).message.toText().trim().takeIf { it.isNotEmpty() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
