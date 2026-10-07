package me.ayuilos.miffan.ui.im.computer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import me.ayuilos.miffan.data.ai.computer.RemoteComputerRegistry
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.RemoteScreenAuth
import me.ayuilos.miffan.data.db.entity.RemoteScreenEndpoint
import me.ayuilos.miffan.data.db.entity.SshKeyEntity
import me.ayuilos.miffan.data.model.withWorkspaceBinding
import me.ayuilos.miffan.data.repository.RemoteCommandOutcome
import me.ayuilos.miffan.data.repository.RemoteMachineProbe
import me.ayuilos.miffan.data.repository.RemoteScreenRepository
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.rerere.workspace.RemoteAuthentication
import me.rerere.workspace.RemoteHostKey
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
enum class ComputerSetupTask { CREATING_KEY, CONNECTING, READING_FINGERPRINT, VERIFYING, PREPARING, INSTALLING, SAVING, CHECKING, BINDING }

sealed interface ComputerSetupAuth {
    data class Password(val value: String) : ComputerSetupAuth {
        override fun toString(): String = "Password([redacted])"
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
    val install: RemoteCommandOutcome? = null,
    /** macOS grants of the remote cua-driver; null on Linux or before checking. */
    val permissions: ComputerPermissions? = null,
    /** What the partner saw in the read-only check. */
    val partnerView: Bitmap? = null,
    val partnerChecked: Boolean = false,
) {
    val busy: Boolean get() = task != null
    val isMac: Boolean get() = probe?.os == "macos"

    val sessionReady: Boolean get() = probe?.session?.present == true
    /** A VNC server exists; macOS Screen Sharing must also be switched on (the helper cannot start it). */
    val screenServiceReady: Boolean get() = probe?.vnc?.let { vnc ->
        vnc.server != null && vnc.server != "none" && (vnc.server != "macos-screen-sharing" || vnc.running)
    } == true
    val cuaReady: Boolean get() = probe?.cua?.ok == true

    /** Everything [ComputerSetupStep.PREPARE] checks is in place. */
    val prepared: Boolean get() = sessionReady && screenServiceReady && cuaReady

    /** The partner saw the screen, and on macOS the driver holds both grants. */
    val tested: Boolean get() = partnerChecked && (!isMac || permissions?.complete == true)
}

/**
 * Guides the user through connecting a computer and giving it to one partner. The UI shows
 * [state] and collects input; every step that trusts a host, runs software on it, stores a
 * credential or changes the partner happens here and only on an explicit user action.
 */
class ComputerSetupVM(
    val assistantId: Uuid,
    private val workspaces: WorkspaceRepository,
    private val screens: RemoteScreenRepository,
    private val registry: RemoteComputerRegistry,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ComputerSetupState())
    val state: StateFlow<ComputerSetupState> = _state.asStateFlow()

    /** Computers Miffan already knows; the user may pick one instead of adding a new one. */
    val hosts: StateFlow<List<RemoteHostEntity>> = workspaces.listHostsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sshKeys: StateFlow<List<SshKeyEntity>> = workspaces.listSshKeysFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The workspace the partner is bound to now, when it is not the one being set up; binding
     * replaces it, so the UI says so before the user confirms.
     */
    val replacedWorkspaceName: StateFlow<String?> = combine(settingsStore.settingsFlow, workspaces.listFlow(), _state) { settings, list, state ->
        val bound = settings.getAssistantById(assistantId)?.workspaceId?.toString() ?: return@combine null
        if (bound == state.workspaceId) null else list.find { it.id == bound }?.name
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var job: Job? = null

    /** The exact command [installCuaDriver] runs, for the confirmation dialog. */
    fun cuaInstallCommand(): String = RemoteScreenRepository.cuaDriverCommand(upgradePath())

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Back one step; leaves created hosts and workspaces in place (they are reused next time). */
    fun back() {
        if (_state.value.busy) return
        _state.update {
            val previous = when (it.step) {
                ComputerSetupStep.ADDRESS, ComputerSetupStep.VERIFY, ComputerSetupStep.PREPARE -> ComputerSetupStep.CHOOSE
                ComputerSetupStep.MAC_ACCOUNT -> ComputerSetupStep.PREPARE
                ComputerSetupStep.TEST -> if (it.isMac) ComputerSetupStep.MAC_ACCOUNT else ComputerSetupStep.PREPARE
                ComputerSetupStep.BIND -> ComputerSetupStep.TEST
                ComputerSetupStep.CHOOSE, ComputerSetupStep.DONE -> it.step
            }
            it.copy(step = previous, error = null)
        }
    }

    fun addNewComputer() = _state.update { ComputerSetupState(step = ComputerSetupStep.ADDRESS) }

    /** Continue with a known computer: verify its key if it has none, otherwise connect. */
    fun chooseHost(hostId: String) = run(ComputerSetupTask.CONNECTING) {
        val host = requireNotNull(workspaces.getHostById(hostId)) { "Computer not found" }
        _state.value = ComputerSetupState(hostId = host.id, task = ComputerSetupTask.CONNECTING)
        if (host.trustedHostKeySha256 == null) readFingerprint(host) else connectAndProbe(host.id)
    }

    /** Generates a key in Miffan; the UI shows its public key for the user to authorize. */
    fun createAppKey(onCreated: (SshKeyEntity) -> Unit) = run(ComputerSetupTask.CREATING_KEY) {
        val taken = sshKeys.value.map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) "Miffan" else "Miffan $it" }.first { it !in taken }
        val key = workspaces.generateSshKey(name)
        withContext(Dispatchers.Main) { onCreated(key) }
    }

    fun submitAddress(name: String, address: String, port: Int, username: String, auth: ComputerSetupAuth) =
        run(ComputerSetupTask.READING_FINGERPRINT) {
            val host = when (auth) {
                is ComputerSetupAuth.Password -> workspaces.createHost(name, address, port, username,
                    authentication = RemoteAuthentication.Password(auth.value))
                is ComputerSetupAuth.AppKey -> workspaces.createHost(name, address, port, username, sshKeyId = auth.keyId)
            }
            _state.update { it.copy(hostId = host.id) }
            readFingerprint(host)
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
        if (_state.value.isMac) {
            _state.update { it.copy(step = ComputerSetupStep.MAC_ACCOUNT) }
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
        val permissions = registry.permissions(workspaceId, prompt = false)
        _state.update { it.copy(permissions = permissions, partnerChecked = false) }
        if (permissions?.complete == false) return@run
        val png = registry.desktopScreenshot(workspaceId) ?: error("cua-driver returned no screenshot")
        val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(png, 0, png.size) }
            ?: error("Unreadable screenshot")
        _state.update { it.copy(partnerView = bitmap, partnerChecked = true) }
    }

    /** macOS: raise the system permission dialogs on the Mac for missing grants. */
    fun requestMacPermissions() = run(ComputerSetupTask.CHECKING) {
        val workspaceId = requireNotNull(_state.value.workspaceId)
        _state.update { it.copy(permissions = registry.permissions(workspaceId, prompt = true)) }
    }

    fun continueAfterTest() {
        if (!_state.value.tested || _state.value.busy) return
        _state.update { it.copy(step = ComputerSetupStep.BIND) }
    }

    /**
     * Binds the partner to this computer and allows computer use with approval kept on. The UI
     * shows the disclosure (screenshots go to the model provider) before calling this.
     */
    fun bind() = run(ComputerSetupTask.BINDING) {
        val workspaceId = Uuid.parse(requireNotNull(_state.value.workspaceId))
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map {
                if (it.id != assistantId) it
                else it.withWorkspaceBinding(workspaceId).copy(computerUseEnabled = true, computerUseApprovalRequired = true)
            })
        }
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
        check(workspaces.testHost(hostId)) { "Connection failed" }
        val workspaceId = ensureWorkspace(currentHost())
        _state.update { it.copy(workspaceId = workspaceId, step = ComputerSetupStep.PREPARE) }
        probe()
    }

    /** The host's first remote workspace, or a new one rooted at the account's home directory. */
    private suspend fun ensureWorkspace(host: RemoteHostEntity): String {
        workspaces.listFlow().first().firstOrNull { it.remoteHostId == host.id }?.let { return it.id }
        val home = workspaces.remoteHome(host.id)
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) host.name else "${host.name} $it" }
            .first { !workspaces.isNameTaken(it, null) }
        return workspaces.createRemoteWorkspace(name, host.id, home).id
    }

    private suspend fun probe() {
        _state.update { it.copy(task = ComputerSetupTask.PREPARING) }
        val host = currentHost()
        val probe = screens.probeHost(host.id, host.connectionRevision)
        _state.update { it.copy(probe = probe, step = ComputerSetupStep.PREPARE) }
    }

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
