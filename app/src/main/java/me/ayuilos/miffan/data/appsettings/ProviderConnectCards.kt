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
val SECRET_CARD_TOOLS: Set<String> = setOf(CONNECT_PROVIDER_TOOL)

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
class ProviderConnectCards(
    private val settingsStore: SettingsStore,
    private val connector: OnboardingConnector,
    private val chatService: ChatService,
    private val scope: CoroutineScope,
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

    fun connect(conversationId: Uuid, toolCallId: String, target: ConnectTarget, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || _phases.value[toolCallId] == Phase.Checking) return
        set(toolCallId, Phase.Checking)
        jobs[toolCallId] = scope.launch {
            try {
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
                save(conversationId, toolCallId, target, trimmed, checked = false, models = emptyList())
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

    private fun set(toolCallId: String, phase: Phase) = _phases.update { it + (toolCallId to phase) }

    private fun clear(toolCallId: String) = _phases.update { it - toolCallId }
}
