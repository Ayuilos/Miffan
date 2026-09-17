package me.ayuilos.miffan.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A connection is opened only for a test or operation. IDLE never means "online". */
enum class RemoteConnectionActivity { IDLE, CONNECTING, OPERATING }

enum class RemoteConfigurationState {
    UNKNOWN,
    READY,
    HOST_KEY_UNTRUSTED,
    CREDENTIAL_MISSING,
    INVALID,
}

data class RemoteCheckRecord(
    val timestampMillis: Long,
    val success: Boolean,
    val reason: String? = null,
)

enum class RemoteOperationOutcome { SUCCESS, COMMAND_FAILED, FILE_FAILED, OUTCOME_UNKNOWN }

data class RemoteOperationRecord(
    val timestampMillis: Long,
    val outcome: RemoteOperationOutcome,
    val reason: String? = null,
    val exitCode: Int? = null,
)

data class RemoteHostRuntimeState(
    val configuration: RemoteConfigurationState = RemoteConfigurationState.UNKNOWN,
    val configurationReason: String? = null,
    val activity: RemoteConnectionActivity = RemoteConnectionActivity.IDLE,
    val lastConnection: RemoteCheckRecord? = null,
)

data class RemoteWorkspaceRuntimeState(
    val activity: RemoteConnectionActivity = RemoteConnectionActivity.IDLE,
    val lastDirectoryCheck: RemoteCheckRecord? = null,
    val lastOperation: RemoteOperationRecord? = null,
)

/** Tracks process-local facts only; it never polls or keeps an SSH session open for status. */
internal class RemoteWorkspaceRuntimeTracker(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class RevisionKey(val id: String, val revision: String)
    private data class ActivityCount(var connecting: Int = 0, var operating: Int = 0) {
        fun activity(): RemoteConnectionActivity = when {
            operating > 0 -> RemoteConnectionActivity.OPERATING
            connecting > 0 -> RemoteConnectionActivity.CONNECTING
            else -> RemoteConnectionActivity.IDLE
        }
    }

    private val hostCounts = mutableMapOf<RevisionKey, ActivityCount>()
    private val workspaceCounts = mutableMapOf<RevisionKey, ActivityCount>()
    private val hostRevisions = mutableMapOf<String, String>()
    private val workspaceRevisions = mutableMapOf<String, String>()
    private val _hostStates = MutableStateFlow<Map<String, RemoteHostRuntimeState>>(emptyMap())
    private val _workspaceStates = MutableStateFlow<Map<String, RemoteWorkspaceRuntimeState>>(emptyMap())
    val hostStates: StateFlow<Map<String, RemoteHostRuntimeState>> = _hostStates.asStateFlow()
    val workspaceStates: StateFlow<Map<String, RemoteWorkspaceRuntimeState>> = _workspaceStates.asStateFlow()

    @Synchronized fun begin(hostId: String, workspaceId: String? = null, revision: String = "legacy") {
        hostRevisions.putIfAbsent(hostId, revision)
        workspaceId?.let { workspaceRevisions.putIfAbsent(it, revision) }
        hostCounts.getOrPut(RevisionKey(hostId, revision), ::ActivityCount).connecting++
        workspaceId?.let {
            workspaceCounts.getOrPut(RevisionKey(it, revision), ::ActivityCount).connecting++
        }
        publishActivity(hostId, workspaceId, revision)
    }

    /** SSH and the selected directory are both verified at this point. */
    @Synchronized fun connected(hostId: String, workspaceId: String? = null, revision: String = "legacy") {
        moveToOperating(hostCounts.getOrPut(RevisionKey(hostId, revision), ::ActivityCount))
        workspaceId?.let {
            moveToOperating(workspaceCounts.getOrPut(RevisionKey(it, revision), ::ActivityCount))
        }
        if (hostRevisions[hostId] != revision) return
        val checked = RemoteCheckRecord(now(), success = true)
        updateHost(hostId) {
            it.copy(configuration = RemoteConfigurationState.READY, configurationReason = null,
                lastConnection = checked)
        }
        workspaceId?.takeIf { workspaceRevisions[it] == revision }?.let { id ->
            updateWorkspace(id) { it.copy(lastDirectoryCheck = checked) }
        }
        publishActivity(hostId, workspaceId, revision)
    }

    /** SSH succeeded, but the selected project directory was inaccessible. */
    @Synchronized fun directoryFailed(hostId: String, workspaceId: String, reason: String,
        revision: String = "legacy",
    ) {
        finishConnecting(hostCounts.getOrPut(RevisionKey(hostId, revision), ::ActivityCount))
        finishConnecting(workspaceCounts.getOrPut(RevisionKey(workspaceId, revision), ::ActivityCount))
        if (hostRevisions[hostId] != revision) return
        val timestamp = now()
        updateHost(hostId) {
            it.copy(configuration = RemoteConfigurationState.READY, configurationReason = null,
                lastConnection = RemoteCheckRecord(timestamp, success = true))
        }
        if (workspaceRevisions[workspaceId] == revision) updateWorkspace(workspaceId) {
            it.copy(lastDirectoryCheck = RemoteCheckRecord(timestamp, success = false, reason))
        }
        publishActivity(hostId, workspaceId, revision)
    }

    @Synchronized fun connectionFailed(hostId: String, workspaceId: String? = null, reason: String,
        revision: String = "legacy",
    ) {
        finishConnecting(hostCounts.getOrPut(RevisionKey(hostId, revision), ::ActivityCount))
        workspaceId?.let {
            finishConnecting(workspaceCounts.getOrPut(RevisionKey(it, revision), ::ActivityCount))
        }
        if (hostRevisions[hostId] != revision) return
        updateHost(hostId) {
            it.copy(lastConnection = RemoteCheckRecord(now(), success = false, reason))
        }
        publishActivity(hostId, workspaceId, revision)
    }

    /** A local configuration problem is not evidence that the remote host is offline. */
    @Synchronized fun configurationFailed(
        hostId: String,
        workspaceId: String? = null,
        state: RemoteConfigurationState,
        reason: String? = null,
        revision: String = "legacy",
    ) {
        require(state != RemoteConfigurationState.READY && state != RemoteConfigurationState.UNKNOWN)
        finishConnecting(hostCounts.getOrPut(RevisionKey(hostId, revision), ::ActivityCount))
        workspaceId?.let {
            finishConnecting(workspaceCounts.getOrPut(RevisionKey(it, revision), ::ActivityCount))
        }
        if (hostRevisions[hostId] != revision) return
        updateHost(hostId) { it.copy(configuration = state, configurationReason = reason) }
        publishActivity(hostId, workspaceId, revision)
    }

    @Synchronized fun completed(hostId: String, workspaceId: String? = null, revision: String = "legacy") {
        finishOperating(hostCounts.getOrPut(RevisionKey(hostId, revision), ::ActivityCount))
        workspaceId?.let {
            finishOperating(workspaceCounts.getOrPut(RevisionKey(it, revision), ::ActivityCount))
        }
        publishActivity(hostId, workspaceId, revision)
    }

    @Synchronized fun abandoned(hostId: String, workspaceId: String? = null, revision: String = "legacy") {
        finishConnecting(hostCounts.getOrPut(RevisionKey(hostId, revision), ::ActivityCount))
        workspaceId?.let {
            finishConnecting(workspaceCounts.getOrPut(RevisionKey(it, revision), ::ActivityCount))
        }
        publishActivity(hostId, workspaceId, revision)
    }

    @Synchronized fun operation(workspaceId: String, outcome: RemoteOperationOutcome,
        reason: String? = null, exitCode: Int? = null, revision: String = "legacy",
    ) {
        if (workspaceRevisions[workspaceId] != revision) return
        updateWorkspace(workspaceId) {
            it.copy(lastOperation = RemoteOperationRecord(now(), outcome, reason, exitCode))
        }
    }

    @Synchronized fun configuration(hostId: String, state: RemoteConfigurationState,
        revision: String = "legacy",
    ) {
        hostRevisions.putIfAbsent(hostId, revision)
        if (hostRevisions[hostId] != revision) return
        updateHost(hostId) { it.copy(configuration = state, configurationReason = null) }
    }

    @Synchronized fun connectionLost(hostId: String, reason: String, revision: String = "legacy") {
        if (hostRevisions[hostId] != revision) return
        updateHost(hostId) {
            it.copy(lastConnection = RemoteCheckRecord(now(), success = false, reason))
        }
    }

    /** Invalidates observations made for the previous target without affecting its in-flight counters. */
    @Synchronized fun revisionChanged(hostId: String, revision: String, workspaceIds: Collection<String>) {
        hostRevisions[hostId] = revision
        _hostStates.value = _hostStates.value + (hostId to RemoteHostRuntimeState())
        workspaceIds.forEach { id ->
            workspaceRevisions[id] = revision
            _workspaceStates.value = _workspaceStates.value + (id to RemoteWorkspaceRuntimeState())
        }
    }

    @Synchronized fun forgetHost(hostId: String) {
        hostRevisions[hostId] = "deleted:${java.util.UUID.randomUUID()}"
        _hostStates.value = _hostStates.value - hostId
    }

    @Synchronized fun forgetWorkspace(workspaceId: String) {
        workspaceRevisions[workspaceId] = "deleted:${java.util.UUID.randomUUID()}"
        _workspaceStates.value = _workspaceStates.value - workspaceId
    }

    private fun moveToOperating(count: ActivityCount) {
        finishConnecting(count)
        count.operating++
    }

    private fun finishConnecting(count: ActivityCount) {
        if (count.connecting > 0) count.connecting--
    }

    private fun finishOperating(count: ActivityCount) {
        if (count.operating > 0) count.operating--
    }

    private fun publishActivity(hostId: String, workspaceId: String?, revision: String) {
        if (hostRevisions[hostId] == revision) updateHost(hostId) {
            it.copy(activity = hostCounts.getValue(RevisionKey(hostId, revision)).activity())
        }
        workspaceId?.takeIf { workspaceRevisions[it] == revision }?.let { id ->
            updateWorkspace(id) {
                it.copy(activity = workspaceCounts.getValue(RevisionKey(id, revision)).activity())
            }
        }
    }

    private fun updateHost(id: String, update: (RemoteHostRuntimeState) -> RemoteHostRuntimeState) {
        _hostStates.value = _hostStates.value + (id to update(_hostStates.value[id] ?: RemoteHostRuntimeState()))
    }

    private fun updateWorkspace(id: String, update: (RemoteWorkspaceRuntimeState) -> RemoteWorkspaceRuntimeState) {
        _workspaceStates.value = _workspaceStates.value +
            (id to update(_workspaceStates.value[id] ?: RemoteWorkspaceRuntimeState()))
    }
}
