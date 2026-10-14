package me.ayuilos.miffan.ui.im.computer

import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.rdpServer
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.usesRdp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

/** Steps of "connect a computer for the partner", in order. */
enum class ComputerSetupStep {
    /** Pick a computer Miffan already knows, or add a new one. */
    CHOOSE,
    /** Address, account and sign-in method of a new computer. */
    ADDRESS,
    /** Confirm the computer's SSH host key fingerprint. */
    VERIFY,
    /** Desktop session, screen service and cua-driver checks, with fixes. */
    PREPARE,
    /** macOS only: the account Screen Sharing signs in with. */
    MAC_ACCOUNT,
    /** Live screen preview, macOS permissions and a read-only look by the partner. */
    TEST,
    /** Give this computer to the partner. */
    BIND,
    DONE,
}

/** Long-running work, shown as progress; at most one runs at a time. */
/** What binding a computer would replace: the phone's own workspace, or another computer. */
sealed interface ReplacedBinding {
    data object Phone : ReplacedBinding
    data class Computer(val name: String) : ReplacedBinding
}

enum class ComputerSetupTask {
    CREATING_KEY, CONNECTING, READING_FINGERPRINT, VERIFYING, PREPARING, INSTALLING, SAVING, CHECKING, BINDING, OPENING_SETTINGS,
}

/** Reads the Mac's grants every few seconds for about two minutes after asking for them. */
private const val PERMISSION_WATCH_INTERVAL_MS = 3_000L
private const val PERMISSION_WATCH_ROUNDS = 40

/** Sharing in System Settings; the second form is for macOS 12 and older. */
private const val MAC_SHARING_SETTINGS_COMMAND =
    "open 'x-apple.systempreferences:com.apple.Sharing-Settings.extension' 2>/dev/null || open /System/Library/PreferencePanes/SharingPref.prefPane"

sealed interface ComputerSetupAuth {
    /**
     * With [keyId], the password only signs in once: the setup authorizes that Miffan key with it and
     * signs in with the key from then on.
     */
    data class Password(val value: String, val keyId: String? = null) : ComputerSetupAuth {
        override fun toString(): String = "Password([redacted], keyId=$keyId)"
    }

    /** A key generated in Miffan; the user adds its public half to the computer first. */
    data class AppKey(val keyId: String) : ComputerSetupAuth
}

data class ComputerSetupState(
    val step: ComputerSetupStep = ComputerSetupStep.CHOOSE,
    val task: ComputerSetupTask? = null,
    val error: Throwable? = null,
    val hostId: String? = null,
    /** Shown on [ComputerSetupStep.VERIFY]; trusting it requires the same fingerprint live. */
    val hostKey: RemoteHostKey? = null,
    /** True when this host already trusted a different key: show a strong warning. */
    val hostKeyChanged: Boolean = false,
    val workspaceId: String? = null,
    val probe: RemoteMachineProbe? = null,
    /** The host identity [probe] was read from; high-performance setup refuses a different one. */
    val hostRevision: String? = null,
    val install: RemoteCommandOutcome? = null,
    /** macOS grants of the remote cua-driver; null on Linux or before checking. */
    val permissions: ComputerPermissions? = null,
    /** What the partner saw in the read-only check. */
    val partnerView: Bitmap? = null,
    val partnerChecked: Boolean = false,
    /** The address step edits this host's saved connection details. */
    val editing: Boolean = false,
    /** Windows only: Sunshine is installed, running, encrypted and paired for [hostRevision]. */
    val streamReady: Boolean = false,
    /** The user said the computer runs Windows (or it was detected before); only changes instructions. */
    val expectWindows: Boolean = false,
    /** The user's choice of whether this computer has a screen to use; null follows what the check found. */
    val wantsScreen: Boolean? = null,
    /**
     * The computer [hostId] does not take passwords: the address step switches it to Miffan's key
     * instead of adding another computer.
     */
    val passwordRefused: Boolean = false,
) {
    val busy: Boolean get() = task != null
    val isMac: Boolean get() = probe?.os == "macos"
    val isWindows: Boolean get() = probe?.os == "windows"

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
     * server or NAS without a display, and the desktop, screen and cua-driver checks are skipped.
     * Until the user chooses, it is on unless the check found no desktop.
     */
    val usesScreen: Boolean get() = wantsScreen ?: probe?.session?.present ?: true

    /** Everything [ComputerSetupStep.PREPARE] checks is in place, or there is nothing to check. */
    val prepared: Boolean get() = !usesScreen || (sessionReady && screenServiceReady && cuaReady)

    /** The partner saw the screen, and on macOS the driver holds both grants. */
    val tested: Boolean get() = partnerChecked && (!isMac || permissions?.complete == true)
}

/**
 * Where the setup starts. With [assistantId] it ends by giving the computer to that partner; without
 * it (opened from "my computers") it ends by letting the user pick any partners, or none. [hostId]
 * starts on that known computer: with [edit] at its connection details, otherwise by connecting and
 * checking it.
 */
data class ComputerSetupArgs(
    val assistantId: Uuid? = null,
    val hostId: String? = null,
    val edit: Boolean = false,
    /** The user turned the screen on for [hostId]: the check starts with it on even without a desktop found. */
    val screen: Boolean = false,
)

/**
 * Guides the user through connecting a computer and giving it to partners. The UI shows
 * [state] and collects input; every step that trusts a host, runs software on it, stores a
 * credential or changes the partner happens here and only on an explicit user action.
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
            step = ComputerSetupStep.ADDRESS, hostId = args.hostId, editing = true,
        ) else ComputerSetupState(),
    )
    val state: StateFlow<ComputerSetupState> = _state.asStateFlow()

    /** Computers Miffan already knows; the user may pick one instead of adding a new one. */
    val hosts: StateFlow<List<RemoteHostEntity>> = workspaces.listHostsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sshKeys: StateFlow<List<SshKeyEntity>> = workspaces.listSshKeysFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    private var job: Job? = null

    /**
     * The password this setup signed in with, kept in memory only: a Mac's Screen Sharing signs in
     * with the same account, so the setup can skip asking for it again.
     */
    private var signInPassword: String? = null
    /** The key to authorize with the password session, see [ComputerSetupAuth.Password.keyId]. */
    private var keyToAuthorize: String? = null
    private var permissionWatch: Job? = null

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

    init {
        if (args.hostId != null && !args.edit) chooseHost(args.hostId)
    }

    /** The exact command [installCuaDriver] runs, for the confirmation dialog. */
    fun cuaInstallCommand(): String = RemoteScreenRepository.cuaDriverCommand(upgradePath(),
        if (_state.value.probe?.os == "windows") RemotePlatform.WINDOWS else RemotePlatform.POSIX)

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Back one step; leaves created hosts and workspaces in place (they are reused next time). */
    fun back() {
        if (_state.value.busy) return
        _state.update {
            val previous = when (it.step) {
                ComputerSetupStep.ADDRESS, ComputerSetupStep.VERIFY, ComputerSetupStep.PREPARE ->
                    if (it.editing) ComputerSetupStep.ADDRESS else ComputerSetupStep.CHOOSE
                ComputerSetupStep.MAC_ACCOUNT -> ComputerSetupStep.PREPARE
                ComputerSetupStep.TEST -> if (it.isMac) ComputerSetupStep.MAC_ACCOUNT else ComputerSetupStep.PREPARE
                ComputerSetupStep.BIND -> if (it.usesScreen) ComputerSetupStep.TEST else ComputerSetupStep.PREPARE
                ComputerSetupStep.CHOOSE, ComputerSetupStep.DONE -> it.step
            }
            it.copy(step = previous, error = null)
        }
    }

    fun addNewComputer() = _state.update { ComputerSetupState(step = ComputerSetupStep.ADDRESS) }

    /** Continue with a known computer: verify its key if it has none, otherwise connect. */
    fun chooseHost(hostId: String) = run(ComputerSetupTask.CONNECTING) {
        val host = requireNotNull(workspaces.getHostById(hostId)) { "Computer not found" }
        _state.value = ComputerSetupState(hostId = host.id, task = ComputerSetupTask.CONNECTING,
            expectWindows = host.platform == RemotePlatform.WINDOWS.storageName,
            wantsScreen = true.takeIf { args.screen && hostId == args.hostId })
        if (host.trustedHostKeySha256 == null) readFingerprint(host) else connectAndProbe(host.id)
    }

    /**
     * Generates a key in Miffan; the UI shows its public key for the user to authorize. With
     * [reuseExisting] a key that already exists is used instead: [sshKeys] may not have loaded yet.
     */
    fun createAppKey(reuseExisting: Boolean = false, onCreated: (SshKeyEntity) -> Unit) = run(ComputerSetupTask.CREATING_KEY) {
        val existing = workspaces.listSshKeysFlow().first()
        if (reuseExisting && existing.isNotEmpty()) {
            withContext(Dispatchers.Main) { onCreated(existing.first()) }
            return@run
        }
        val taken = existing.map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) "Miffan" else "Miffan $it" }.first { it !in taken }
        val key = workspaces.generateSshKey(name)
        withContext(Dispatchers.Main) { onCreated(key) }
    }

    /**
     * Saves a new computer, or with [ComputerSetupArgs.edit] updates the one being edited; there a null
     * [auth] keeps the saved sign-in. Continues to the fingerprint when it is not trusted (new, or the
     * address changed), otherwise straight to connecting and checking.
     */
    fun submitAddress(name: String, address: String, port: Int, username: String, auth: ComputerSetupAuth?, windows: Boolean = false) =
        run(ComputerSetupTask.READING_FINGERPRINT) {
            _state.update { it.copy(expectWindows = windows) }
            (auth as? ComputerSetupAuth.Password).let {
                signInPassword = it?.value
                keyToAuthorize = it?.keyId
            }
            val host = if (_state.value.editing || _state.value.passwordRefused) {
                val hostId = requireNotNull(_state.value.hostId)
                check(workspaces.updateHost(hostId, name, address, port, username,
                    authentication = (auth as? ComputerSetupAuth.Password)?.let { RemoteAuthentication.Password(it.value) },
                    sshKeyId = (auth as? ComputerSetupAuth.AppKey)?.keyId,
                )) { "Computer not found" }
                requireNotNull(workspaces.getHostById(hostId)) { "Computer not found" }
            } else {
                when (auth) {
                    is ComputerSetupAuth.Password -> workspaces.createHost(name, address, port, username,
                        authentication = RemoteAuthentication.Password(auth.value))
                    is ComputerSetupAuth.AppKey -> workspaces.createHost(name, address, port, username, sshKeyId = auth.keyId)
                    null -> error("Sign-in method required")
                }
            }
            _state.update { it.copy(hostId = host.id) }
            if (host.trustedHostKeySha256 == null) readFingerprint(host) else connectAndProbe(host.id)
        }

    /** Reads the fingerprint again, e.g. after the user compared it with the computer. */
    fun rereadFingerprint() = run(ComputerSetupTask.READING_FINGERPRINT) { readFingerprint(currentHost()) }

    /** Trusts the fingerprint the user saw; refused if the live key differs from it. */
    fun trustAndConnect() = run(ComputerSetupTask.VERIFYING) {
        val host = currentHost()
        val key = requireNotNull(_state.value.hostKey) { "No fingerprint to trust" }
        check(workspaces.trustHostKey(host.id, key.sha256Fingerprint)) { "Computer not found" }
        connectAndProbe(host.id)
    }

    fun reprobe() = run(ComputerSetupTask.PREPARING) { probe() }

    /**
     * Checks again without showing progress, while the user fixes something at the computer; the
     * result is dropped if the user started anything meanwhile. Failures are ignored: the visible
     * check reports them.
     */
    fun quietRecheck() {
        val start = _state.value
        if (start.busy || start.step != ComputerSetupStep.PREPARE || start.prepared) return
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
            _state.update {
                if (it.busy || it.step != ComputerSetupStep.PREPARE || it.hostId != hostId) it
                else it.copy(probe = probe, hostRevision = host.connectionRevision)
            }
        }
    }

    /** macOS: opens Sharing in System Settings on the Mac, where Screen Sharing is switched on. */
    fun openMacSharingSettings() = run(ComputerSetupTask.OPENING_SETTINGS) {
        val workspaceId = requireNotNull(_state.value.workspaceId)
        val result = workspaces.executeCommand(workspaceId, MAC_SHARING_SETTINGS_COMMAND, timeoutMillis = 15_000)
        check(result.exitCode == 0 && !result.timedOut) { result.stderr.ifBlank { "Could not open System Settings" } }
    }

    fun setWantsScreen(wants: Boolean) {
        if (_state.value.busy) return
        _state.update { it.copy(wantsScreen = wants, error = null) }
    }

    /** Installs or upgrades cua-driver; call only after the user confirmed [cuaInstallCommand]. */
    fun installCuaDriver() = run(ComputerSetupTask.INSTALLING) {
        val host = currentHost()
        val outcome = screens.installCuaDriverOnHost(host.id, host.connectionRevision, upgradePath())
        _state.update { it.copy(install = outcome) }
        probe()
    }

    /** Leaves [ComputerSetupStep.PREPARE] once everything is in place. */
    fun continueAfterPrepare() = run(ComputerSetupTask.SAVING) {
        check(_state.value.prepared) { "The computer is not ready yet" }
        if (!_state.value.usesScreen) {
            // Saved screen settings stay, so turning the screen back on only needs the check.
            screens.setScreenEnabled(currentHost().id, false)
            _state.update { it.copy(step = ComputerSetupStep.BIND) }
            return@run
        }
        if (_state.value.isMac) {
            // Screen Sharing signs in with the Mac account: reuse a saved one (a null password keeps
            // it), or the password this setup signed in with. Saving also turns the screen on. Back
            // from the test still leads to the account, to change it.
            val host = currentHost()
            val account: Pair<String, String?>? = savedMacAccount()?.let { it to null }
                ?: signInPassword?.let { host.username to it }
            if (account == null) {
                _state.update { it.copy(step = ComputerSetupStep.MAC_ACCOUNT) }
                return@run
            }
            val (username, password) = account
            screens.updateConfig(host.id, enabled = true, endpoint = RemoteScreenEndpoint.Helper,
                auth = RemoteScreenAuth.MACOS_ACCOUNT, username = username, password = password)
            _state.update { it.copy(step = ComputerSetupStep.TEST) }
            return@run
        }
        val host = currentHost()
        // A screen the user already set up in professional mode keeps its settings.
        if (!host.screenEnabled) {
            screens.updateConfig(host.id, enabled = true, endpoint = RemoteScreenEndpoint.Helper,
                auth = RemoteScreenAuth.NONE, username = "", password = null)
        }
        _state.update { it.copy(step = ComputerSetupStep.TEST) }
    }

    /** Whether the Mac already has a saved Screen Sharing account; the user may keep it. */
    suspend fun savedMacAccount(): String? {
        val config = screens.getConfig(currentHost().id) ?: return null
        return config.username.ifBlank { null }?.takeIf { config.auth == RemoteScreenAuth.MACOS_ACCOUNT && config.hasPassword }
    }

    /** Saves the macOS account Screen Sharing signs in with. [password] null keeps the saved one. */
    fun saveMacAccount(username: String, password: String?) = run(ComputerSetupTask.SAVING) {
        val host = currentHost()
        screens.updateConfig(host.id, enabled = true, endpoint = RemoteScreenEndpoint.Helper,
            auth = RemoteScreenAuth.MACOS_ACCOUNT, username = username.ifBlank { host.username }, password = password)
        _state.update { it.copy(step = ComputerSetupStep.TEST) }
    }

    /**
     * The partner's read-only look: macOS grants (read-only), then one desktop screenshot.
     * Nothing on the computer changes.
     */
    fun checkPartner() = run(ComputerSetupTask.CHECKING) {
        val workspaceId = requireNotNull(_state.value.workspaceId)
        // Linux drivers also answer check_permissions, with macOS TCC fields that mean nothing there.
        val permissions = if (_state.value.isMac) registry.permissions(workspaceId, prompt = false) else null
        _state.update { it.copy(permissions = permissions, partnerChecked = false) }
        if (permissions?.complete == false) return@run
        val png = registry.desktopScreenshot(workspaceId) ?: error("cua-driver returned no screenshot")
        val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(png, 0, png.size) }
            ?: error("Unreadable screenshot")
        _state.update { it.copy(partnerView = bitmap, partnerChecked = true) }
    }

    /**
     * macOS: raise the system permission dialogs on the Mac for missing grants, then keep reading
     * the grants (read-only) while the user answers them, and take the partner's look once both are on.
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
                if (current.step != ComputerSetupStep.TEST || current.workspaceId != workspaceId) return@launch
                if (current.busy) return@repeat
                val grants = try {
                    registry.permissions(workspaceId, prompt = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@repeat
                } ?: return@launch
                _state.update { if (it.workspaceId == workspaceId) it.copy(permissions = grants) else it }
                if (grants.complete) {
                    checkPartner()
                    return@launch
                }
            }
        }
    }

    fun continueAfterTest() {
        if (!_state.value.tested || _state.value.busy) return
        _state.update { it.copy(step = ComputerSetupStep.BIND) }
    }

    /**
     * Binds the partner to this computer and allows computer use with approval kept on. The UI
     * shows the disclosure (screenshots go to the model provider) before calling this.
     */
    /**
     * Partners already bound to a workspace of the computer being set up; the bind step shows them as
     * already using it.
     */
    val boundPartnerIds: StateFlow<Set<Uuid>> = combine(
        settingsStore.settingsFlow, workspaces.listFlow(), _state,
    ) { settings, list, state ->
        val hostId = state.hostId ?: return@combine emptySet()
        val workspaceIds = list.filter { it.isRemote && it.remoteHostId == hostId }.map { it.id }.toSet()
        settings.assistants.filter { it.workspaceId?.toString() in workspaceIds }.map { it.id }.toSet()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun bind(assistantIds: Set<Uuid>): Unit = run(ComputerSetupTask.BINDING) {
        partnerComputers.bind(requireNotNull(_state.value.hostId), assistantIds)
        _state.update { it.copy(step = ComputerSetupStep.DONE) }
    }

    private suspend fun readFingerprint(host: RemoteHostEntity) {
        val key = workspaces.discoverHostKey(host.id)
        _state.update {
            it.copy(
                step = ComputerSetupStep.VERIFY, hostId = host.id, hostKey = key,
                hostKeyChanged = host.trustedHostKeySha256 != null && host.trustedHostKeySha256 != key.sha256Fingerprint,
            )
        }
    }

    private suspend fun connectAndProbe(hostId: String) {
        _state.update { it.copy(task = ComputerSetupTask.CONNECTING) }
        try {
            check(workspaces.testHost(hostId)) { "Connection failed" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error.passwordSignInFailure() != RemotePasswordSignInFailure.NOT_OFFERED) throw error
            _state.update { it.copy(step = ComputerSetupStep.ADDRESS, passwordRefused = true) }
            return
        }
        _state.update { it.copy(passwordRefused = false) }
        val workspaceId = partnerComputers.ensureComputerWorkspace(hostId)
        keyToAuthorize?.let { keyId ->
            keyToAuthorize = null
            switchToKey(hostId, workspaceId, keyId)
        }
        // A screen already in use stays on even if the desktop is signed out right now.
        val screenOn = workspaces.getHostById(hostId)?.screenEnabled == true
        _state.update {
            it.copy(workspaceId = workspaceId, step = ComputerSetupStep.PREPARE,
                wantsScreen = it.wantsScreen ?: true.takeIf { screenOn })
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

    private suspend fun probe() {
        _state.update { it.copy(task = ComputerSetupTask.PREPARING) }
        val host = currentHost()
        val probe = screens.probeHost(host.id, host.connectionRevision)
        _state.update { it.copy(probe = probe, hostRevision = host.connectionRevision, step = ComputerSetupStep.PREPARE) }
        // Sunshine is optional on Linux and macOS and required on Windows; its check runs beside the others.
        if (probe.os in setOf("linux", "macos", "windows")) streamSetup.check(host.id, host.connectionRevision)
    }

    private fun Throwable.passwordSignInFailure(): RemotePasswordSignInFailure? =
        generateSequence(this) { it.cause }.firstNotNullOfOrNull { (it as? RemoteConnectException)?.passwordSignIn }

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
