package me.ayuilos.miffan.ui.im.computer

import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.rdpServer
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.usesRdp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jcraft.jsch.JSchHostKeyException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.ai.computer.ComputerPermissions
import me.ayuilos.miffan.data.ai.computer.PartnerComputers
import me.ayuilos.miffan.data.ai.computer.RemoteComputerRegistry
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.RemoteScreenAuth
import me.ayuilos.miffan.data.db.entity.RemoteScreenEndpoint
import me.ayuilos.miffan.data.db.entity.SshKeyEntity
import me.ayuilos.miffan.data.remote.NearbyComputer
import me.ayuilos.miffan.data.remote.NearbyNameSource
import me.ayuilos.miffan.data.remote.NearbyNetwork
import me.ayuilos.miffan.data.remote.NearbySystem
import me.ayuilos.miffan.data.remote.nearbyBannerSystem
import me.ayuilos.miffan.data.remote.probeNearbySsh
import me.ayuilos.miffan.data.repository.RemoteCommandOutcome
import me.ayuilos.miffan.data.repository.RemoteMachineProbe
import me.ayuilos.miffan.data.repository.RemoteScreenRepository
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteStreamSetupController
import me.ayuilos.miffan.data.repository.RemoteSunshinePermission
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.rerere.workspace.RemoteAuthentication
import me.rerere.workspace.RemoteConnectException
import me.rerere.workspace.RemotePasswordSignInFailure
import me.rerere.workspace.RemoteHostKey
import me.rerere.workspace.RemotePlatform
import kotlin.uuid.Uuid

/** Screens of "connect a computer"; the user moves between them, [ComputerSetupState.history] remembers the way back. */
enum class ComputerSetupStep {
    /** Computers on this Wi-Fi (and, on request, on Tailscale), plus the ones Miffan already knows. */
    FIND,
    /** Type an address; also the connection settings of a saved computer. */
    MANUAL,
    /** Account and password of a Mac or Linux computer. */
    SIGN_IN,
    /** Run one command on the computer so Miffan's key may sign in. */
    KEY,
    /** Run the setup script on a Windows computer, which also authorizes Miffan's key. */
    WINDOWS,
    /** Only when a saved computer's identity changed: a strong warning before trusting the new one. */
    VERIFY,
    /** Everything the partner needs, checked and fixed in one list. */
    READY,
    /** Who gets the computer, under the live screen. */
    SHARE,
    DONE,
}

/** What binding a computer would replace: the phone's own workspace, or another computer. */
sealed interface ReplacedBinding {
    data object Phone : ReplacedBinding
    data class Computer(val name: String) : ReplacedBinding
}

/** Long-running work, shown as progress; at most one runs at a time. */
enum class ComputerSetupTask {
    CREATING_KEY, LOOKING_UP, CONNECTING, VERIFYING, PREPARING, INSTALLING, SAVING, CHECKING, BINDING, OPENING_SETTINGS,
}

/** Reads the Mac's grants every few seconds for about two minutes after asking for them. */
private const val PERMISSION_WATCH_INTERVAL_MS = 3_000L
private const val PERMISSION_WATCH_ROUNDS = 40

/** Tries with the key when the user comes back from the computer; few, so sshd's failure limits stay far away. */
private const val QUIET_KEY_TRIES = 4

/** Sharing in System Settings; the second form is for macOS 12 and older. */
private const val MAC_SHARING_SETTINGS_COMMAND =
    "open 'x-apple.systempreferences:com.apple.Sharing-Settings.extension' 2>/dev/null || open /System/Library/PreferencePanes/SharingPref.prefPane"

/** The computer being connected, as found nearby or typed in, before or after Miffan saved it. */
data class SetupTarget(
    val name: String,
    val host: String,
    val port: Int,
    val system: NearbySystem = NearbySystem.UNKNOWN,
    val network: NearbyNetwork = NearbyNetwork.LOCAL,
    /** The name came from a network lookup or the address; the computer's own name replaces it after sign-in. */
    val nameGuessed: Boolean = false,
)

data class ComputerSetupState(
    val step: ComputerSetupStep = ComputerSetupStep.FIND,
    /** Screens to go back to, most recent last; empty means back leaves the setup. */
    val history: List<ComputerSetupStep> = emptyList(),
    val task: ComputerSetupTask? = null,
    val error: Throwable? = null,
    val target: SetupTarget? = null,
    /** The account typed so far, carried between the sign-in screens. */
    val username: String = "",
    val hostId: String? = null,
    /** Shown on [ComputerSetupStep.VERIFY]: the computer's new identity. */
    val hostKey: RemoteHostKey? = null,
    val workspaceId: String? = null,
    val probe: RemoteMachineProbe? = null,
    /** The host identity [probe] was read from; high-performance setup refuses a different one. */
    val hostRevision: String? = null,
    val install: RemoteCommandOutcome? = null,
    /** macOS grants of the remote cua-driver; null on Linux or before checking. */
    val permissions: ComputerPermissions? = null,
    /** The account Screen Sharing signs in with on a Mac, once saved. */
    val macAccount: String? = null,
    /** What the partner saw in the read-only check. */
    val partnerView: Bitmap? = null,
    val partnerChecked: Boolean = false,
    /** Editing a saved computer's connection settings. */
    val editing: Boolean = false,
    /** Windows only: Sunshine is installed, running, encrypted and paired for [hostRevision]. */
    val streamReady: Boolean = false,
    /** The user's choice of whether this computer has a screen to use; null follows what the check found. */
    val wantsScreen: Boolean? = null,
    /** The computer does not take passwords: [ComputerSetupStep.KEY] says so and is the only way in. */
    val passwordRefused: Boolean = false,
    /** [ComputerSetupStep.KEY]: the computer did not accept Miffan's key (yet). */
    val keyRefused: Boolean = false,
    /** [ComputerSetupStep.MANUAL]: nothing answered as a computer with remote login at that address. */
    val unreachable: Boolean = false,
) {
    val busy: Boolean get() = task != null
    /** The computer's system: what the check found, else what finding it suggested. */
    val system: NearbySystem get() = when (probe?.os) {
        "macos" -> NearbySystem.MAC
        "windows" -> NearbySystem.WINDOWS
        "linux" -> NearbySystem.LINUX
        else -> target?.system ?: NearbySystem.UNKNOWN
    }
    val isMac: Boolean get() = probe?.os?.let { it == "macos" } ?: (target?.system == NearbySystem.MAC)
    val isWindows: Boolean get() = probe?.os?.let { it == "windows" } ?: (target?.system == NearbySystem.WINDOWS)

    val sessionReady: Boolean get() = probe?.session?.present == true
    /**
     * GNOME and KDE need their RDP server installed (Miffan starts it); other desktops need a VNC
     * server, and macOS Screen Sharing must also be switched on (the helper cannot start it).
     */
    val screenServiceReady: Boolean get() = probe?.let { probe ->
        // Sunshine is the only screen a Windows computer has.
        if (probe.os == "windows") return@let streamReady
        if (probe.usesRdp) return@let probe.rdpServer != null
        val vnc = probe.vnc
        vnc.server != null && vnc.server != "none" && (vnc.server != "macos-screen-sharing" || vnc.running)
    } == true
    val cuaReady: Boolean get() = probe?.cua?.ok == true

    /**
     * Whether the partner gets the screen. Off, the computer is used through commands only, as on a
     * server or NAS without a display, and the screen checks are skipped. Until the user chooses, it
     * is on unless the check found no desktop.
     */
    val usesScreen: Boolean get() = wantsScreen ?: probe?.session?.present ?: true

    /** Desktop, screen service and cua-driver: what the probe can see. */
    val machineReady: Boolean get() = sessionReady && screenServiceReady && cuaReady
    val macAccountReady: Boolean get() = !isMac || macAccount != null
    val grantsReady: Boolean get() = !isMac || permissions?.complete == true

    /** Everything [ComputerSetupStep.READY] lists is done, or there is nothing to check. */
    val ready: Boolean get() = probe != null && (!usesScreen || (machineReady && macAccountReady && grantsReady))
}

/**
 * Where the setup starts. With [assistantId] it ends by offering the computer to that partner; without
 * it (opened from "my computers") any partners, or none. [hostId] starts on that saved computer: with
 * [edit] at its connection settings, otherwise by connecting and checking it.
 */
data class ComputerSetupArgs(
    val assistantId: Uuid? = null,
    val hostId: String? = null,
    val edit: Boolean = false,
    /** The user turned the screen on for [hostId]: the check starts with it on even without a desktop found. */
    val screen: Boolean = false,
)

/**
 * Guides the user through connecting a computer and giving it to partners. The UI shows [state] and
 * collects input; anything that runs software on the computer, stores a credential or changes a
 * partner happens here and only on an explicit user action. A computer's identity is trusted the
 * first time Miffan connects to it; a changed identity stops at [ComputerSetupStep.VERIFY].
 */
class ComputerSetupVM(
    private val args: ComputerSetupArgs,
    private val workspaces: WorkspaceRepository,
    private val screens: RemoteScreenRepository,
    private val registry: RemoteComputerRegistry,
    private val settingsStore: SettingsStore,
    private val partnerComputers: PartnerComputers,
) : ViewModel() {
    /** The partner this setup is for; null when opened from "my computers". */
    val assistantId: Uuid? get() = args.assistantId

    private val _state = MutableStateFlow(
        if (args.hostId != null && args.edit) ComputerSetupState(
            step = ComputerSetupStep.MANUAL, hostId = args.hostId, editing = true,
        ) else ComputerSetupState(),
    )
    val state: StateFlow<ComputerSetupState> = _state.asStateFlow()

    /** Computers Miffan already knows. */
    val hosts: StateFlow<List<RemoteHostEntity>> = workspaces.listHostsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Miffan's own key, made on first need; the key and Windows screens show its public half. */
    private val _appKey = MutableStateFlow<SshKeyEntity?>(null)
    val appKey: StateFlow<SshKeyEntity?> = _appKey.asStateFlow()

    /**
     * What the partner is bound to now, when it is not the workspace being set up; binding replaces
     * it, so the UI says so before the user confirms.
     */
    val replacedBinding: StateFlow<ReplacedBinding?> = combine(settingsStore.settingsFlow, workspaces.listFlow(), hosts, _state) { settings, list, hostList, state ->
        val bound = settings.getAssistantById(assistantId ?: return@combine null)?.workspaceId?.toString() ?: return@combine null
        if (bound == state.workspaceId) return@combine null
        val workspace = list.find { it.id == bound } ?: return@combine null
        if (!workspace.isRemote) ReplacedBinding.Phone
        else ReplacedBinding.Computer(hostList.find { it.id == workspace.remoteHostId }?.name ?: workspace.name)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Partners already bound to a workspace of the computer being set up. */
    val boundPartnerIds: StateFlow<Set<Uuid>> = combine(
        settingsStore.settingsFlow, workspaces.listFlow(), _state,
    ) { settings, list, state ->
        val hostId = state.hostId ?: return@combine emptySet()
        val workspaceIds = list.filter { it.isRemote && it.remoteHostId == hostId }.map { it.id }.toSet()
        settings.assistants.filter { it.workspaceId?.toString() in workspaceIds }.map { it.id }.toSet()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private var job: Job? = null

    /**
     * The password this setup signed in with, kept in memory only: a Mac's Screen Sharing signs in
     * with the same account, so the setup does not ask again.
     */
    private var signInPassword: String? = null
    /** Miffan's key, authorized through the session the password opens; see [switchToKey]. */
    private var keyToAuthorize: String? = null
    private var permissionWatch: Job? = null
    private var quietKeyTries = 0
    /** A computer saved in this setup under a guessed name, renamed once it says what it is called. */
    private var guessedNameHostId: String? = null

    private val streamSetup = RemoteStreamSetupController(screens, viewModelScope)
    /** Optional high-performance mode for the computer being set up; see [RemoteStreamSetupController]. */
    val streamSetups = streamSetup.states

    init {
        // Windows readiness depends on Sunshine, which the stream controller tracks per host and revision.
        viewModelScope.launch {
            streamSetup.states.collect { setups ->
                _state.update { current ->
                    val setup = current.hostId?.let { setups[it] }?.takeIf { it.connectionRevision == current.hostRevision }
                    current.copy(streamReady = setup?.streamReady == true)
                }
            }
        }
        when {
            args.hostId != null && args.edit -> viewModelScope.launch {
                workspaces.getHostById(args.hostId)?.let { host -> _state.update { it.copy(target = host.target(), username = host.username) } }
            }
            args.hostId != null -> chooseHost(args.hostId)
        }
    }

    private fun withProbedHost(action: (hostId: String, revision: String) -> Unit) {
        val state = _state.value
        action(state.hostId ?: return, state.hostRevision ?: return)
    }
    fun checkStream() = withProbedHost(streamSetup::check)
    fun pairStream() = withProbedHost(streamSetup::pair)
    fun cancelStreamPairing() = streamSetup.cancelPairing()
    /** Called only from the confirmation that shows exactly what changes on the computer. */
    fun enforceStreamEncryption() = withProbedHost(streamSetup::enforceEncryption)
    fun enableStream() = withProbedHost { hostId, _ -> streamSetup.setEnabled(hostId, true) }
    fun startSunshine() = withProbedHost(streamSetup::start)
    fun openSunshineSettings(permission: RemoteSunshinePermission) =
        withProbedHost { hostId, revision -> streamSetup.openPermissionSettings(hostId, revision, permission) }

    /** The exact command [installCuaDriver] runs, for the confirmation dialog. */
    fun cuaInstallCommand(): String = RemoteScreenRepository.cuaDriverCommand(upgradePath(),
        if (_state.value.probe?.os == "windows") RemotePlatform.WINDOWS else RemotePlatform.POSIX)

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Back one screen; false when there is none and the setup should close. Saved computers stay saved. */
    fun back(): Boolean {
        val state = _state.value
        if (state.busy || state.history.isEmpty()) return false
        _state.update { it.copy(step = it.history.last(), history = it.history.dropLast(1), error = null, keyRefused = false, unreachable = false) }
        return true
    }

    private fun go(step: ComputerSetupStep, update: (ComputerSetupState) -> ComputerSetupState = { it }) = _state.update {
        val next = update(it)
        if (it.step == step) next.copy(error = null) else next.copy(step = step, history = next.history + it.step, error = null)
    }

    // ---- Finding the computer ----

    /** A computer found nearby: a saved one is checked again, a new one asks for the account. */
    fun pickNearby(computer: NearbyComputer) {
        if (_state.value.busy) return
        hosts.value.savedMatch(computer)?.let {
            chooseHost(it.id, fromList = true)
            return
        }
        val target = SetupTarget(computer.name ?: defaultComputerName(computer.host), computer.host, computer.port,
            computer.system, computer.network, nameGuessed = computer.nameSource != NearbyNameSource.MDNS)
        go(if (computer.system == NearbySystem.WINDOWS) ComputerSetupStep.WINDOWS else ComputerSetupStep.SIGN_IN) {
            // Another computer's account is no guess for this one.
            val sameComputer = it.hostId == null && it.target?.host == computer.host
            it.copy(target = target, hostId = null, passwordRefused = false, keyRefused = false,
                username = if (sameComputer) it.username else "")
        }
    }

    fun addManually() = go(ComputerSetupStep.MANUAL) { it.copy(unreachable = false, username = if (it.hostId == null) it.username else "") }

    /**
     * An address typed in: asks the port whether it is a computer with remote login, without
     * signing in, and continues with the screen that fits its system.
     */
    fun submitManual(address: String, port: Int, username: String, name: String) = run(ComputerSetupTask.LOOKING_UP) {
        val banner = probeNearbySsh(address, port, connectTimeoutMillis = 4_000, bannerTimeoutMillis = 4_000)
        if (banner == null) {
            _state.update { it.copy(unreachable = true, target = SetupTarget(name.ifBlank { defaultComputerName(address) }, address, port)) }
            return@run
        }
        val system = nearbyBannerSystem(banner).system
        val target = SetupTarget(name.ifBlank { defaultComputerName(address) }, address, port, system, nameGuessed = name.isBlank())
        go(if (system == NearbySystem.WINDOWS) ComputerSetupStep.WINDOWS else ComputerSetupStep.SIGN_IN) {
            it.copy(target = target, username = username.ifBlank { it.username }, unreachable = false)
        }
    }

    /** Nothing answered: a Windows computer gets remote login from the setup script. */
    fun continueAsWindows() = go(ComputerSetupStep.WINDOWS) {
        it.copy(target = it.target?.copy(system = NearbySystem.WINDOWS), unreachable = false)
    }

    /** Saves a saved computer's address, port, account and name, then checks it again. */
    fun saveConnection(name: String, address: String, port: Int, username: String) = run(ComputerSetupTask.SAVING) {
        val hostId = requireNotNull(_state.value.hostId)
        check(workspaces.updateHost(hostId, name.ifBlank { defaultComputerName(address) }, address, port, username)) { "Computer not found" }
        if (name.isNotBlank()) guessedNameHostId = null
        connect(requireNotNull(workspaces.getHostById(hostId)) { "Computer not found" })
    }

    /** Connection settings: sign in again, with a password or the key. */
    fun changeSignIn() {
        val state = _state.value
        val target = state.target ?: return
        go(if (target.system == NearbySystem.WINDOWS) ComputerSetupStep.WINDOWS else ComputerSetupStep.SIGN_IN)
    }

    /** A saved computer: connect and check it. */
    fun chooseHost(hostId: String, fromList: Boolean = false) = run(ComputerSetupTask.CONNECTING) {
        val host = requireNotNull(workspaces.getHostById(hostId)) { "Computer not found" }
        _state.update {
            // Checking a saved computer opens straight on its checks, which show the connection.
            val base = if (fromList) it else ComputerSetupState(step = ComputerSetupStep.READY)
            base.copy(hostId = host.id, target = host.target(), username = host.username, task = ComputerSetupTask.CONNECTING,
                wantsScreen = true.takeIf { args.screen && hostId == args.hostId })
        }
        connect(host)
    }

    // ---- Signing in ----

    fun setUsername(username: String) = _state.update { it.copy(username = username) }

    /** Sign in with Miffan's key instead of a password. */
    fun useKey() = go(ComputerSetupStep.KEY) { it.copy(passwordRefused = false, keyRefused = false) }

    /** Leave this computer and look for another; anything already saved stays saved. */
    fun findAnother() {
        if (_state.value.busy) return
        signInPassword = null
        keyToAuthorize = null
        quietKeyTries = 0
        _state.value = ComputerSetupState()
    }

    /** Windows set up without a found address: what the script printed. */
    fun setWindowsAddress(address: String) {
        val parsed = parseSshTarget(address)
        val host = parsed?.host ?: address
        _state.update {
            it.copy(target = SetupTarget(defaultComputerName(host), host, parsed?.port ?: 22, NearbySystem.WINDOWS, nameGuessed = true),
                username = parsed?.username ?: it.username)
        }
    }

    /** Makes Miffan's key if there is none yet; reuses the first one otherwise. */
    fun ensureAppKey() {
        if (_appKey.value != null) return
        viewModelScope.launch {
            try {
                _appKey.value = appKeyId().let { workspaces.getSshKeyById(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(error = error) }
            }
        }
    }

    /** Signs in once with the password, puts Miffan's key on the computer and signs in with that from then on. */
    fun signIn(username: String, password: String) = run(ComputerSetupTask.CONNECTING) {
        val keyId = appKeyId()
        signInPassword = password
        keyToAuthorize = keyId
        _state.update { it.copy(username = username) }
        connect(saveHost(username, RemoteAuthentication.Password(password), sshKeyId = null))
    }

    /**
     * The user ran the key command (or the Windows script): signs in with Miffan's key. [quiet] is
     * the automatic try when the user comes back to the app; it shows no progress and keeps quiet
     * about a refusal.
     */
    fun signInWithKey(username: String, quiet: Boolean = false) {
        if (quiet) {
            val state = _state.value
            if (state.busy || quietKeyTries >= QUIET_KEY_TRIES || username.isBlank() ||
                state.step !in setOf(ComputerSetupStep.KEY, ComputerSetupStep.WINDOWS)) return
            quietKeyTries++
        }
        run(ComputerSetupTask.CONNECTING) {
            signInPassword = null
            keyToAuthorize = null
            _state.update { it.copy(username = username, keyRefused = it.keyRefused && quiet) }
            try {
                connect(saveHost(username, authentication = null, sshKeyId = appKeyId()), quiet = quiet)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!quiet) throw error
            }
        }
    }

    /** Trusts the changed identity the user was warned about; refused if the live key differs from it. */
    fun trustAndConnect() = run(ComputerSetupTask.VERIFYING) {
        val host = currentHost()
        val key = requireNotNull(_state.value.hostKey) { "No fingerprint to trust" }
        check(workspaces.trustHostKey(host.id, key.sha256Fingerprint)) { "Computer not found" }
        connectAndProbe(host.id)
    }

    /** Before the screens that sign in; same key as the command shows. */
    private suspend fun appKeyId(): String {
        _appKey.value?.let { return it.id }
        val existing = workspaces.listSshKeysFlow().first()
        val key = existing.firstOrNull() ?: run {
            val taken = existing.map { it.name }.toSet()
            val name = generateSequence(1) { it + 1 }.map { if (it == 1) "Miffan" else "Miffan $it" }.first { it !in taken }
            workspaces.generateSshKey(name)
        }
        _appKey.value = key
        return key.id
    }

    /**
     * The computer being set up, saved with this sign-in: the one already saved in this setup or
     * with the same address and account, else a new one.
     */
    private suspend fun saveHost(username: String, authentication: RemoteAuthentication?, sshKeyId: String?): RemoteHostEntity {
        val state = _state.value
        val target = requireNotNull(state.target) { "No computer chosen" }
        val existing = state.hostId?.let { workspaces.getHostById(it) }
            ?: hosts.value.find { it.host == target.host && it.port == target.port && it.username == username }
        val host = if (existing != null) {
            check(workspaces.updateHost(existing.id, existing.name, existing.host, existing.port, username,
                authentication = authentication, sshKeyId = sshKeyId)) { "Computer not found" }
            requireNotNull(workspaces.getHostById(existing.id)) { "Computer not found" }
        } else {
            workspaces.createHost(target.name, target.host, target.port, username, authentication = authentication, sshKeyId = sshKeyId)
                .also { if (target.nameGuessed) guessedNameHostId = it.id }
        }
        _state.update { it.copy(hostId = host.id) }
        return host
    }

    /** Trusts a computer's identity the first time, then connects; see [connectAndProbe]. */
    private suspend fun connect(host: RemoteHostEntity, quiet: Boolean = false) {
        if (host.trustedHostKeySha256 == null) {
            val key = workspaces.discoverHostKey(host.id)
            check(workspaces.trustHostKey(host.id, key.sha256Fingerprint)) { "Computer not found" }
        }
        connectAndProbe(host.id, quiet)
    }

    private suspend fun connectAndProbe(hostId: String, quiet: Boolean = false) {
        if (!quiet) _state.update { it.copy(task = ComputerSetupTask.CONNECTING) }
        try {
            check(workspaces.testHost(hostId)) { "Connection failed" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val chain = generateSequence<Throwable>(error) { it.cause }
            val connect = chain.firstNotNullOfOrNull { it as? RemoteConnectException }
            when {
                chain.any { it is JSchHostKeyException } -> {
                    // A saved computer answered with another identity: warn, never trust silently.
                    val key = workspaces.discoverHostKey(hostId)
                    go(ComputerSetupStep.VERIFY) { it.copy(hostKey = key) }
                }
                connect?.passwordSignIn == RemotePasswordSignInFailure.NOT_OFFERED ->
                    go(ComputerSetupStep.KEY) { it.copy(passwordRefused = true, keyRefused = false) }
                // A saved computer that no longer accepts its sign-in (a changed password, a wrong
                // account) asks to sign in again instead of stopping at an error.
                !quiet && _state.value.step == ComputerSetupStep.FIND && connect != null &&
                    (connect.passwordSignIn != null || connect.message?.startsWith("Auth ") == true) -> {
                    val keyed = workspaces.getHostById(hostId)?.sshKeyId != null
                    if (keyed) go(ComputerSetupStep.KEY) { it.copy(passwordRefused = false, keyRefused = true) }
                    else {
                        go(ComputerSetupStep.SIGN_IN) { it.copy(passwordRefused = false, keyRefused = false) }
                        _state.update { it.copy(error = error) }
                    }
                }
                _state.value.step in setOf(ComputerSetupStep.KEY, ComputerSetupStep.WINDOWS) &&
                    connect != null && connect.passwordSignIn == null && connect.message?.startsWith("Auth ") == true ->
                    _state.update { it.copy(keyRefused = !quiet || it.keyRefused) }
                quiet -> Unit
                else -> throw error
            }
            return
        }
        val workspaceId = partnerComputers.ensureComputerWorkspace(hostId)
        keyToAuthorize?.let { keyId ->
            keyToAuthorize = null
            switchToKey(hostId, workspaceId, keyId)
        }
        // A screen already in use stays on even if the desktop is signed out right now.
        val host = workspaces.getHostById(hostId)
        val macAccount = savedMacAccount(hostId)
        _state.update {
            // Back from the checks leads to choosing another computer, never to signing in again.
            it.copy(step = ComputerSetupStep.READY,
                history = listOfNotNull(ComputerSetupStep.FIND.takeIf { _ -> it.step == ComputerSetupStep.FIND || ComputerSetupStep.FIND in it.history }),
                error = null, workspaceId = workspaceId, passwordRefused = false, keyRefused = false, macAccount = macAccount,
                target = host?.target() ?: it.target, wantsScreen = it.wantsScreen ?: true.takeIf { host?.screenEnabled == true })
        }
        probe()
    }

    /**
     * Password sign-in: authorizes Miffan's key through the session the password opened, then signs
     * in with the key, which also deletes the saved password. Anything going wrong keeps the
     * password, which is known to work.
     */
    private suspend fun switchToKey(hostId: String, workspaceId: String, keyId: String) {
        val password = signInPassword ?: return
        val host = workspaces.getHostById(hostId) ?: return
        var keyed = false
        val switched = try {
            val key = workspaces.getSshKeyById(keyId) ?: return
            val result = workspaces.executeCommand(workspaceId, authorizeKeyCommand(key.publicKey), timeoutMillis = 20_000)
            if (result.exitCode != 0 || result.timedOut) return
            check(workspaces.updateHost(hostId, host.name, host.host, host.port, host.username, sshKeyId = keyId))
            keyed = true
            workspaces.testHost(hostId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (switched || !keyed) return
        workspaces.updateHost(hostId, host.name, host.host, host.port, host.username,
            authentication = RemoteAuthentication.Password(password))
        check(workspaces.testHost(hostId)) { "Connection failed" }
    }

    // ---- Getting ready ----

    fun reprobe() = run(ComputerSetupTask.PREPARING) { probe() }

    /**
     * Checks again without showing progress, while the user fixes something at the computer; the
     * result is dropped if the user started anything meanwhile. Failures are ignored: the visible
     * check reports them.
     */
    fun quietRecheck() {
        val start = _state.value
        if (start.busy || start.step != ComputerSetupStep.READY || start.ready || !start.usesScreen) return
        // Windows' screen is Sunshine, which its own setup flow checks; the probe would not see it change.
        if (start.sessionReady && start.cuaReady && (start.isWindows || start.screenServiceReady)) return
        val hostId = start.hostId ?: return
        viewModelScope.launch {
            val host = workspaces.getHostById(hostId) ?: return@launch
            val probe = try {
                screens.probeHost(host.id, host.connectionRevision)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            }
            var changed = false
            _state.update {
                if (it.busy || it.step != ComputerSetupStep.READY || it.hostId != hostId) it
                else it.copy(probe = probe, hostRevision = host.connectionRevision).also { changed = true }
            }
            if (changed) afterProbe()
        }
    }

    fun setWantsScreen(wants: Boolean) {
        if (_state.value.busy) return
        _state.update { it.copy(wantsScreen = wants, error = null) }
        if (wants) viewModelScope.launch { afterProbe() }
    }

    /** Installs or upgrades cua-driver; call only after the user confirmed [cuaInstallCommand]. */
    fun installCuaDriver() = run(ComputerSetupTask.INSTALLING) {
        val host = currentHost()
        val outcome = screens.installCuaDriverOnHost(host.id, host.connectionRevision, upgradePath())
        _state.update { it.copy(install = outcome) }
        probe()
    }

    /** macOS: opens Sharing in System Settings on the Mac, where Screen Sharing is switched on. */
    fun openMacSharingSettings() = run(ComputerSetupTask.OPENING_SETTINGS) {
        val workspaceId = requireNotNull(_state.value.workspaceId)
        val result = workspaces.executeCommand(workspaceId, MAC_SHARING_SETTINGS_COMMAND, timeoutMillis = 15_000)
        check(result.exitCode == 0 && !result.timedOut) { result.stderr.ifBlank { "Could not open System Settings" } }
    }

    /** Saves the macOS account Screen Sharing signs in with. */
    fun saveMacAccount(username: String, password: String) = run(ComputerSetupTask.SAVING) {
        val host = currentHost()
        screens.updateConfig(host.id, enabled = true, endpoint = RemoteScreenEndpoint.Helper,
            auth = RemoteScreenAuth.MACOS_ACCOUNT, username = username.ifBlank { host.username }, password = password)
        _state.update { it.copy(macAccount = username.ifBlank { host.username }) }
        afterProbe()
    }

    /**
     * macOS: raise the system permission dialogs on the Mac for missing grants, then keep reading
     * the grants (read-only) while the user answers them.
     */
    fun requestMacPermissions() = run(ComputerSetupTask.CHECKING) {
        val workspaceId = requireNotNull(_state.value.workspaceId)
        _state.update { it.copy(permissions = registry.permissions(workspaceId, prompt = true)) }
        if (_state.value.permissions?.complete == false) watchMacPermissions(workspaceId)
    }

    private fun watchMacPermissions(workspaceId: String) {
        permissionWatch?.cancel()
        permissionWatch = viewModelScope.launch {
            repeat(PERMISSION_WATCH_ROUNDS) {
                delay(PERMISSION_WATCH_INTERVAL_MS)
                val current = _state.value
                if (current.step != ComputerSetupStep.READY || current.workspaceId != workspaceId) return@launch
                if (current.busy) return@repeat
                val grants = try {
                    registry.permissions(workspaceId, prompt = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@repeat
                } ?: return@launch
                _state.update { if (it.workspaceId == workspaceId) it.copy(permissions = grants) else it }
                if (grants.complete) return@launch
            }
        }
    }

    /** Leaves [ComputerSetupStep.READY] once everything is in place. */
    fun continueToShare() = run(ComputerSetupTask.SAVING) {
        check(_state.value.ready) { "The computer is not ready yet" }
        // Saved screen settings stay, so turning the screen back on only needs the check.
        if (!_state.value.usesScreen) screens.setScreenEnabled(currentHost().id, false)
        go(ComputerSetupStep.SHARE)
    }

    /**
     * Once the machine is ready, saves what the screen signs in with (on a Mac the saved account or
     * the password this setup signed in with) and reads the Mac's grants, so the list fills itself.
     */
    private suspend fun afterProbe() {
        val state = _state.value
        if (!state.usesScreen || !state.machineReady) return
        val host = workspaces.getHostById(state.hostId ?: return) ?: return
        if (state.isMac) {
            if (state.macAccount == null) {
                val password = signInPassword ?: return
                screens.updateConfig(host.id, enabled = true, endpoint = RemoteScreenEndpoint.Helper,
                    auth = RemoteScreenAuth.MACOS_ACCOUNT, username = host.username, password = password)
                _state.update { it.copy(macAccount = host.username) }
            } else if (!host.screenEnabled) {
                screens.setScreenEnabled(host.id, true)
            }
            if (_state.value.permissions?.complete != true) {
                val grants = runCatching { registry.permissions(requireNotNull(state.workspaceId), prompt = false) }.getOrNull()
                _state.update { it.copy(permissions = grants ?: it.permissions) }
            }
        } else if (!host.screenEnabled) {
            // A screen the user already set up in professional mode keeps its settings.
            screens.updateConfig(host.id, enabled = true, endpoint = RemoteScreenEndpoint.Helper,
                auth = RemoteScreenAuth.NONE, username = "", password = null)
        }
    }

    // ---- Sharing ----

    /** The partner's read-only look: one desktop screenshot. Nothing on the computer changes. */
    fun checkPartner() = run(ComputerSetupTask.CHECKING) {
        val workspaceId = requireNotNull(_state.value.workspaceId)
        _state.update { it.copy(partnerChecked = false) }
        val png = registry.desktopScreenshot(workspaceId) ?: error("cua-driver returned no screenshot")
        val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(png, 0, png.size) }
            ?: error("Unreadable screenshot")
        _state.update { it.copy(partnerView = bitmap, partnerChecked = true) }
    }

    /**
     * Binds the partners to this computer with approval kept on. The UI shows what that means
     * (screenshots go to the model provider) before calling this.
     */
    fun bind(assistantIds: Set<Uuid>): Unit = run(ComputerSetupTask.BINDING) {
        partnerComputers.bind(requireNotNull(_state.value.hostId), assistantIds)
        _state.update { it.copy(step = ComputerSetupStep.DONE, history = emptyList()) }
    }

    // ---- Helpers ----

    private suspend fun savedMacAccount(hostId: String): String? = try {
        val config = screens.getConfig(hostId)
        config?.username?.ifBlank { null }?.takeIf { config.auth == RemoteScreenAuth.MACOS_ACCOUNT && config.hasPassword }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun probe() {
        _state.update { it.copy(task = ComputerSetupTask.PREPARING) }
        val host = currentHost()
        val probe = screens.probeHost(host.id, host.connectionRevision)
        _state.update { it.copy(probe = probe, hostRevision = host.connectionRevision) }
        adoptComputerName(host, probe.hostname)
        // Sunshine is optional on Linux and macOS and required on Windows; its check runs beside the others.
        if (probe.os in setOf("linux", "macos", "windows")) streamSetup.check(host.id, host.connectionRevision)
        afterProbe()
    }

    /**
     * A computer saved here under a name guessed from the network (a DNS alias, an address) takes the
     * name it calls itself, and so does its partner workspace if that still carries the guess.
     */
    private suspend fun adoptComputerName(host: RemoteHostEntity, hostname: String?) {
        val name = hostname?.trim()?.takeIf { it.isNotEmpty() }
        if (host.id == guessedNameHostId && name != null && name != host.name) {
            guessedNameHostId = null
            workspaces.updateHost(host.id, name, host.host, host.port, host.username)
            _state.value.workspaceId?.let { id ->
                val workspace = workspaces.getById(id)
                if (workspace?.name == host.name && !workspaces.isNameTaken(name, id)) workspaces.rename(id, name)
            }
        }
        // The probe also recorded the computer's system.
        workspaces.getHostById(host.id)?.let { saved -> _state.update { it.copy(target = saved.target()) } }
    }

    private fun RemoteHostEntity.target() = SetupTarget(name, host, port, system())

    private fun upgradePath(): String? = _state.value.probe?.cua?.takeIf { !it.ok }?.path

    private suspend fun currentHost(): RemoteHostEntity =
        requireNotNull(_state.value.hostId?.let { workspaces.getHostById(it) }) { "Computer not found" }

    private fun run(task: ComputerSetupTask, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(task = task, error = null) }
        job = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update { it.copy(error = error) }
            } finally {
                _state.update { it.copy(task = null) }
            }
        }
    }
}

/** The saved computer [computer] is: the same address, or the same identity reached over another network. */
internal fun List<RemoteHostEntity>.savedMatch(computer: NearbyComputer): RemoteHostEntity? =
    find { it.host == computer.host && it.port == computer.port }
        ?: computer.hostKey?.let { key -> find { it.trustedHostKeySha256 == key } }

/** A saved computer's system, from what its screen check or connection found. */
internal fun RemoteHostEntity.system(): NearbySystem = when {
    screenPlatform == "macos" -> NearbySystem.MAC
    screenPlatform == "linux" -> NearbySystem.LINUX
    screenPlatform == "windows" || platform == RemotePlatform.WINDOWS.storageName -> NearbySystem.WINDOWS
    else -> NearbySystem.UNKNOWN
}
