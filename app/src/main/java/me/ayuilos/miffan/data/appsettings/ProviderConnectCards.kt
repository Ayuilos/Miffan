package me.ayuilos.miffan.data.appsettings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.onboarding.ConnectResult
import me.ayuilos.miffan.data.onboarding.OnboardingConnector
import me.ayuilos.miffan.service.ChatService
import me.rerere.ai.provider.Model
import kotlin.uuid.Uuid

/** Tool calls that are settled only by the app, from a card the owner fills on the phone. */
val SECRET_CARD_TOOLS: Set<String> = setOf(CONNECT_PROVIDER_TOOL, CONNECT_SEARCH_TOOL, SET_SECRET_HEADER_TOOL, CONNECT_VOICE_TOOL)

/**
 * Guards a decision on [toolCallId] (plus [alsoIds] decided with it) against the secure cards among [secretIds].
 * Returns the other calls the decision may settle, or null when it may not proceed: a secure card is approved or
 * answered ([settles]) only [fromCard] (the app, after storing the owner's key), and that path settles nothing else.
 * Declining is allowed from anywhere.
 */
fun secretCardDecisionIds(
    toolCallId: String,
    alsoIds: Set<String>,
    secretIds: Set<String>,
    settles: Boolean,
    fromCard: Boolean,
): Set<String>? = when {
    fromCard -> if (toolCallId in secretIds && settles) emptySet() else null
    settles && toolCallId in secretIds -> null
    settles -> alsoIds - secretIds
    else -> alsoIds
}

/**
 * Runs the secure key cards. The key the owner types goes from the card to here, is checked against the one
 * service the card names and stored for it, and is then dropped. The partner only gets [connectResultJson];
 * the state shown on cards ([phases]) never holds the key either.
 */
private const val SEARCH_CHECK_TIMEOUT_MS = 30_000L

class ProviderConnectCards(
    private val settingsStore: SettingsStore,
    private val connector: OnboardingConnector,
    private val chatService: ChatService,
    private val scope: CoroutineScope,
    private val voice: VoiceSampler? = null,
) {
    sealed interface Phase {
        data object Checking : Phase
        data object KeyRejected : Phase
        data object Unreachable : Phase
        data class ServiceError(val detail: String) : Phase

        /** The provider was removed or now points elsewhere than the card showed. */
        data object TargetChanged : Phase

        /** Stored, but the call could no longer be settled (the reply was stopped meanwhile); nothing more to fill. */
        data object Saved : Phase
    }

    private val _phases = MutableStateFlow<Map<String, Phase>>(emptyMap())
    val phases: StateFlow<Map<String, Phase>> = _phases.asStateFlow()
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** Keys the owner typed into the chat box and moved to a card instead of sending; each card takes its own once. */
    private val handoffKeys = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val _handoffs = MutableStateFlow<Set<String>>(emptySet())
    /** The cards with a key waiting for them; never the keys themselves. */
    val handoffs: StateFlow<Set<String>> = _handoffs.asStateFlow()

    fun handOff(toolCallId: String, key: String) {
        handoffKeys[toolCallId] = key.trim()
        _handoffs.update { it + toolCallId }
    }

    fun takeHandoff(toolCallId: String): String? {
        _handoffs.update { it - toolCallId }
        return handoffKeys.remove(toolCallId)
    }

    fun connect(conversationId: Uuid, toolCallId: String, target: ConnectTarget, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || _phases.value[toolCallId] == Phase.Checking) return
        set(toolCallId, Phase.Checking)
        jobs[toolCallId] = scope.launch {
            try {
                when (target) {
                    is SearchTarget -> {
                        when (val result = checkSearchKey(target, trimmed)) {
                            null -> saveSecret(conversationId, toolCallId) { it.withSearchKey(target, trimmed, checked = true) }
                            else -> set(toolCallId, result)
                        }
                        return@launch
                    }
                    is VoiceTarget -> {
                        connectVoice(conversationId, toolCallId, target, trimmed)
                        return@launch
                    }
                    // Nothing to check a header against without calling the service's tools; it is stored as given.
                    is HeaderTarget -> {
                        saveSecret(conversationId, toolCallId) { it.withSecretHeader(target, trimmed) }
                        return@launch
                    }
                    else -> Unit
                }
                val provider = target.providerWith(settingsStore.settingsFlow.value, trimmed)
                if (provider == null) {
                    set(toolCallId, Phase.TargetChanged)
                    return@launch
                }
                when (val result = connector.verify(provider, trimmed)) {
                    is ConnectResult.Ok -> save(conversationId, toolCallId, target, trimmed, checked = true, models = result.models)
                    // The key works; the service only has nothing to chat with yet.
                    ConnectResult.NoChatModel -> save(conversationId, toolCallId, target, trimmed, checked = true, models = emptyList())
                    ConnectResult.KeyRejected -> set(toolCallId, Phase.KeyRejected)
                    ConnectResult.Unreachable -> set(toolCallId, Phase.Unreachable)
                    is ConnectResult.ServiceError -> set(toolCallId, Phase.ServiceError(scrubServiceDetail(result.detail, trimmed)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                set(toolCallId, Phase.ServiceError(scrubServiceDetail(e.message.orEmpty(), trimmed)))
            } finally {
                jobs.remove(toolCallId)
            }
        }
    }

    /** After the service could not be reached: keep the key anyway, the partner hears it was not checked. */
    fun saveUnchecked(conversationId: Uuid, toolCallId: String, target: ConnectTarget, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || _phases.value[toolCallId] == Phase.Checking) return
        set(toolCallId, Phase.Checking)
        jobs[toolCallId] = scope.launch {
            try {
                if (target is SearchTarget) saveSecret(conversationId, toolCallId) { it.withSearchKey(target, trimmed, checked = false) }
                else if (target is VoiceTarget) saveSecret(conversationId, toolCallId) { it.withVoiceKey(target, trimmed, checked = false) }
                else save(conversationId, toolCallId, target, trimmed, checked = false, models = emptyList())
            } finally {
                jobs.remove(toolCallId)
            }
        }
    }

    /** [reason] is the owner's own words (or empty), shown in the record and passed to the partner. */
    fun cancel(conversationId: Uuid, toolCallId: String, reason: String = "") {
        jobs.remove(toolCallId)?.cancel()
        clear(toolCallId)
        scope.launch { chatService.declineSecretCard(conversationId, toolCallId, reason) }
    }

    private suspend fun save(
        conversationId: Uuid,
        toolCallId: String,
        target: ConnectTarget,
        key: String,
        checked: Boolean,
        models: List<Model>,
    ) {
        var saved: ConnectSave.Saved? = null
        settingsStore.update { settings ->
            when (val outcome = settings.withConnectedProvider(target, key)) {
                is ConnectSave.Saved -> outcome.settings.also { saved = outcome }
                ConnectSave.TargetChanged -> settings
            }
        }
        val result = saved
        if (result == null) {
            set(toolCallId, Phase.TargetChanged)
            return
        }
        val delivered = chatService.completeSecretCard(conversationId, toolCallId, connectResultJson(result.provider, checked, models))
        // Saved either way; never offer the field again, or a second submit would add the service twice.
        if (delivered) clear(toolCallId) else set(toolCallId, Phase.Saved)
    }

    /**
     * A read-aloud key is checked by reading the sample with it, which the owner then hears; a voice input key cannot
     * be checked without recording, so it is stored as given.
     */
    private suspend fun connectVoice(conversationId: Uuid, toolCallId: String, target: VoiceTarget, key: String) {
        val sampler = voice
        if (target.kind == VoiceKind.ASR || sampler == null) {
            saveSecret(conversationId, toolCallId) { it.withVoiceKey(target, key, checked = false) }
            return
        }
        val service = TtsKeyService.of(target.type) ?: return set(toolCallId, Phase.TargetChanged)
        val current = target.serviceId?.let { id -> settingsStore.settingsFlow.value.ttsProviders.firstOrNull { it.id == id } }
        if (target.serviceId != null && (current == null || TtsKeyService.of(current) != service)) return set(toolCallId, Phase.TargetChanged)
        val setting = (current ?: service.create()).transformTtsSecret { key }.configuredFor(target)
        val sample = try {
            sampler.synthesize(setting) ?: return set(toolCallId, Phase.Unreachable)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return set(toolCallId, checkFailure(e, key))
        }
        if (!saveSecret(conversationId, toolCallId) { it.withVoiceKey(target, key, checked = true) }) return
        // Heard right on the card: the voice the owner gets is the proof the key works.
        try {
            sampler.play(sample)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Playback trouble does not undo a key that worked.
        }
    }

    /** What a card shows when a check with the key failed. */
    private fun checkFailure(error: Throwable, key: String): Phase {
        if (generateSequence(error) { it.cause }.any { it is java.io.IOException }) return Phase.Unreachable
        val text = error.message.orEmpty()
        val rejected = listOf("401", "403", "unauthorized", "forbidden", "invalid api key", "invalid_api_key", "api key")
            .any { text.contains(it, ignoreCase = true) }
        return if (rejected) Phase.KeyRejected else Phase.ServiceError(scrubServiceDetail(text, key))
    }

    /** One test search with the key: null when it works, otherwise what the card shows. */
    private suspend fun checkSearchKey(target: SearchTarget, key: String): Phase? {
        val current = target.serviceId?.let { id -> settingsStore.settingsFlow.value.searchServices.firstOrNull { it.id == id } }
        val options = (current ?: target.service.create()).withSearchApiKey(key)
        val result = kotlinx.coroutines.withTimeoutOrNull(SEARCH_CHECK_TIMEOUT_MS) {
            me.rerere.search.SearchService.getService(options).search(
                kotlinx.serialization.json.buildJsonObject { put("query", kotlinx.serialization.json.JsonPrimitive("Miffan")) },
                me.rerere.search.SearchCommonOptions(resultSize = 1), options)
        } ?: return Phase.Unreachable
        val error = result.exceptionOrNull() ?: return null
        return checkFailure(error, key)
    }

    /** True when the key was stored (whether or not the call could still be settled). */
    private suspend fun saveSecret(conversationId: Uuid, toolCallId: String, apply: (me.ayuilos.miffan.data.datastore.Settings) -> SecretSave): Boolean {
        var saved: SecretSave.Saved? = null
        settingsStore.update { settings ->
            when (val outcome = apply(settings)) {
                is SecretSave.Saved -> outcome.settings.also { saved = outcome }
                SecretSave.TargetChanged -> settings
            }
        }
        val result = saved ?: run { set(toolCallId, Phase.TargetChanged); return false }
        val delivered = chatService.completeSecretCard(conversationId, toolCallId, result.result)
        if (delivered) clear(toolCallId) else set(toolCallId, Phase.Saved)
        return true
    }

    private fun set(toolCallId: String, phase: Phase) = _phases.update { it + (toolCallId to phase) }

    private fun clear(toolCallId: String) {
        _phases.update { it - toolCallId }
        takeHandoff(toolCallId)
    }
}
