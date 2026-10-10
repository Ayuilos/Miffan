package me.ayuilos.miffan.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.SshKeyEntity
import me.ayuilos.miffan.data.db.entity.RemoteScreenAuth
import me.ayuilos.miffan.data.db.entity.RemoteScreenEndpoint
import me.ayuilos.miffan.data.repository.RemoteHostScreenConfig
import me.ayuilos.miffan.data.repository.RemoteScreenRepository
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenEnvironmentPhase
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenEnvironmentState
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteStreamSetupController
import me.ayuilos.miffan.data.repository.RemoteSunshinePermission
import me.rerere.workspace.RemoteAuthentication
import me.rerere.workspace.RemoteHostKey
import me.rerere.workspace.RootfsInstallProgress

class WorkspaceVM(
    private val repository: WorkspaceRepository,
    private val screenRepository: RemoteScreenRepository,
) : ViewModel() {
    val workspaces = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val hosts = repository.listHostsFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val sshKeys = repository.listSshKeysFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val remoteHostStates = repository.remoteHostStates
    val remoteWorkspaceStates = repository.remoteWorkspaceStates
    val remoteConnectionStates = repository.remoteConnectionStates
    private val _keyMaterialStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val keyMaterialStatus = _keyMaterialStatus.asStateFlow()

    private val _screenEnvironments = MutableStateFlow<Map<String, RemoteScreenEnvironmentState>>(emptyMap())
    val screenEnvironments = _screenEnvironments.asStateFlow()

    fun probeScreenHost(host: RemoteHostEntity) {
        val previous = _screenEnvironments.value[host.id] ?: RemoteScreenEnvironmentState()
        if (previous.busy) return
        val retained = if (previous.connectionRevision == host.connectionRevision) previous else RemoteScreenEnvironmentState()
        _screenEnvironments.update { it + (host.id to retained.copy(
            connectionRevision = host.connectionRevision,
            phase = RemoteScreenEnvironmentPhase.PROBING,
            probe = null,
            error = null,
        )) }
        runOperation({ screenRepository.probeHost(host.id, host.connectionRevision) }) { result ->
            _screenEnvironments.update { states ->
                val current = states.getValue(host.id)
                states + (host.id to current.copy(
                    phase = RemoteScreenEnvironmentPhase.IDLE,
                    probe = result.getOrNull(),
                    error = result.exceptionOrNull(),
                ))
            }
        }
    }

    private val streamSetup = RemoteStreamSetupController(screenRepository, viewModelScope)
    /** High-performance mode setup per host; see [RemoteStreamSetupController]. */
    val streamSetups = streamSetup.states

    fun checkStream(host: RemoteHostEntity) = streamSetup.check(host.id, host.connectionRevision)
    fun pairStream(host: RemoteHostEntity) = streamSetup.pair(host.id, host.connectionRevision)
    fun cancelStreamPairing() = streamSetup.cancelPairing()
    /** Called only from the confirmation that shows exactly what changes on the computer. */
    fun enforceStreamEncryption(host: RemoteHostEntity) = streamSetup.enforceEncryption(host.id, host.connectionRevision)
    fun startSunshine(host: RemoteHostEntity) = streamSetup.start(host.id, host.connectionRevision)
    fun openSunshineSettings(host: RemoteHostEntity, permission: RemoteSunshinePermission) =
        streamSetup.openPermissionSettings(host.id, host.connectionRevision, permission)
    fun setStreamEnabled(hostId: String, enabled: Boolean, onResult: (Result<Boolean>) -> Unit) =
        streamSetup.setEnabled(hostId, enabled, onResult)

    /** Called only by the environment panel's explicit install/upgrade confirmation. */
    fun installScreenCuaDriver(host: RemoteHostEntity, upgradePath: String?) {
        val previous = _screenEnvironments.value[host.id] ?: return
        if (previous.busy || previous.connectionRevision != host.connectionRevision || previous.probe == null) return
        val cua = previous.probe.cua
        if (cua.ok || upgradePath != cua.path?.takeIf { it.isNotBlank() }) return
        _screenEnvironments.update { it + (host.id to previous.copy(
            phase = RemoteScreenEnvironmentPhase.INSTALLING,
            installation = null,
            installationError = null,
            error = null,
        )) }
        runOperation({ screenRepository.installCuaDriverOnHost(host.id, host.connectionRevision, upgradePath) }) { result ->
            _screenEnvironments.update { states ->
                states + (host.id to states.getValue(host.id).copy(
                    phase = RemoteScreenEnvironmentPhase.IDLE,
                    installation = result.getOrNull(),
                    installationError = result.exceptionOrNull(),
                ))
            }
            // A fresh probe follows both a command outcome and an execution exception.
            // Keep the install error/output distinct from the probe error.
            probeScreenHost(host)
        }
    }

    fun refreshKeyMaterial(keys: List<SshKeyEntity>) {
        viewModelScope.launch {
            val status = mutableMapOf<String, Boolean>()
            keys.forEach { key ->
                try {
                    status[key.id] = repository.hasSshKeyMaterial(key.id)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // An unreadable credential stays unknown rather than being presented as missing.
                }
            }
            _keyMaterialStatus.value = status
        }
    }

    private fun <T> runOperation(
        operation: suspend () -> T,
        onResult: (Result<T>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = try {
                Result.success(operation())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            onResult(result)
        }
    }

    fun create(name: String, onResult: (Result<WorkspaceEntity>) -> Unit) {
        runOperation({ repository.create(name) }, onResult)
    }

    fun createRemote(name: String, hostId: String, remoteRoot: String, onResult: (Result<WorkspaceEntity>) -> Unit) {
        runOperation({ repository.createRemoteWorkspace(name, hostId, remoteRoot) }, onResult)
    }

    fun createHost(
        name: String,
        host: String,
        port: Int,
        username: String,
        authentication: RemoteAuthentication?,
        sshKeyId: String?,
        onResult: (Result<RemoteHostEntity>) -> Unit,
    ) {
        runOperation({ repository.createHost(name, host, port, username, authentication, sshKeyId) }, onResult)
    }

    fun updateHost(
        id: String,
        name: String,
        host: String,
        port: Int,
        username: String,
        authentication: RemoteAuthentication?,
        sshKeyId: String?,
        onResult: (Result<Boolean>) -> Unit,
    ) {
        runOperation({ repository.updateHost(id, name, host, port, username, authentication, sshKeyId) }, onResult)
    }

    fun getHost(id: String, onResult: (Result<RemoteHostEntity?>) -> Unit) {
        runOperation({ repository.getHostById(id) }, onResult)
    }

    fun getScreenConfig(hostId: String, onResult: (Result<RemoteHostScreenConfig?>) -> Unit) {
        runOperation({ screenRepository.getConfig(hostId) }, onResult)
    }

    fun updateScreenConfig(
        hostId: String,
        enabled: Boolean,
        endpoint: RemoteScreenEndpoint,
        auth: RemoteScreenAuth,
        username: String,
        password: String?,
        onResult: (Result<Boolean>) -> Unit,
    ) {
        runOperation({ screenRepository.updateConfig(hostId, enabled, endpoint, auth, username, password) }, onResult)
    }

    fun generateSshKey(name: String, onResult: (Result<SshKeyEntity>) -> Unit) {
        runOperation({ repository.generateSshKey(name) }, onResult)
    }

    fun importSshKey(name: String, privateKeyPem: String, passphrase: String?, onResult: (Result<SshKeyEntity>) -> Unit) {
        runOperation({ repository.importSshKey(name, privateKeyPem, passphrase) }, onResult)
    }

    fun renameSshKey(id: String, name: String, onResult: (Result<Boolean>) -> Unit) {
        runOperation({ repository.renameSshKey(id, name) }, onResult)
    }

    fun deleteSshKey(id: String, onResult: (Result<Boolean>) -> Unit) {
        runOperation({ repository.deleteSshKey(id) }, onResult)
    }

    fun hasSshKeyMaterial(id: String, onResult: (Result<Boolean>) -> Unit) {
        runOperation({ repository.hasSshKeyMaterial(id) }, onResult)
    }

    fun importSshKeyMaterial(id: String, privateKeyPem: String, passphrase: String?, onResult: (Result<Boolean>) -> Unit) {
        runOperation({ repository.importSshKeyMaterial(id, privateKeyPem, passphrase) }, onResult)
    }

    fun exportSshPrivateKey(id: String, passphrase: String?, onResult: (Result<String>) -> Unit) {
        runOperation({ repository.exportSshPrivateKey(id, passphrase) }, onResult)
    }

    fun discoverHostKey(id: String, onResult: (Result<RemoteHostKey>) -> Unit) {
        runOperation({ repository.discoverHostKey(id) }, onResult)
    }

    fun trustHostKey(id: String, fingerprint: String, onResult: (Result<Boolean>) -> Unit) {
        runOperation({ repository.trustHostKey(id, fingerprint) }, onResult)
    }

    fun testHost(id: String, onResult: (Result<Boolean>) -> Unit) {
        runOperation({ repository.testHost(id) }, onResult)
    }

    fun deleteHost(id: String, onResult: (Result<Boolean>) -> Unit) {
        runOperation({ repository.deleteHost(id).also { deleted -> if (deleted) screenRepository.forgetHost(id) } }, onResult)
    }

    fun rename(workspace: WorkspaceEntity, name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(workspace.id, name) }
        }
    }

    fun delete(workspace: WorkspaceEntity) {
        viewModelScope.launch {
            repository.delete(workspace.id)
        }
    }
}
