package me.ayuilos.miffan.data.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.data.ai.suggestFastModel
import me.ayuilos.miffan.data.ai.withSuggestedFastModel
import me.ayuilos.miffan.data.datastore.DEFAULT_PROVIDERS
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.repository.ModelCatalogRepository
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelListException
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.util.parseErrorDetail
import java.io.IOException

/** How connecting a service with a key ended, in the terms the first-run conversation explains. */
sealed interface ConnectResult {
    /** The key works; [models] are the ones that chat in words, for the user to choose from. */
    data class Ok(val models: List<Model>) : ConnectResult

    /** The service says the key is wrong, revoked or not allowed. */
    data object KeyRejected : ConnectResult

    /** No answer: no network, a wrong address, or a timeout. */
    data object Unreachable : ConnectResult

    /** The service answered with an error that is not about the key. */
    data class ServiceError(val code: Int?, val detail: String) : ConnectResult

    /** The key works, but nothing in the service's list can chat in words. */
    data object NoChatModel : ConnectResult
}

/**
 * Connects one service from a pasted key in two steps: [verify] lists the models, which proves the key,
 * and [save] stores the service once the user has chosen the model to chat with. Nothing is saved before.
 */
class OnboardingConnector(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val catalogRepository: ModelCatalogRepository,
) {
    suspend fun verify(provider: ProviderSetting, apiKey: String): ConnectResult {
        val keyed = provider.withApiKey(apiKey.trim())
        val listed = try {
            withTimeoutOrNull(LIST_TIMEOUT_MS) { providerManager.getProviderByType(keyed).listModels(keyed) }
                ?: return ConnectResult.Unreachable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return classifyConnectFailure(e)
        }
        catalogRepository.awaitLoaded()
        val catalog = catalogRepository.catalog.value
        val models = onboardingChatModels(listed.map { ModelRegistry.resolveCapabilities(it, catalog) })
        return if (models.isEmpty()) ConnectResult.NoChatModel else ConnectResult.Ok(models)
    }

    /** Stores [provider] with [apiKey], chatting with [chosen], one of the [models] that [verify] returned. */
    suspend fun save(provider: ProviderSetting, apiKey: String, chosen: Model, models: List<Model>) {
        val keyed = provider.withApiKey(apiKey.trim())
        // A light model of the same service lets memory and topic sorting work without a trip to settings.
        val fast = keyed.copyProvider(models = models).suggestFastModel(chosen)
        settingsStore.update { it.withOnboardingConnection(keyed, listOfNotNull(chosen, fast), chosen) }
    }

    private companion object {
        const val LIST_TIMEOUT_MS = 30_000L
    }
}

/** Adds [models] to [provider] (or adds the provider, for a service typed in by hand) and chats with [chatModel]. */
internal fun Settings.withOnboardingConnection(provider: ProviderSetting, models: List<Model>, chatModel: Model): Settings {
    val existing = providers.firstOrNull { it.id == provider.id }
    val kept = existing?.models.orEmpty().filterNot { old -> models.any { it.modelId == old.modelId } }
    val connected = provider.copyProvider(enabled = true, models = kept + models)
    return copy(
        providers = if (existing == null) providers + connected else providers.map { if (it.id == provider.id) connected else it },
        chatModelId = chatModel.id,
    ).withSuggestedFastModel(connected, chatModel)
}

/**
 * Takes back the connection the first run made. With the settings from just [before] it, they come back
 * exactly; without them (the app was restarted since), the chat model's service loses the key and models
 * it was given, and a service typed in by hand goes away.
 */
fun Settings.undoOnboardingConnection(before: Settings?): Settings {
    if (before != null) {
        return copy(providers = before.providers, chatModelId = before.chatModelId, fastModelId = before.fastModelId,
            enableSuggestion = before.enableSuggestion)
    }
    val connected = providers.firstOrNull { provider -> provider.models.any { it.id == chatModelId } } ?: return this
    val builtIn = DEFAULT_PROVIDERS.any { it.id == connected.id }
    return copy(providers = if (builtIn) {
        providers.map { if (it.id == connected.id) it.copyProvider(models = emptyList()).withApiKey("") else it }
    } else providers - connected)
}

internal fun ProviderSetting.withApiKey(apiKey: String): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(apiKey = apiKey)
    is ProviderSetting.Google -> copy(apiKey = apiKey)
    is ProviderSetting.Claude -> copy(apiKey = apiKey)
}

internal fun classifyConnectFailure(error: Throwable): ConnectResult {
    if (generateSequence(error) { it.cause }.any { it is IOException }) return ConnectResult.Unreachable
    // A malformed address typed in by hand.
    if (error is IllegalArgumentException) return ConnectResult.Unreachable
    if (error !is ModelListException) return ConnectResult.ServiceError(null, error.message.orEmpty().take(DETAIL_LIMIT))
    val detail = runCatching { Json.parseToJsonElement(error.body).parseErrorDetail().message }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: error.body
    // Gemini answers a wrong key with 400 instead of 401.
    val keyRejected = error.code == 401 || error.code == 403 ||
        (error.code == 400 && listOf("API_KEY_INVALID", "API key not valid").any { it in error.body })
    return if (keyRejected) ConnectResult.KeyRejected else ConnectResult.ServiceError(error.code, detail.trim().take(DETAIL_LIMIT))
}

private const val DETAIL_LIMIT = 200
