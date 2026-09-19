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
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.rerere.workspace.RemoteAuthentication
import me.rerere.workspace.RemoteHostKey
import me.rerere.workspace.RootfsInstallProgress

class WorkspaceVM(
    private val repository: WorkspaceRepository,
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
        runOperation({ repository.deleteHost(id) }, onResult)
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
