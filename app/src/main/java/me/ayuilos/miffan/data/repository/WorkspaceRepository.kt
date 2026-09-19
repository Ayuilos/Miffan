package me.ayuilos.miffan.data.repository

import android.content.res.Resources
import me.ayuilos.miffan.R
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.dao.WorkspaceDAO
import me.ayuilos.miffan.data.db.dao.RemoteHostDAO
import me.ayuilos.miffan.data.db.dao.SshKeyDAO
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.SshKeyEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.model.withWorkspaceBinding
import me.ayuilos.miffan.data.model.withWorkspaceShellApproval
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.workspace.RootfsCatalog
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceScope
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.NativeSshWorkspaceTransport
import me.rerere.workspace.RemoteAuthentication
import me.rerere.workspace.RemoteHostConfig
import me.rerere.workspace.RemoteHostKey
import me.rerere.workspace.RemoteTerminalSession
import me.rerere.workspace.RemoteWorkspaceSession
import me.rerere.workspace.RemoteWorkspaceDirectoryException
import me.rerere.workspace.RemoteFileTimeoutException
import me.rerere.workspace.SshKeyCodec
import me.rerere.workspace.SshKeyMaterial
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.Closeable
import java.util.UUID
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val networkBroker: WorkspaceNetworkBroker,
    private val settingsStore: SettingsStore,
    private val hostDao: RemoteHostDAO,
    private val credentialStore: RemoteHostCredentialStore,
    private val remoteTransport: NativeSshWorkspaceTransport,
    private val sshKeyDao: SshKeyDAO,
    private val sshKeyCredentialStore: SshKeyCredentialStore,
    private val workspaceStrings: Resources,
) {
    private val localWorkspaceCreator = LocalWorkspaceCreator(dao, manager)
    private val remoteRuntime = RemoteWorkspaceRuntimeTracker()
    private val remoteConnections = RemoteWorkspaceConnectionPool<RemoteWorkspaceSession>(
        connected = { it.isConnected },
    )
    private val terminalLock = Any()
    private val activeTerminals = mutableMapOf<String, MutableSet<RemoteTerminalConnection>>()

    val remoteHostStates: StateFlow<Map<String, RemoteHostRuntimeState>> = remoteRuntime.hostStates
    val remoteWorkspaceStates: StateFlow<Map<String, RemoteWorkspaceRuntimeState>> = remoteRuntime.workspaceStates
    val remoteConnectionStates: StateFlow<Map<String, RemoteConnectionStatus>> = remoteConnections.states

    private fun remoteIdentity(workspace: WorkspaceEntity, host: RemoteHostEntity) =
        RemoteConnectionIdentity(workspace.id, host.id, host.connectionRevision,
            requireNotNull(workspace.remotePath))

    private suspend fun openLeasedConnection(
        identity: RemoteConnectionIdentity,
        config: RemoteHostConfig,
        verifyPath: Boolean = true,
        explicit: Boolean = true,
    ): RemoteWorkspaceConnectionPool<RemoteWorkspaceSession>.Lease =
        remoteConnections.acquire(identity, explicit = explicit) {
            var attempt = 0
            while (true) {
                val opened = AtomicReference<RemoteWorkspaceSession?>()
                val disposed = AtomicBoolean(false)
                try {
                    val result = runRemoteInterruptible {
                        remoteTransport.open(config).also { session ->
                            if (disposed.get()) session.close()
                            else {
                                opened.set(session)
                                if (disposed.get()) opened.getAndSet(null)?.close()
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (verifyPath && result.resolvedRoot != identity.remotePath) {
                        throw WorkspaceToolTargetChangedException()
                    }
                    opened.compareAndSet(result, null)
                    return@acquire result
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (attempt++ > 0 || !error.retryableConnectionFailure()) throw error
                    delay(250)
                } finally {
                    disposed.set(true)
                    opened.getAndSet(null)?.close()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable SSH connection retry state")
        }

    /** Only the initial handshake is retried, never a dispatched file or shell operation. */
    private fun Throwable.retryableConnectionFailure(): Boolean =
        this is SocketTimeoutException || this is ConnectException ||
            (this is JSchException &&
                (cause?.retryableConnectionFailure() == true ||
                    message?.contains("timeout", ignoreCase = true) == true ||
                    message?.contains("connection refused", ignoreCase = true) == true))

    private suspend fun <T> runRemoteOperation(session: RemoteWorkspaceSession, block: () -> T): T {
        val operation = session.newOperation()
        return runCancellableRemoteOperation(operation) {
            session.withOperation(operation, block)
        }
    }

    /** Holds a connection while a configured workspace is on screen. */
    suspend fun retainRemoteWorkspace(id: String): Closeable? {
        if (!remoteConnections.mayPreconnect(id)) return null
        val workspace = dao.getById(id)?.takeIf { it.isRemote } ?: return null
        val host = hostDao.getById(workspace.remoteHostId ?: return null) ?: return null
        val config = host.config(requireNotNull(workspace.remotePath))
        return openLeasedConnection(remoteIdentity(workspace, host), config, explicit = false)
    }

    suspend fun connectRemoteWorkspace(id: String) {
        val workspace = dao.getById(id)?.takeIf { it.isRemote }
            ?: error(workspaceStrings.getString(R.string.workspace_not_found))
        val host = hostDao.getById(requireNotNull(workspace.remoteHostId))
            ?: error(workspaceStrings.getString(R.string.workspace_remote_host_not_found))
        openLeasedConnection(remoteIdentity(workspace, host),
            host.config(requireNotNull(workspace.remotePath))).close()
    }

    fun disconnectRemoteWorkspace(id: String) {
        closeRemoteTerminals(id)
        remoteConnections.disconnect(id)
    }

    private fun closeRemoteTerminals(id: String) {
        val terminals = synchronized(terminalLock) { activeTerminals[id]?.toList().orEmpty() }
        terminals.forEach { runCatching { it.close() } }
    }

    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    fun listHostsFlow(): Flow<List<RemoteHostEntity>> = hostDao.listFlow()

    /** Captured by the app when a tool call is created; model input never supplies these fields. */
    suspend fun currentWorkspaceToolTarget(
        assistantId: String,
        workspaceId: String,
        scopeId: String?,
    ): WorkspaceToolTargetSnapshot? {
        val assistant = settingsStore.settingsFlow.value.assistants
            .find { it.id.toString() == assistantId } ?: return null
        if (assistant.workspaceId?.toString() != workspaceId ||
            assistant.workspaceScopeId?.toString() != scopeId
        ) return null
        val workspace = dao.getById(workspaceId) ?: return null
        val host = if (workspace.isRemote) {
            hostDao.getById(workspace.remoteHostId ?: return null) ?: return null
        } else null
        return WorkspaceToolTargetSnapshot(
            assistantId = assistantId,
            workspacePermissionRevision = assistant.workspacePermissionRevision,
            workspaceId = workspaceId,
            scopeId = scopeId,
            kind = workspace.kind,
            localRoot = workspace.root.takeUnless { workspace.isRemote },
            remoteHostId = host?.id,
            remoteRoot = workspace.remotePath.takeIf { workspace.isRemote },
            hostConnectionRevision = host?.connectionRevision,
            workspaceName = workspace.name,
            remoteHostName = host?.name,
            remoteHostLabel = host?.let { "${it.username}@${it.host}:${it.port}" },
        )
    }

    private suspend fun validateWorkspaceToolTarget(
        expected: WorkspaceToolTargetSnapshot?,
        workspaceId: String,
        requireShell: Boolean = false,
    ) {
        if (expected == null) return
        val actual = currentWorkspaceToolTarget(expected.assistantId, workspaceId, expected.scopeId)
        if (!expected.sameTarget(actual)) throw WorkspaceToolTargetChangedException()
        if (requireShell) {
            val assistant = settingsStore.settingsFlow.value.assistants
                .find { it.id.toString() == expected.assistantId }
            if (assistant?.workspaceShellEnabled != true) throw WorkspaceToolTargetChangedException()
        }
    }

    private fun validateLoadedWorkspace(
        expected: WorkspaceToolTargetSnapshot?,
        workspace: WorkspaceEntity,
    ) {
        if (expected == null) return
        if (expected.workspaceId != workspace.id || expected.kind != workspace.kind ||
            expected.localRoot != workspace.root.takeUnless { workspace.isRemote } ||
            expected.remoteHostId != workspace.remoteHostId.takeIf { workspace.isRemote } ||
            expected.remoteRoot != workspace.remotePath.takeIf { workspace.isRemote }
        ) throw WorkspaceToolTargetChangedException()
    }

    suspend fun getHostById(id: String): RemoteHostEntity? = hostDao.getById(id)

    fun listSshKeysFlow(): Flow<List<SshKeyEntity>> = sshKeyDao.listFlow()

    suspend fun getSshKeyById(id: String): SshKeyEntity? = sshKeyDao.getById(id)

    suspend fun hasSshKeyMaterial(id: String): Boolean = try {
        loadVerifiedSshKey(id)
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    suspend fun generateSshKey(name: String): SshKeyEntity =
        addSshKey(name, withContext(Dispatchers.Default) { SshKeyCodec.generate() })

    suspend fun importSshKey(
        name: String,
        privateKeyPem: String,
        passphrase: String? = null,
    ): SshKeyEntity = addSshKey(
        name,
        withContext(Dispatchers.Default) { SshKeyCodec.importPrivateKey(privateKeyPem, passphrase) },
    )

    /** Replaces missing local key material after restoring public metadata from a backup. */
    suspend fun importSshKeyMaterial(
        id: String,
        privateKeyPem: String,
        passphrase: String? = null,
    ): Boolean {
        val key = sshKeyDao.getById(id) ?: return false
        val material = withContext(Dispatchers.Default) {
            SshKeyCodec.importPrivateKey(privateKeyPem, passphrase)
        }
        require(material.algorithm == key.algorithm && material.fingerprint == key.fingerprint &&
            material.publicKey == key.publicKey
        ) { workspaceStrings.getString(R.string.workspace_key_mismatch) }
        withContext(Dispatchers.IO) { sshKeyCredentialStore.save(id, material.privateKeyPem) }
        sshKeyDao.update(key.copy(updatedAt = System.currentTimeMillis()))
        hostDao.listFlow().first().filter { it.sshKeyId == id }.forEach { host ->
            hostDao.update(host.copy(
                connectionRevision = UUID.randomUUID().toString(),
                updatedAt = System.currentTimeMillis(),
            ))
            invalidateRemoteHostWorkspaces(host.id)
        }
        return true
    }

    suspend fun renameSshKey(id: String, name: String): Boolean {
        val key = sshKeyDao.getById(id) ?: return false
        val finalName = name.trim().also { require(it.isNotEmpty()) { workspaceStrings.getString(R.string.workspace_error_key_name_required) } }
        sshKeyDao.update(key.copy(name = finalName, updatedAt = System.currentTimeMillis()))
        return true
    }

    suspend fun deleteSshKey(id: String): Boolean {
        if (sshKeyDao.getById(id) == null) return false
        require(hostDao.countBySshKeyId(id) == 0) { workspaceStrings.getString(R.string.workspace_error_key_linked) }
        sshKeyDao.deleteById(id)
        withContext(Dispatchers.IO) { sshKeyCredentialStore.delete(id) }
        return true
    }

    private suspend fun addSshKey(name: String, material: SshKeyMaterial): SshKeyEntity {
        val finalName = name.trim().also { require(it.isNotEmpty()) { workspaceStrings.getString(R.string.workspace_error_key_name_required) } }
        val now = System.currentTimeMillis()
        val key = SshKeyEntity(
            id = UUID.randomUUID().toString(),
            name = finalName,
            algorithm = material.algorithm,
            publicKey = material.publicKey,
            fingerprint = material.fingerprint,
            createdAt = now,
            updatedAt = now,
        )
        withContext(Dispatchers.IO) { sshKeyCredentialStore.save(key.id, material.privateKeyPem) }
        try {
            sshKeyDao.insert(key)
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { sshKeyCredentialStore.delete(key.id) }
            throw error
        }
        return key
    }

    suspend fun createHost(
        name: String,
        host: String,
        port: Int,
        username: String,
        authentication: RemoteAuthentication? = null,
        sshKeyId: String? = null,
    ): RemoteHostEntity {
        require((authentication != null) != (sshKeyId != null)) {
            workspaceStrings.getString(R.string.workspace_error_auth_selection)
        }
        if (sshKeyId != null) requireUsableSshKey(sshKeyId)
        val finalName = name.trim().also { require(it.isNotEmpty()) { workspaceStrings.getString(R.string.workspace_error_host_name_required) } }
        val finalHost = host.trim().also { require(it.isNotEmpty()) { workspaceStrings.getString(R.string.workspace_error_host_address_required) } }
        val finalUser = username.trim().also { require(it.isNotEmpty()) { workspaceStrings.getString(R.string.workspace_error_username_required) } }
        require(port in 1..65535) { workspaceStrings.getString(R.string.workspace_error_port_invalid) }
        val now = System.currentTimeMillis()
        val result = RemoteHostEntity(
            id = UUID.randomUUID().toString(),
            name = finalName,
            host = finalHost,
            port = port,
            username = finalUser,
            authType = authentication?.let(::authType) ?: AUTH_MANAGED_KEY,
            sshKeyId = sshKeyId,
            trustedHostKeySha256 = null,
            connectionRevision = UUID.randomUUID().toString(),
            createdAt = now,
            updatedAt = now,
        )
        if (authentication != null) withContext(Dispatchers.IO) {
            credentialStore.save(result.id, authentication)
        }
        try {
            hostDao.insert(result)
        } catch (error: Throwable) {
            if (authentication != null) withContext(NonCancellable + Dispatchers.IO) {
                credentialStore.delete(result.id)
            }
            throw error
        }
        remoteRuntime.configuration(result.id, RemoteConfigurationState.HOST_KEY_UNTRUSTED,
            result.connectionRevision)
        return result
    }

    suspend fun updateHost(
        id: String,
        name: String,
        host: String,
        port: Int,
        username: String,
        authentication: RemoteAuthentication? = null,
        sshKeyId: String? = null,
    ): Boolean {
        require(authentication == null || sshKeyId == null) {
            workspaceStrings.getString(R.string.workspace_error_auth_selection)
        }
        if (sshKeyId != null) requireUsableSshKey(sshKeyId)
        val previous = hostDao.getById(id) ?: return false
        val finalName = name.trim().also { require(it.isNotEmpty()) { workspaceStrings.getString(R.string.workspace_error_host_name_required) } }
        val finalHost = host.trim().also { require(it.isNotEmpty()) { workspaceStrings.getString(R.string.workspace_error_host_address_required) } }
        val finalUser = username.trim().also { require(it.isNotEmpty()) { workspaceStrings.getString(R.string.workspace_error_username_required) } }
        require(port in 1..65535) { workspaceStrings.getString(R.string.workspace_error_port_invalid) }
        val changedEndpoint = previous.host != finalHost || previous.port != port
        val changedConnection = changedEndpoint || previous.username != finalUser ||
            authentication != null || (sshKeyId != null && sshKeyId != previous.sshKeyId)
        val oldAuthentication = if (authentication != null) withContext(Dispatchers.IO) {
            runCatching { credentialStore.load(id) }.getOrNull()
        } else null
        if (authentication != null) withContext(Dispatchers.IO) {
            credentialStore.save(id, authentication)
        }
        try {
            hostDao.update(previous.copy(
                name = finalName,
                host = finalHost,
                port = port,
                username = finalUser,
                authType = when {
                    authentication != null -> authType(authentication)
                    sshKeyId != null -> AUTH_MANAGED_KEY
                    else -> previous.authType
                },
                sshKeyId = if (authentication != null) null else sshKeyId ?: previous.sshKeyId,
                trustedHostKeySha256 = previous.trustedHostKeySha256.takeUnless { changedEndpoint },
                connectionRevision = if (changedConnection) UUID.randomUUID().toString()
                    else previous.connectionRevision,
                updatedAt = System.currentTimeMillis(),
            ))
        } catch (error: Throwable) {
            if (authentication != null) withContext(NonCancellable + Dispatchers.IO) {
                if (oldAuthentication != null) credentialStore.save(id, oldAuthentication)
                else credentialStore.delete(id)
            }
            throw error
        }
        if (sshKeyId != null && previous.sshKeyId == null) withContext(Dispatchers.IO) {
            credentialStore.delete(id)
        }
        if (changedConnection) invalidateRemoteHostWorkspaces(id)
        return true
    }

    /** Discover without authentication; the UI must display and explicitly confirm this key. */
    suspend fun discoverHostKey(id: String): RemoteHostKey {
        val host = hostDao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_host_not_found, id))
        return runRemoteInterruptible { remoteTransport.discoverHostKey(host.host, host.port) }
    }

    /** Re-check the presented key at trust time to avoid pinning a stale fingerprint. */
    suspend fun trustHostKey(id: String, fingerprintSha256: String): Boolean {
        val host = hostDao.getById(id) ?: return false
        require(fingerprintSha256.isNotBlank()) { workspaceStrings.getString(R.string.workspace_error_fingerprint_required) }
        val liveKey = runRemoteInterruptible {
            remoteTransport.discoverHostKey(host.host, host.port)
        }
        require(liveKey.sha256Fingerprint == fingerprintSha256) {
            workspaceStrings.getString(R.string.workspace_error_host_key_changed)
        }
        hostDao.update(host.copy(
            trustedHostKeySha256 = fingerprintSha256,
            connectionRevision = if (host.trustedHostKeySha256 != fingerprintSha256)
                UUID.randomUUID().toString() else host.connectionRevision,
            updatedAt = System.currentTimeMillis(),
        ))
        if (host.trustedHostKeySha256 != fingerprintSha256) invalidateRemoteHostWorkspaces(id)
        return true
    }

    suspend fun testHost(id: String): Boolean {
        val host = hostDao.getById(id) ?: return false
        val revision = host.connectionRevision
        remoteRuntime.begin(id, revision = revision)
        var stage = RemoteConnectionStage.CONNECTING
        try {
            val config = try {
                host.config()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val state = if (host.trustedHostKeySha256 == null)
                    RemoteConfigurationState.HOST_KEY_UNTRUSTED
                else RemoteConfigurationState.CREDENTIAL_MISSING
                remoteRuntime.configurationFailed(id, state = state,
                    reason = state.shortReason(), revision = revision)
                stage = RemoteConnectionStage.FINISHED
                throw error
            }
            runRemoteInterruptible { remoteTransport.open(config).use { } }
            remoteRuntime.connected(id, revision = revision)
            stage = RemoteConnectionStage.OPERATING
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (stage == RemoteConnectionStage.CONNECTING) {
                remoteRuntime.connectionFailed(id, reason = shortConnectionReason(error),
                    revision = revision)
                stage = RemoteConnectionStage.FINISHED
            }
            throw error
        } finally {
            when (stage) {
                RemoteConnectionStage.CONNECTING -> remoteRuntime.abandoned(id, revision = revision)
                RemoteConnectionStage.OPERATING -> remoteRuntime.completed(id, revision = revision)
                RemoteConnectionStage.FINISHED -> Unit
            }
        }
        for (workspace in dao.getByRemoteHostId(id)) {
            try {
                withRemoteWorkspace(workspace, recordOperation = false) { }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The workspace's directory check is recorded separately from host connectivity.
            }
        }
        return true
    }

    suspend fun deleteHost(id: String): Boolean {
        if (hostDao.getById(id) == null) return false
        require(dao.countByRemoteHostId(id) == 0) { workspaceStrings.getString(R.string.workspace_delete_linked_workspaces_first) }
        hostDao.deleteById(id)
        withContext(Dispatchers.IO) { credentialStore.delete(id) }
        remoteRuntime.forgetHost(id)
        return true
    }

    suspend fun createRemoteWorkspace(name: String, hostId: String, remoteRoot: String): WorkspaceEntity {
        val host = hostDao.getById(hostId) ?: error(workspaceStrings.getString(R.string.workspace_error_host_not_found, hostId))
        if (host.trustedHostKeySha256 == null) {
            remoteRuntime.configuration(hostId, RemoteConfigurationState.HOST_KEY_UNTRUSTED,
                host.connectionRevision)
            error(workspaceStrings.getString(R.string.workspace_fingerprint_not_confirmed))
        }
        val path = remoteRoot.trim().trimEnd('/').ifEmpty { "/" }
        require(path.startsWith('/') && !path.contains('\u0000') && path.split('/').none { it == ".." || it == "." }) {
            workspaceStrings.getString(R.string.workspace_error_absolute_path)
        }
        require(path != "/") {
            workspaceStrings.getString(R.string.workspace_error_root_path)
        }
        val finalName = name.trim().also { require(it.isNotEmpty()) { workspaceStrings.getString(R.string.workspace_error_name_required) } }
        require(!isNameTaken(finalName, null)) { workspaceStrings.getString(R.string.workspace_error_name_duplicate, finalName) }
        val workspaceId = UUID.randomUUID().toString()
        val revision = host.connectionRevision
        remoteRuntime.begin(hostId, workspaceId, revision)
        var stage = RemoteConnectionStage.CONNECTING
        var lease: RemoteWorkspaceConnectionPool<RemoteWorkspaceSession>.Lease? = null
        val resolvedPath = try {
            val config = try {
                host.config(remoteRoot = path)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                remoteRuntime.configurationFailed(hostId, workspaceId,
                    RemoteConfigurationState.CREDENTIAL_MISSING,
                    RemoteConfigurationState.CREDENTIAL_MISSING.shortReason(), revision)
                stage = RemoteConnectionStage.FINISHED
                throw error
            }
            val opened = openLeasedConnection(
                RemoteConnectionIdentity(workspaceId, hostId, revision, path), config,
                verifyPath = false,
            ).also { lease = it }.session
            stage = RemoteConnectionStage.OPERATING
            remoteRuntime.connected(hostId, workspaceId, revision)
            val result = runRemoteOperation(opened) {
                opened.execute("pwd", timeoutMillis = 15_000)
            }
            when {
                result.timedOut -> {
                    remoteRuntime.operation(workspaceId, RemoteOperationOutcome.OUTCOME_UNKNOWN,
                        workspaceStrings.getString(R.string.workspace_directory_check_timeout), revision = revision)
                    error(workspaceStrings.getString(R.string.workspace_error_path_timeout))
                }
                result.exitCode != 0 -> {
                    remoteRuntime.operation(workspaceId, RemoteOperationOutcome.COMMAND_FAILED,
                        workspaceStrings.getString(R.string.workspace_directory_check_exit, result.exitCode), result.exitCode, revision)
                    error(workspaceStrings.getString(R.string.workspace_remote_directory_unavailable))
                }
                else -> remoteRuntime.operation(workspaceId, RemoteOperationOutcome.SUCCESS,
                    revision = revision)
            }
            opened.resolvedRoot
        } catch (error: CancellationException) {
            if (stage == RemoteConnectionStage.OPERATING) remoteRuntime.operation(workspaceId,
                RemoteOperationOutcome.OUTCOME_UNKNOWN, workspaceStrings.getString(R.string.workspace_cancelled_outcome_unknown),
                revision = revision)
            remoteRuntime.forgetWorkspace(workspaceId)
            throw error
        } catch (error: RemoteWorkspaceDirectoryException) {
            if (stage == RemoteConnectionStage.CONNECTING) {
                remoteRuntime.directoryFailed(hostId, workspaceId, workspaceStrings.getString(R.string.workspace_remote_directory_unavailable), revision)
                stage = RemoteConnectionStage.FINISHED
            }
            remoteRuntime.forgetWorkspace(workspaceId)
            throw error
        } catch (error: Exception) {
            if (stage == RemoteConnectionStage.CONNECTING) {
                remoteRuntime.connectionFailed(hostId, workspaceId, shortConnectionReason(error),
                    revision)
                stage = RemoteConnectionStage.FINISHED
            }
            remoteRuntime.forgetWorkspace(workspaceId)
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { lease?.close() } }
            when (stage) {
                RemoteConnectionStage.CONNECTING -> remoteRuntime.abandoned(hostId, workspaceId, revision)
                RemoteConnectionStage.OPERATING -> remoteRuntime.completed(hostId, workspaceId, revision)
                RemoteConnectionStage.FINISHED -> Unit
            }
        }
        require(resolvedPath != "/") {
            workspaceStrings.getString(R.string.workspace_error_root_path)
        }
        if (hostDao.getById(hostId)?.connectionRevision != revision) {
            remoteRuntime.forgetWorkspace(workspaceId)
            throw WorkspaceToolTargetChangedException()
        }
        val now = System.currentTimeMillis()
        return WorkspaceEntity(
            id = workspaceId,
            name = finalName,
            root = UUID.randomUUID().toString(), // local storage identity, never a remote path
            shellStatus = WorkspaceShellStatus.READY.name,
            createdAt = now,
            updatedAt = now,
            kind = WorkspaceEntity.KIND_REMOTE,
            remoteHostId = hostId,
            remotePath = resolvedPath,
        ).also {
            dao.upsert(it)
            if (it.remotePath != path) remoteConnections.invalidate(workspaceId)
        }
    }

    private fun authType(authentication: RemoteAuthentication): String = when (authentication) {
        is RemoteAuthentication.Password -> "PASSWORD"
        is RemoteAuthentication.PrivateKey -> "PRIVATE_KEY"
    }

    suspend fun exportSshPrivateKey(id: String, passphrase: String? = null): String {
        val stored = loadVerifiedSshKey(id)
        return withContext(Dispatchers.Default) { SshKeyCodec.exportPrivateKey(stored.pem, passphrase) }
    }

    private suspend fun requireUsableSshKey(id: String) {
        require(hasSshKeyMaterial(id)) {
            workspaceStrings.getString(R.string.workspace_error_key_material_missing)
        }
    }

    private suspend fun loadVerifiedSshKey(id: String): RemoteAuthentication.PrivateKey {
        val key = sshKeyDao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_key_not_found))
        val stored = withContext(Dispatchers.IO) { sshKeyCredentialStore.load(id) }
        val material = withContext(Dispatchers.Default) {
            SshKeyCodec.importPrivateKey(stored.pem)
        }
        require(material.algorithm == key.algorithm && material.fingerprint == key.fingerprint &&
            material.publicKey == key.publicKey
        ) { workspaceStrings.getString(R.string.workspace_error_key_metadata_mismatch) }
        return stored
    }

    private suspend fun invalidateRemoteHostWorkspaces(hostId: String) {
        val workspaceIds = dao.getByRemoteHostId(hostId).mapTo(mutableSetOf()) { it.id }
        workspaceIds.forEach { id ->
            closeRemoteTerminals(id)
            remoteConnections.invalidate(id)
        }
        hostDao.getById(hostId)?.let { host ->
            remoteRuntime.revisionChanged(hostId, host.connectionRevision, workspaceIds)
            remoteRuntime.configuration(hostId,
                if (host.trustedHostKeySha256 == null) RemoteConfigurationState.HOST_KEY_UNTRUSTED
                else RemoteConfigurationState.READY,
                host.connectionRevision)
        }
        dao.updateShellStatusByHostId(
            hostId, WorkspaceShellStatus.DISABLED.name, System.currentTimeMillis(),
        )
        dao.clearToolApprovalsByHostId(hostId)
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map { assistant ->
                if (assistant.workspaceId?.toString()?.let(workspaceIds::contains) == true) {
                    assistant.withWorkspaceShellApproval(true).copy(
                        workspaceShellApprovalTarget = null,
                        workspacePermissionRevision = UUID.randomUUID().toString(),
                    )
                } else assistant
            })
        }
    }

    private suspend fun RemoteHostEntity.config(remoteRoot: String = "/"): RemoteHostConfig {
        val authentication = if (authType == AUTH_MANAGED_KEY) {
            val keyId = requireNotNull(sshKeyId) { workspaceStrings.getString(R.string.workspace_error_key_not_selected) }
            loadVerifiedSshKey(keyId)
        } else withContext(Dispatchers.IO) { credentialStore.load(id) }
        return RemoteHostConfig(
            host = host,
            port = port,
            username = username,
            authentication = authentication,
            trustedHostKeySha256 = requireNotNull(trustedHostKeySha256) { workspaceStrings.getString(R.string.workspace_fingerprint_not_confirmed) },
            remoteRoot = remoteRoot,
        )
    }

    private suspend fun <T> withRemoteWorkspace(
        workspace: WorkspaceEntity,
        expectedTarget: WorkspaceToolTargetSnapshot? = null,
        recordOperation: Boolean = true,
        block: (RemoteWorkspaceSession) -> T,
    ): T {
        validateLoadedWorkspace(expectedTarget, workspace)
        val hostId = requireNotNull(workspace.remoteHostId)
        val host = hostDao.getById(hostId)
            ?: error(workspaceStrings.getString(R.string.workspace_error_host_missing, workspace.id))
        val revision = host.connectionRevision
        remoteRuntime.begin(hostId, workspace.id, revision)
        var stage = RemoteConnectionStage.CONNECTING
        var lease: RemoteWorkspaceConnectionPool<RemoteWorkspaceSession>.Lease? = null
        try {
            if (expectedTarget != null && host.connectionRevision != expectedTarget.hostConnectionRevision) {
                throw WorkspaceToolTargetChangedException()
            }
            validateWorkspaceToolTarget(expectedTarget, workspace.id)
            val config = try {
                host.config(requireNotNull(workspace.remotePath))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val configurationState = when {
                    host.trustedHostKeySha256 == null -> RemoteConfigurationState.HOST_KEY_UNTRUSTED
                    error is IllegalArgumentException && error.message?.startsWith("Invalid SSH") == true ->
                        RemoteConfigurationState.INVALID
                    else -> RemoteConfigurationState.CREDENTIAL_MISSING
                }
                remoteRuntime.configurationFailed(hostId, workspace.id, configurationState,
                    configurationState.shortReason(), revision)
                stage = RemoteConnectionStage.FINISHED
                throw error
            }
            val opened = openLeasedConnection(remoteIdentity(workspace, host), config,
                verifyPath = false)
                .also { lease = it }.session
            if (opened.resolvedRoot != workspace.remotePath) {
                remoteRuntime.directoryFailed(hostId, workspace.id, workspaceStrings.getString(R.string.workspace_remote_directory_changed), revision)
                stage = RemoteConnectionStage.FINISHED
                throw WorkspaceToolTargetChangedException()
            }
            stage = RemoteConnectionStage.OPERATING
            remoteRuntime.connected(hostId, workspace.id, revision)
            if (hostDao.getById(hostId)?.connectionRevision != revision ||
                dao.getById(workspace.id)?.let {
                    it.isRemote && it.remoteHostId == hostId && it.remotePath == workspace.remotePath
                } != true) throw WorkspaceToolTargetChangedException()
            validateWorkspaceToolTarget(expectedTarget, workspace.id)
            val result = runRemoteOperation(opened) { block(opened) }
            if (!recordOperation) {
                return result
            }
            if (result is WorkspaceCommandResult) {
                val outcome = when {
                    result.timedOut -> RemoteOperationOutcome.OUTCOME_UNKNOWN
                    result.exitCode != 0 -> RemoteOperationOutcome.COMMAND_FAILED
                    else -> RemoteOperationOutcome.SUCCESS
                }
                remoteRuntime.operation(workspace.id, outcome,
                    reason = when (outcome) {
                        RemoteOperationOutcome.OUTCOME_UNKNOWN -> workspaceStrings.getString(R.string.workspace_command_timeout_unknown)
                        RemoteOperationOutcome.COMMAND_FAILED -> workspaceStrings.getString(R.string.workspace_command_exit_code, result.exitCode)
                        else -> null
                    }, exitCode = result.exitCode, revision = revision)
            } else {
                remoteRuntime.operation(workspace.id, RemoteOperationOutcome.SUCCESS,
                    revision = revision)
            }
            return result
        } catch (error: CancellationException) {
            if (stage == RemoteConnectionStage.OPERATING && recordOperation) {
                remoteRuntime.operation(workspace.id, RemoteOperationOutcome.OUTCOME_UNKNOWN,
                    workspaceStrings.getString(R.string.workspace_cancelled_outcome_unknown), revision = revision)
            }
            throw error
        } catch (error: RemoteWorkspaceDirectoryException) {
            if (stage == RemoteConnectionStage.CONNECTING) {
                remoteRuntime.directoryFailed(hostId, workspace.id, workspaceStrings.getString(R.string.workspace_remote_directory_unavailable), revision)
                stage = RemoteConnectionStage.FINISHED
            }
            throw error
        } catch (error: WorkspaceToolTargetChangedException) {
            remoteConnections.invalidate(workspace.id)
            throw error
        } catch (error: Exception) {
            if (stage == RemoteConnectionStage.CONNECTING) {
                remoteRuntime.connectionFailed(hostId, workspace.id,
                    reason = shortConnectionReason(error), revision = revision)
                stage = RemoteConnectionStage.FINISHED
            } else if (stage == RemoteConnectionStage.OPERATING) {
                val transportLost = lease?.session?.isConnected == false
                val outcomeUnknown = transportLost || error is JSchException ||
                    error is SftpException || error is RemoteFileTimeoutException
                if (transportLost) {
                    lease?.session?.let { remoteConnections.connectionLost(workspace.id, it) }
                    remoteRuntime.connectionLost(hostId,
                        workspaceStrings.getString(R.string.workspace_ssh_interrupted), revision)
                }
                if (recordOperation) remoteRuntime.operation(workspace.id,
                    if (outcomeUnknown) RemoteOperationOutcome.OUTCOME_UNKNOWN
                    else RemoteOperationOutcome.FILE_FAILED,
                    reason = when {
                        transportLost -> workspaceStrings.getString(R.string.workspace_connection_lost_unknown)
                        error is RemoteFileTimeoutException -> workspaceStrings.getString(R.string.workspace_remote_file_timeout)
                        outcomeUnknown -> workspaceStrings.getString(R.string.workspace_connection_lost_unknown)
                        else -> workspaceStrings.getString(R.string.workspace_file_or_command_failed)
                    },
                    revision = revision)
            }
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { lease?.close() } }
            when (stage) {
                RemoteConnectionStage.CONNECTING -> remoteRuntime.abandoned(hostId, workspace.id, revision)
                RemoteConnectionStage.OPERATING -> remoteRuntime.completed(hostId, workspace.id, revision)
                RemoteConnectionStage.FINISHED -> Unit
            }
        }
    }

    /** Manual user terminal: uses the SSH account's permissions, independently of AI tool grants. */
    suspend fun openRemoteTerminal(
        id: String,
        columns: Int = 80,
        rows: Int = 24,
        expectedHostId: String? = null,
        expectedHostRevision: String? = null,
        expectedRemoteRoot: String? = null,
    ): RemoteTerminalConnection {
        require(columns in 1..1000 && rows in 1..1000) { workspaceStrings.getString(R.string.workspace_error_terminal_size) }
        val workspace = dao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_not_found))
        require(workspace.isRemote) { workspaceStrings.getString(R.string.workspace_remote_required) }
        val hostId = requireNotNull(workspace.remoteHostId)
        val host = hostDao.getById(hostId) ?: error(workspaceStrings.getString(R.string.workspace_remote_host_not_found))
        val revision = host.connectionRevision
        if ((expectedHostId != null && expectedHostId != hostId) ||
            (expectedHostRevision != null && expectedHostRevision != revision) ||
            (expectedRemoteRoot != null && expectedRemoteRoot != workspace.remotePath)
        ) throw WorkspaceToolTargetChangedException()
        remoteRuntime.begin(hostId, id, revision)
        var lease: RemoteWorkspaceConnectionPool<RemoteWorkspaceSession>.Lease? = null
        var terminal: RemoteTerminalSession? = null
        var handedOff = false
        var classified = false
        try {
            val config = try {
                host.config(requireNotNull(workspace.remotePath))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val state = if (host.trustedHostKeySha256 == null) RemoteConfigurationState.HOST_KEY_UNTRUSTED
                    else RemoteConfigurationState.CREDENTIAL_MISSING
                remoteRuntime.configurationFailed(hostId, id, state, state.shortReason(), revision)
                classified = true
                throw error
            }
            val opened = openLeasedConnection(remoteIdentity(workspace, host), config,
                verifyPath = false)
                .also { lease = it }.session
            if (opened.resolvedRoot != workspace.remotePath ||
                hostDao.getById(hostId)?.connectionRevision != revision ||
                dao.getById(id)?.let { it.isRemote && it.remoteHostId == hostId && it.remotePath == workspace.remotePath } != true
            ) throw WorkspaceToolTargetChangedException()
            val pty = runRemoteOperation(opened) {
                opened.openTerminal(columns, rows).also { terminal = it }
            }
            currentCoroutineContext().ensureActive()
            // Metadata may change during the PTY handshake; never hand off an unexpected target.
            if (hostDao.getById(hostId)?.connectionRevision != revision ||
                dao.getById(id)?.let { it.isRemote && it.remoteHostId == hostId && it.remotePath == workspace.remotePath } != true
            ) throw WorkspaceToolTargetChangedException()
            remoteRuntime.connected(hostId, id, revision)
            lateinit var connection: RemoteTerminalConnection
            connection = RemoteTerminalConnection(pty) {
                synchronized(terminalLock) {
                    activeTerminals[id]?.remove(connection)
                    if (activeTerminals[id].isNullOrEmpty()) activeTerminals.remove(id)
                }
                try { lease?.close() } finally { remoteRuntime.completed(hostId, id, revision) }
            }
            synchronized(terminalLock) { activeTerminals.getOrPut(id, ::mutableSetOf) += connection }
            handedOff = true
            return connection
        } catch (error: CancellationException) {
            throw error
        } catch (error: WorkspaceToolTargetChangedException) {
            remoteConnections.invalidate(id)
            throw error
        } catch (error: RemoteWorkspaceDirectoryException) {
            remoteRuntime.directoryFailed(hostId, id, workspaceStrings.getString(R.string.workspace_remote_directory_unavailable), revision)
            classified = true
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (!classified) {
                remoteRuntime.connectionFailed(hostId, id, shortConnectionReason(error), revision)
                classified = true
            }
            throw error
        } finally {
            if (!handedOff) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { terminal?.close() }
                    runCatching { lease?.close() }
                }
                if (!classified) remoteRuntime.abandoned(hostId, id, revision)
            }
        }
    }

    private enum class RemoteConnectionStage { CONNECTING, OPERATING, FINISHED }

    private fun RemoteConfigurationState.shortReason(): String = when (this) {
        RemoteConfigurationState.HOST_KEY_UNTRUSTED -> workspaceStrings.getString(R.string.workspace_fingerprint_not_confirmed)
        RemoteConfigurationState.CREDENTIAL_MISSING -> workspaceStrings.getString(R.string.workspace_ssh_credentials_unavailable)
        RemoteConfigurationState.INVALID -> workspaceStrings.getString(R.string.workspace_connection_config_invalid)
        else -> ""
    }

    private fun shortConnectionReason(error: Exception): String = when {
        error is RemoteFileTimeoutException -> workspaceStrings.getString(R.string.workspace_remote_file_unresponsive)
        error is JSchException && error.message?.contains("auth", ignoreCase = true) == true ->
            workspaceStrings.getString(R.string.workspace_ssh_auth_failed)
        error is JSchException && error.message?.contains("timeout", ignoreCase = true) == true ->
            workspaceStrings.getString(R.string.workspace_ssh_timeout)
        else -> workspaceStrings.getString(R.string.workspace_ssh_failed)
    }

    private fun requireRemoteFilesArea(area: WorkspaceStorageArea) {
        require(area == WorkspaceStorageArea.FILES) {
            workspaceStrings.getString(R.string.workspace_error_remote_files_area)
        }
    }

    private fun remotePath(workspace: WorkspaceEntity, path: String): String {
        return mapRemoteRootfsPath(requireNotNull(workspace.remotePath), path)
    }

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            if (workspace.isRemote) continue
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun create(name: String): WorkspaceEntity = localWorkspaceCreator.create(name)

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            workspaceStrings.getString(R.string.workspace_error_name_duplicate, finalName)
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun installRootfs(
        id: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        require(!workspace.isRemote) { workspaceStrings.getString(R.string.workspace_error_remote_rootfs) }
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                val source = RootfsCatalog.forAndroidAbis(Build.SUPPORTED_ABIS.toList())
                rootfsInstaller.install(workspace.root, source, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}", e)
            // Installer swaps atomically and restores the previous Rootfs on failure. Preserve the
            // prior state when that rollback left a usable installation in place.
            if (withContext(Dispatchers.IO) { manager.hasRootfs(workspace.root) }) {
                restoreShellState(workspace)
            } else {
                updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            }
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        scopeId: String? = null,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        if (workspace.isRemote) {
            requireRemoteFilesArea(area)
            return@withContext withRemoteWorkspace(workspace) { it.list(path) }
        }
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        manager.listFiles(workspace.root, path, area, scope)
    }

    suspend fun readText(
        id: String,
        path: String,
        scopeId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_workspace_not_found, id))
        if (workspace.isRemote) return@withContext withRemoteWorkspace(workspace) { it.readText(path) }
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        manager.readText(workspace.root, path, scope = scope)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
        scopeId: String? = null,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_workspace_not_found, id))
        if (workspace.isRemote) return@withContext withRemoteWorkspace(workspace) {
            it.writeText(path, text, overwrite)
        }
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        manager.writeText(workspace.root, path, text, overwrite, scope = scope)
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        scopeId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_workspace_not_found, id))
        if (workspace.isRemote) {
            requireRemoteFilesArea(area)
            return@withContext withRemoteWorkspace(workspace) { it.readText(path, MAX_PREVIEW_BYTES) }
        }
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path, scope = scope)
            WorkspaceStorageArea.LINUX,
            WorkspaceStorageArea.HOME,
            WorkspaceStorageArea.TEMP,
            WorkspaceStorageArea.VAR_TEMP,
                -> {
                val size = manager.fileSize(workspace.root, path, area, scope)
                require(size <= MAX_PREVIEW_BYTES) {
                    workspaceStrings.getString(R.string.workspace_file_preview_too_large, size)
                }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out, scope = scope)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    /** Read a UTF-8 file by its absolute Rootfs path for the unified artifact preview screen. */
    suspend fun readRootfsTextForPreview(
        id: String,
        path: String,
        scopeId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_workspace_not_found, id))
        if (workspace.isRemote) return@withContext withRemoteWorkspace(workspace) {
            it.readText(remotePath(workspace, path), MAX_PREVIEW_BYTES)
        }
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        val size = manager.rootfsFileSize(workspace.root, path, scope)
        require(size <= MAX_PREVIEW_BYTES) {
            workspaceStrings.getString(R.string.workspace_file_in_app_preview_too_large, size)
        }
        ByteArrayOutputStream(size.toInt()).use { out ->
            manager.exportRootfsFile(workspace.root, path, out, scope = scope)
            out.toString(Charsets.UTF_8.name())
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
        scopeId: String? = null,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_workspace_not_found, id))
        if (workspace.isRemote) {
            requireRemoteFilesArea(area)
            val target = if (destinationPath.isBlank()) fileName else "${destinationPath.trimEnd('/')}/$fileName"
            return@withContext withRemoteWorkspace(workspace) {
                it.importFile(target, inputStream, overwrite = false)
            }
        }
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream, scope)
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        scopeId: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_workspace_not_found, id))
        if (workspace.isRemote) {
            requireRemoteFilesArea(area)
            return@withContext withRemoteWorkspace(workspace) { it.fileSize(path) }
        }
        manager.fileSize(workspace.root, path, area, WorkspaceScope.fromNullableId(scopeId))
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
        scopeId: String? = null,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_workspace_not_found, id))
        if (workspace.isRemote) {
            requireRemoteFilesArea(area)
            return@withContext withRemoteWorkspace(workspace) { it.exportFile(path, outputStream) }
        }
        manager.exportFile(
            workspace.root,
            path,
            area,
            outputStream,
            scope = WorkspaceScope.fromNullableId(scopeId),
        )
    }

    /** 按 Rootfs 内绝对路径读取文件大小, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun rootfsFileSize(
        id: String,
        path: String,
        scopeId: String? = null,
        expectedTarget: WorkspaceToolTargetSnapshot? = null,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_workspace_not_found, id))
        validateLoadedWorkspace(expectedTarget, workspace)
        validateWorkspaceToolTarget(expectedTarget, id)
        if (workspace.isRemote) return@withContext withRemoteWorkspace(workspace, expectedTarget) {
            it.fileSize(remotePath(workspace, path))
        }
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        validateWorkspaceToolTarget(expectedTarget, id)
        manager.rootfsFileSize(workspace.root, path, scope)
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
        scopeId: String? = null,
        expectedTarget: WorkspaceToolTargetSnapshot? = null,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_workspace_not_found, id))
        validateLoadedWorkspace(expectedTarget, workspace)
        validateWorkspaceToolTarget(expectedTarget, id)
        if (workspace.isRemote) return@withContext withRemoteWorkspace(workspace, expectedTarget) {
            it.exportFile(remotePath(workspace, path), outputStream)
        }
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        validateWorkspaceToolTarget(expectedTarget, id)
        manager.exportRootfsFile(workspace.root, path, outputStream, scope = scope)
    }

    /** Export a user-selected artifact without the smaller AI tool read limit. */
    suspend fun exportRootfsArtifact(
        id: String,
        path: String,
        outputStream: OutputStream,
        scopeId: String? = null,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_workspace_not_found, id))
        if (workspace.isRemote) return@withContext withRemoteWorkspace(workspace) {
            it.exportFile(remotePath(workspace, path), outputStream)
        }
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        manager.exportRootfsFile(
            workspace.root,
            path,
            outputStream,
            maxBytes = Long.MAX_VALUE,
            scope = scope,
        )
    }

    /** Writes a Rootfs guest path without invoking a shell or following symbolic links. */
    suspend fun writeRootfsText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
        scopeId: String? = null,
        expectedTarget: WorkspaceToolTargetSnapshot? = null,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_workspace_not_found, id))
        validateLoadedWorkspace(expectedTarget, workspace)
        validateWorkspaceToolTarget(expectedTarget, id)
        if (workspace.isRemote) return@withContext withRemoteWorkspace(workspace, expectedTarget) {
            it.writeText(remotePath(workspace, path), text, overwrite).copy(path = path)
        }
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        validateWorkspaceToolTarget(expectedTarget, id)
        manager.writeRootfsText(workspace.root, path, text, overwrite, scope = scope)
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
        scopeId: String? = null,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            if (workspace.isRemote) {
                requireRemoteFilesArea(area)
                return@withContext withRemoteWorkspace(workspace) { it.delete(path, recursive) }
            }
            manager.deleteFile(
                workspace.root,
                path,
                recursive,
                area,
                WorkspaceScope.fromNullableId(scopeId),
            )
        }
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
        scopeId: String? = null,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_workspace_not_found, id))
        if (workspace.isRemote) return@withContext withRemoteWorkspace(workspace) {
            it.move(source, target, overwrite)
        }
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(
            workspace.root,
            source,
            target,
            overwrite,
            WorkspaceScope.fromNullableId(scopeId),
        )
    }

    suspend fun fetchUrl(
        id: String,
        url: String,
        destinationPath: String,
        scopeId: String? = null,
        expectedTarget: WorkspaceToolTargetSnapshot? = null,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_workspace_not_found, id))
        validateLoadedWorkspace(expectedTarget, workspace)
        validateWorkspaceToolTarget(expectedTarget, id)
        // An AI-approved download may spend time on the network. Revalidate the target after
        // downloading and before any workspace write; the broker caps this body at 8 MiB.
        val approvedDownload = expectedTarget?.let {
            networkBroker.fetch(url) { input -> input.readBytes() }
        }
        if (approvedDownload != null) validateWorkspaceToolTarget(expectedTarget, id)
        if (workspace.isRemote) {
            val relative = remotePath(workspace, destinationPath)
            return@withContext withRemoteWorkspace(workspace, expectedTarget) { session ->
                if (approvedDownload != null) session.importFile(relative,
                    approvedDownload.inputStream(), overwrite = false)
                else networkBroker.fetch(url) { input ->
                    session.importFile(relative, input, overwrite = false)
                }
            }
        }
        val guestPath = me.rerere.workspace.GuestPath.parse(destinationPath, "destination_path")
        require(guestPath.isWithin(WorkspaceManager.ROOTFS_WORKSPACE_PATH) &&
            guestPath != WorkspaceManager.ROOTFS_WORKSPACE_PATH
        ) { workspaceStrings.getString(R.string.workspace_error_download_path) }
        val relative = guestPath.relativeTo(WorkspaceManager.ROOTFS_WORKSPACE_PATH)
        val parent = relative.substringBeforeLast('/', "")
        val fileName = relative.substringAfterLast('/')
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        validateWorkspaceToolTarget(expectedTarget, id)
        val importDownloaded: (InputStream) -> WorkspaceFileEntry = { input ->
            manager.importFile(
                root = workspace.root,
                destinationPath = parent,
                fileName = fileName,
                inputStream = input,
                scope = scope,
            )
        }
        if (approvedDownload != null) importDownloaded(approvedDownload.inputStream())
        else networkBroker.fetch(url, importDownloaded)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        scopeId: String? = null,
        expectedTarget: WorkspaceToolTargetSnapshot? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error(workspaceStrings.getString(R.string.workspace_error_workspace_not_found, id))
        validateLoadedWorkspace(expectedTarget, workspace)
        validateWorkspaceToolTarget(expectedTarget, id, requireShell = true)
        if (workspace.isRemote) return withRemoteWorkspace(workspace, expectedTarget) { session ->
            session.execute(
                command = command,
                workingDirectory = cwd,
                timeoutMillis = timeoutMillis,
                stdin = stdin?.inputStream(),
            )
        }
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            val scope = WorkspaceScope.fromNullableId(scopeId)
            manager.ensureScope(workspace.root, scope)
            // The already loaded root is the exact target represented by expectedTarget.
            manager.executeCommand(workspace.root, command, cwd, timeoutMillis, stdin, scope)
        }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        if (workspace.isRemote) {
            closeRemoteTerminals(id)
            remoteConnections.forget(id)
        }
        dao.deleteById(id)
        if (!workspace.isRemote) withContext(Dispatchers.IO) {
            manager.deleteWorkspace(workspace.root)
        }
        cleanupAssistantReferences(id)
        return true
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.withWorkspaceBinding(null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
        private const val AUTH_MANAGED_KEY = "MANAGED_KEY"
    }
}
