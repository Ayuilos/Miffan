package me.ayuilos.miffan.ui.im.welcome

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterAuthService
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterAuthState
import me.ayuilos.miffan.data.ai.openrouter.OPENROUTER_FREE_MODEL
import me.ayuilos.miffan.data.ai.openrouter.openRouterApiKeyOrNull
import me.ayuilos.miffan.data.ai.openrouter.openRouterProvider
import me.ayuilos.miffan.data.datastore.OPENROUTER_PROVIDER_ID
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.findProvider
import me.ayuilos.miffan.data.datastore.getCurrentChatModel
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.InterfaceMode
import me.ayuilos.miffan.data.model.MessageOrigin
import me.ayuilos.miffan.data.model.withInterfaceMode
import me.ayuilos.miffan.data.model.withWhaleThemeTrial
import me.ayuilos.miffan.data.onboarding.ConnectResult
import me.ayuilos.miffan.data.onboarding.OnboardingConnector
import me.ayuilos.miffan.data.onboarding.OnboardingStage
import me.ayuilos.miffan.data.onboarding.onboardingHint
import me.ayuilos.miffan.data.onboarding.onboardingStage
import me.ayuilos.miffan.data.onboarding.isFreeOnOpenRouter
import me.ayuilos.miffan.data.onboarding.openRouterChatModels
import me.ayuilos.miffan.data.onboarding.undoOnboardingConnection
import me.ayuilos.miffan.data.thread.PARTNER_ADDED_EVENT
import me.ayuilos.miffan.data.thread.ThreadService
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import java.util.Locale
import kotlin.uuid.Uuid

/** Text from resources, resolved where it is shown; [raw] is shown as is (an error a service sent back). */
data class Words(@StringRes val id: Int = 0, val args: List<String> = emptyList(), val raw: String? = null)

private fun words(@StringRes id: Int, vararg args: String) = Words(id, args.toList())

enum class StepStatus { DOING, DONE, FAILED }

data class ProgressStep(val label: Words, val status: StepStatus, val detail: Words? = null)

/** How a failed check is tried again: another key, the same key once more, or the form with what was typed. */
enum class RetryKind { NEW_KEY, SAME_KEY, EDIT_ADDRESS }

/** What a failed check offers: trying again, and the service's site. */
data class KeyFailure(val provider: ProviderSetting, val siteUrl: String?, val retry: RetryKind)

enum class ChoiceKind { BACKUP, PRO }

/** One row of the first-run conversation. Cards stay in the list; [used] ones fold away once answered. */
sealed interface WelcomeEntry {
    val key: Long

    data class Said(override val key: Long, val words: Words) : WelcomeEntry
    data class Typing(override val key: Long) : WelcomeEntry
    data class Mine(override val key: Long, val words: Words) : WelcomeEntry
    data class Note(override val key: Long, val words: Words) : WelcomeEntry

    /** "Connected to X"; [undoable] while the connection can still be taken back here. */
    data class Connected(override val key: Long, val words: Words, val undoable: Boolean = true) : WelcomeEntry
    data class Connect(override val key: Long) : WelcomeEntry

    /** Under Miffan's request for a key: where to get one, and, while this key is still awaited, another service. */
    data class KeyAsk(override val key: Long, val providerName: String, val url: String?, val active: Boolean = true) : WelcomeEntry
    data class Progress(
        override val key: Long,
        val steps: List<ProgressStep>,
        val waitingForLogin: Boolean = false,
        val failure: KeyFailure? = null,
    ) : WelcomeEntry
    data class AddressForm(override val key: Long, val provider: ProviderSetting?, val address: String = "", val used: Boolean = false) : WelcomeEntry
    /** The service's chat models, for the user to choose the one to chat with. */
    data class ModelPick(
        override val key: Long,
        val providerName: String,
        val models: List<Model>,
        /** Model ids marked free (OpenRouter), shown first. */
        val free: Set<String> = emptySet(),
        val used: Boolean = false,
    ) : WelcomeEntry
    data class Partners(override val key: Long, val used: Boolean = false) : WelcomeEntry
    data class Diy(override val key: Long, val used: Boolean = false) : WelcomeEntry
    data class Choices(override val key: Long, val kind: ChoiceKind, val used: Boolean = false) : WelcomeEntry
}

/** The first partner: Miffan as it is, the whale (which brings its theme), or one added now. */
sealed interface PartnerChoice {
    data object Miffan : PartnerChoice
    data class Whale(val name: String) : PartnerChoice
    data class New(val assistant: Assistant, val madeHere: Boolean) : PartnerChoice
}

sealed interface WelcomeEvent {
    data class OpenThread(val assistantId: Uuid) : WelcomeEvent
    data object Home : WelcomeEvent
    data object ProviderSettings : WelcomeEvent
}

/**
 * Miffan's first conversation with a new user: connect a service, pick a first partner, and hand over
 * to that partner. The lines Miffan says here are scripted and stay out of every thread.
 */
class ImWelcomeVM(
    context: Context,
    private val settingsStore: SettingsStore,
    private val connector: OnboardingConnector,
    private val auth: OpenRouterAuthService,
    private val threadService: ThreadService,
    private val appScope: AppScope,
) : ViewModel() {
    private val resources = context.applicationContext.resources
    private var nextKey = 0L
    private val _entries = MutableStateFlow<List<WelcomeEntry>>(emptyList())
    val entries: StateFlow<List<WelcomeEntry>> = _entries.asStateFlow()

    /** The service whose key the input bar asks for; null shows the quiet bar. */
    private val _keyFor = MutableStateFlow<ProviderSetting?>(null)
    val keyFor: StateFlow<ProviderSetting?> = _keyFor.asStateFlow()

    /** Something is under way (Miffan typing, a check, a login); choices wait. */
    private val _busy = MutableStateFlow(true)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The service connected in this conversation, for the header's status. */
    private val _connectedTo = MutableStateFlow<String?>(null)
    val connectedTo: StateFlow<String?> = _connectedTo.asStateFlow()

    private val _events = Channel<WelcomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loginEntry: Long? = null

    init {
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.first { !it.init }
            if (settings.onboardingStage() == OnboardingStage.PICK_PARTNER) {
                // Back after leaving halfway: the conversation so far, without replaying it.
                val name = settings.connectedServiceName()
                _connectedTo.value = name
                INTRO.forEach { add { key -> WelcomeEntry.Said(key, words(it)) } }
                add { WelcomeEntry.Connected(it, words(R.string.onboarding_note_connected, name, serviceSettingsPath())) }
                add { WelcomeEntry.Said(it, words(R.string.onboarding_say_pick_partner)) }
                add { WelcomeEntry.Partners(it) }
                _busy.value = false
            } else {
                // Left and opened again: the introduction was already heard, so it is just there.
                if (introPlayed) INTRO.forEach { add { key -> WelcomeEntry.Said(key, words(it)) } }
                else {
                    delay(400)
                    say(*INTRO.toIntArray())
                    introPlayed = true
                }
                add { WelcomeEntry.Connect(it) }
                _busy.value = false
            }
        }
        viewModelScope.launch {
            auth.state.collect { state -> loginEntry?.let { onLoginState(it, state) } }
        }
    }

    fun signInWithOpenRouter() = act {
        closeKeyAsk()
        beforeConnect = settingsStore.settingsFlow.value
        mine(words(R.string.onboarding_connect_openrouter))
        say(R.string.onboarding_say_browser)
        auth.startAuthorization(Locale.getDefault().toLanguageTag())
        loginEntry = add {
            WelcomeEntry.Progress(it, listOf(ProgressStep(words(R.string.onboarding_step_wait_login), StepStatus.DOING,
                words(R.string.onboarding_step_wait_login_detail))), waitingForLogin = true)
        }
    }

    fun reopenLogin() {
        auth.startAuthorization(Locale.getDefault().toLanguageTag())
    }

    fun cancelLogin() {
        val key = loginEntry ?: return
        loginEntry = null
        auth.cancelAuthorization()
        replace(key) { WelcomeEntry.Progress(key, listOf(ProgressStep(words(R.string.onboarding_step_login_cancelled), StepStatus.FAILED))) }
        _busy.value = false
        act { say(R.string.onboarding_say_try_other) }
    }

    private fun onLoginState(key: Long, state: OpenRouterAuthState) {
        when (state) {
            OpenRouterAuthState.Connected -> {
                loginEntry = null
                replace(key) { WelcomeEntry.Progress(key, listOf(ProgressStep(words(R.string.onboarding_step_login_done), StepStatus.DONE))) }
                viewModelScope.launch { chooseOpenRouterModel() }
            }
            is OpenRouterAuthState.Error -> {
                loginEntry = null
                replace(key) {
                    WelcomeEntry.Progress(key, listOf(ProgressStep(words(R.string.onboarding_step_login_failed), StepStatus.FAILED,
                        Words(raw = state.message))))
                }
                _busy.value = false
                act { say(R.string.onboarding_say_try_other) }
            }
            else -> Unit
        }
    }

    /** A service picked from the list: OpenRouter signs in, services at their own address get a form, the rest a key. */
    fun pickProvider(provider: ProviderSetting?) {
        if (provider?.id == OPENROUTER_PROVIDER_ID) return signInWithOpenRouter()
        if (provider == null || onboardingHint(provider.id)?.needsAddress == true) return act {
            closeKeyAsk()
            mine(words(R.string.onboarding_me_use, provider?.name ?: resources.getString(R.string.onboarding_custom_name)))
            say(R.string.onboarding_say_address)
            add { WelcomeEntry.AddressForm(it, provider) }
        }
        act {
            closeKeyAsk()
            mine(words(R.string.onboarding_me_use, provider.name))
            askForKey(provider)
        }
    }

    private suspend fun askForKey(provider: ProviderSetting) {
        say(words(R.string.onboarding_say_paste_key, provider.name))
        add { WelcomeEntry.KeyAsk(it, provider.name, onboardingHint(provider.id)?.keyUrl) }
        _keyFor.value = provider
    }

    fun submitKey(apiKey: String) {
        val provider = _keyFor.value ?: return
        if (apiKey.isBlank()) return
        closeKeyAsk()
        act {
            mine(Words(raw = maskKey(apiKey.trim())))
            check(provider, apiKey)
        }
    }

    /** The form for a service at an address of its own; [provider] is null for one typed in from scratch. */
    fun submitAddress(entryKey: Long, provider: ProviderSetting?, address: String, apiKey: String) = act {
        val url = address.trim().trimEnd('/')
        val target = (provider as? ProviderSetting.OpenAI)?.copy(baseUrl = url)
            ?: ProviderSetting.OpenAI(name = serviceNameOf(url), baseUrl = url)
        replace(entryKey) { (it as WelcomeEntry.AddressForm).copy(used = true) }
        mine(Words(raw = listOfNotNull(url, apiKey.trim().takeIf { it.isNotEmpty() }?.let(::maskKey)).joinToString("\n")))
        check(target, apiKey)
    }

    fun retry(failure: KeyFailure) = act {
        val provider = failure.provider
        // Answered: the failed check keeps its reason but no longer offers to try again.
        _entries.update { list -> list.map { if (it is WelcomeEntry.Progress && it.failure == failure) it.copy(failure = null) else it } }
        mine(words(failure.retry.label))
        when (failure.retry) {
            RetryKind.NEW_KEY -> {
                say(R.string.onboarding_say_paste_again)
                add { WelcomeEntry.KeyAsk(it, provider.name, onboardingHint(provider.id)?.keyUrl) }
                _keyFor.value = provider
            }
            RetryKind.SAME_KEY -> check(provider, lastKey)
            RetryKind.EDIT_ADDRESS -> {
                say(R.string.onboarding_say_edit)
                add { WelcomeEntry.AddressForm(it, provider, address = (provider as? ProviderSetting.OpenAI)?.baseUrl.orEmpty()) }
            }
        }
    }

    // The key of the last check, kept in memory only so a failed check can run once more.
    private var lastKey = ""

    private suspend fun check(provider: ProviderSetting, apiKey: String) {
        lastKey = apiKey
        beforeConnect = settingsStore.settingsFlow.value
        val name = provider.name
        val entry = add { WelcomeEntry.Progress(it, listOf(ProgressStep(words(R.string.onboarding_step_check_key, name), StepStatus.DOING))) }
        when (val result = connector.verify(provider, apiKey)) {
            is ConnectResult.Ok -> {
                // A service at home may need no key; then there was no key to praise.
                replace(entry) {
                    WelcomeEntry.Progress(entry, listOf(ProgressStep(
                        words(if (apiKey.isBlank()) R.string.onboarding_step_reached else R.string.onboarding_step_key_ok), StepStatus.DONE)))
                }
                // Which model to chat with is the user's choice; nothing is saved until it is made.
                pending = PendingConnection(provider, apiKey, result.models)
                say(words(R.string.onboarding_say_pick_model, name))
                add { WelcomeEntry.ModelPick(it, name, result.models) }
            }
            else -> {
                val (label, detail) = when (result) {
                    ConnectResult.KeyRejected -> words(R.string.onboarding_fail_key, name) to words(R.string.onboarding_fail_key_detail)
                    ConnectResult.Unreachable -> words(R.string.onboarding_fail_unreachable, name) to words(R.string.onboarding_fail_unreachable_detail)
                    ConnectResult.NoChatModel -> words(R.string.onboarding_fail_no_model, name) to words(R.string.onboarding_fail_no_model_detail)
                    is ConnectResult.ServiceError -> words(R.string.onboarding_fail_service, name) to
                        Words(raw = listOfNotNull(result.code?.let { "HTTP $it" }, result.detail.takeIf { it.isNotBlank() }).joinToString(" · "))
                    is ConnectResult.Ok -> error("handled above")
                }
                // A service at an address of its own is fixed in its form; otherwise the key, or a retry for a passing problem.
                val retry = when {
                    onboardingHint(provider.id)?.needsAddress == true || settingsProvider(provider.id) == null -> RetryKind.EDIT_ADDRESS
                    result == ConnectResult.KeyRejected || result == ConnectResult.NoChatModel -> RetryKind.NEW_KEY
                    else -> RetryKind.SAME_KEY
                }
                replace(entry) {
                    WelcomeEntry.Progress(entry, listOf(ProgressStep(label, StepStatus.FAILED, detail)),
                        failure = KeyFailure(provider, onboardingHint(provider.id)?.keyUrl, retry))
                }
            }
        }
    }

    private suspend fun connected(name: String, free: Boolean) {
        _connectedTo.value = name
        delay(300)
        val settings = settingsStore.settingsFlow.value
        val choosing = settings.onboardingStage() == OnboardingStage.PICK_PARTNER
        // Only the first run's partner choice still lies ahead to take a wrong connection back before.
        add { WelcomeEntry.Connected(it, words(R.string.onboarding_note_connected, name, serviceSettingsPath()), undoable = choosing) }
        delay(300)
        if (!choosing) {
            // Professional mode, or easy mode that already has partners: nothing left to choose.
            say(R.string.onboarding_say_connected)
            delay(500)
            settingsStore.update { it.copy(onboardingDone = true) }
            handedOff = true
            _events.send(WelcomeEvent.Home)
            return
        }
        say(R.string.onboarding_say_connected)
        if (free) say(R.string.onboarding_say_free_slow)
        say(R.string.onboarding_say_pick_partner)
        add { WelcomeEntry.Partners(it) }
        _busy.value = false
    }

    /** A service whose key worked, waiting for the user to choose a model; kept in memory only. */
    private class PendingConnection(val provider: ProviderSetting, val apiKey: String, val models: List<Model>)

    private var pending: PendingConnection? = null

    /**
     * Signed in to OpenRouter: the login already connected its free router (the browser may come back
     * to a new process), and the user still chooses the model, free ones first.
     */
    private suspend fun chooseOpenRouterModel() {
        val settings = settingsStore.settingsFlow.value
        val provider = settings.openRouterProvider()
        val apiKey = settings.openRouterApiKeyOrNull()
        val free = provider?.models?.firstOrNull { it.modelId == OPENROUTER_FREE_MODEL }
        if (provider == null || apiKey == null || free == null) return connected("OpenRouter", free = true)
        val listed = (connector.verify(provider, apiKey) as? ConnectResult.Ok)?.models.orEmpty()
        // Without the list (a network hiccup) the free router, already connected, is the choice there is.
        if (listed.isEmpty()) return connected(provider.name, free = true)
        val models = openRouterChatModels(listed, free)
        pending = PendingConnection(provider, apiKey, models)
        say(words(R.string.onboarding_say_pick_model, provider.name))
        add { WelcomeEntry.ModelPick(it, provider.name, models, free = models.filter { m -> m.isFreeOnOpenRouter() }.mapTo(HashSet()) { m -> m.modelId }) }
        _busy.value = false
    }

    fun chooseModel(entryKey: Long, model: Model) = act {
        val connection = pending ?: return@act
        pending = null
        replace(entryKey) { (it as WelcomeEntry.ModelPick).copy(used = true) }
        mine(words(R.string.onboarding_me_use, model.displayName.ifBlank { model.modelId }))
        val openRouter = connection.provider.id == OPENROUTER_PROVIDER_ID
        val free = openRouter && model.isFreeOnOpenRouter()
        // A free choice keeps its background helper free too; a paid one may use any light model.
        val pool = if (free) connection.models.filter { it.isFreeOnOpenRouter() } else connection.models
        connector.save(connection.provider, connection.apiKey, model, pool)
        connected(connection.provider.name, free = free)
    }

    // Settings as they were before the latest connection attempt, to take a connection back exactly.
    private var beforeConnect: Settings? = null

    /** Connected the wrong service: take the key and models back out and choose again. */
    fun undoConnection() = act {
        _entries.update { list -> list.map { if (it is WelcomeEntry.Connected) it.copy(undoable = false) else it } }
        foldCards()
        mine(words(R.string.onboarding_me_undo_connect))
        val before = beforeConnect
        settingsStore.update { it.undoOnboardingConnection(before) }
        beforeConnect = null
        _connectedTo.value = null
        say(R.string.onboarding_say_undo)
        add { WelcomeEntry.Connect(it) }
    }

    fun pickPartner(choice: PartnerChoice, name: String) = act {
        foldCards()
        mine(words(R.string.onboarding_me_pick, name))
        when (choice) {
            PartnerChoice.Miffan -> Unit
            is PartnerChoice.Whale -> say(words(R.string.onboarding_say_calling_whale, name))
            is PartnerChoice.New -> say(words(if (choice.madeHere) R.string.onboarding_say_diy_arrived else R.string.onboarding_say_calling, name))
        }
        delay(500)
        val partnerId = commitPartner(choice)
        val summary = resources.getString(if (choice == PartnerChoice.Miffan) R.string.onboarding_event_start else R.string.onboarding_event_added, name)
        // The partner's first words run on the app's scope: this page is gone by the time they arrive.
        appScope.launch {
            runCatching {
                threadService.deliverFrom(
                    origin = MessageOrigin.Event(kind = PARTNER_ADDED_EVENT, summary = summary),
                    assistantId = partnerId,
                    content = listOf(UIMessagePart.Text(resources.getString(R.string.onboarding_greeting_instruction))),
                )
            }
        }
        handedOff = true
        _events.send(WelcomeEvent.OpenThread(partnerId))
    }

    private suspend fun commitPartner(choice: PartnerChoice): Uuid {
        var picked: Uuid? = null
        settingsStore.update { current ->
            val partnered = when (choice) {
                PartnerChoice.Miffan -> current.copy(assistantId = current.assistants.first().id)
                is PartnerChoice.Whale -> current.withWhaleThemeTrial(choice.name)
                is PartnerChoice.New -> current.copy(
                    assistants = if (current.assistants.any { it.id == choice.assistant.id }) current.assistants else current.assistants + choice.assistant,
                    assistantId = choice.assistant.id,
                )
            }
            picked = partnered.assistantId
            // Miffan introduced itself by name, so the partner list should not call it the default assistant.
            val miffanId = partnered.assistants.first().id
            partnered.copy(
                assistants = partnered.assistants.map { if (it.id == miffanId && it.name.isBlank()) it.copy(name = MIFFAN_NAME) else it },
                onboardingDone = true,
            )
        }
        return picked ?: settingsStore.settingsFlow.value.assistantId
    }

    fun openDiy() = act {
        foldCards()
        mine(words(R.string.onboarding_me_diy))
        say(R.string.onboarding_say_diy)
        add { WelcomeEntry.Diy(it) }
    }

    fun leaveDiy() = act {
        foldCards()
        mine(words(R.string.onboarding_diy_back))
        say(R.string.onboarding_say_diy_back)
        add { WelcomeEntry.Partners(it) }
    }

    fun chooseBackup() = act {
        closeKeyAsk()
        mine(words(R.string.onboarding_connect_backup))
        say(R.string.onboarding_say_backup)
        add { WelcomeEntry.Choices(it, ChoiceKind.BACKUP) }
    }

    /** A backup option was picked; the page restores a file itself and sends the others to the backup pages. */
    fun backupPicked(@StringRes label: Int) = mine(words(label))

    private var restoreEntry: Long? = null

    fun restoreStarted() {
        _busy.value = true
        restoreEntry = add { WelcomeEntry.Progress(it, listOf(ProgressStep(words(R.string.onboarding_step_restoring), StepStatus.DOING))) }
    }

    fun restoreFailed(message: String) {
        val key = restoreEntry ?: return
        replace(key) { WelcomeEntry.Progress(key, listOf(ProgressStep(words(R.string.onboarding_step_restore_failed), StepStatus.FAILED, Words(raw = message)))) }
        _busy.value = false
    }

    fun choosePro() = act {
        closeKeyAsk()
        mine(words(R.string.onboarding_connect_pro))
        say(R.string.onboarding_say_pro_1, R.string.onboarding_say_pro_2)
        add { WelcomeEntry.Choices(it, ChoiceKind.PRO) }
    }

    fun goPro(entryKey: Long) = act {
        replace(entryKey) { (it as WelcomeEntry.Choices).copy(used = true) }
        settingsStore.update { it.withInterfaceMode(InterfaceMode.PROFESSIONAL).copy(onboardingDone = true) }
        // Not a hand-over: backing out of the settings without a model lands here again, still usable.
        _events.send(WelcomeEvent.ProviderSettings)
    }

    fun stayHere(entryKey: Long) = act {
        replace(entryKey) { (it as WelcomeEntry.Choices).copy(used = true) }
        mine(words(R.string.onboarding_pro_stay))
        say(R.string.onboarding_say_stay)
        add { WelcomeEntry.Connect(it) }
    }

    // The conversation went on elsewhere (a partner's chat, settings); this page takes no more choices.
    private var handedOff = false

    /**
     * Runs one step of the conversation; taps while one runs are dropped. A login waiting in the
     * browser keeps the page busy after the step that started it.
     */
    private fun act(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                if (loginEntry == null && !handedOff) _busy.value = false
            }
        }
    }

    /** Another way was chosen: the key bar, its "another service" and a waiting model choice all close. */
    private fun closeKeyAsk() {
        _keyFor.value = null
        pending = null
        _entries.update { list ->
            list.map {
                when (it) {
                    is WelcomeEntry.KeyAsk -> it.copy(active = false)
                    is WelcomeEntry.ModelPick -> it.copy(used = true)
                    else -> it
                }
            }
        }
    }

    private fun foldCards() = _entries.update { list ->
        list.map {
            when (it) {
                is WelcomeEntry.Partners -> it.copy(used = true)
                is WelcomeEntry.Diy -> it.copy(used = true)
                else -> it
            }
        }
    }

    private suspend fun say(vararg ids: Int) = ids.forEach { say(words(it)) }

    private suspend fun say(line: Words) {
        val typing = add { WelcomeEntry.Typing(it) }
        delay(TYPING_MS)
        replace(typing) { WelcomeEntry.Said(typing, line) }
        delay(GAP_MS)
    }

    private fun mine(line: Words) {
        add { WelcomeEntry.Mine(it, line) }
    }

    private fun add(make: (Long) -> WelcomeEntry): Long {
        val key = nextKey++
        _entries.update { it + make(key) }
        return key
    }

    private fun replace(key: Long, make: (WelcomeEntry) -> WelcomeEntry) = _entries.update { list ->
        list.map { if (it.key == key) make(it) else it }
    }

    /** Where services are changed later, named as the tabs and rows name it. */
    private fun serviceSettingsPath() = resources.getString(R.string.im_tab_me) + " › " + resources.getString(R.string.im_me_ai_service)

    private fun settingsProvider(id: Uuid) = settingsStore.settingsFlow.value.providers.firstOrNull { it.id == id }

    private fun Settings.connectedServiceName(): String =
        getCurrentChatModel()?.findProvider(providers, checkOverwrite = false)?.name
            ?: providers.firstOrNull { it.models.isNotEmpty() }?.name.orEmpty()

    companion object {
        const val MIFFAN_NAME = "Miffan"
        private const val TYPING_MS = 700L
        private const val GAP_MS = 250L
        // Per process: a new process plays the introduction again, which is fine.
        private var introPlayed = false
        private val INTRO = listOf(R.string.onboarding_intro_1, R.string.onboarding_intro_2, R.string.onboarding_intro_3)
    }
}

@get:StringRes
val RetryKind.label: Int get() = when (this) {
    RetryKind.NEW_KEY -> R.string.onboarding_fail_retry
    RetryKind.SAME_KEY -> R.string.onboarding_fail_retry_same
    RetryKind.EDIT_ADDRESS -> R.string.onboarding_fail_edit
}

/** The start and end of a pasted key, enough to recognize it without showing it. */
internal fun maskKey(key: String): String = if (key.length > 12) key.take(6) + "••••••" + key.takeLast(4) else "••••••"

/** A name for a service typed in by hand: its host, without the usual api. prefix. */
internal fun serviceNameOf(url: String): String =
    url.substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("api.").ifBlank { url }
