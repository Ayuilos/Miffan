package me.ayuilos.miffan.data.repository.relay

import android.os.Build
import java.io.ByteArrayInputStream
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.db.dao.RemoteHostDAO
import me.ayuilos.miffan.data.db.entity.RemoteHostRelay
import me.ayuilos.miffan.data.db.entity.encodeRemoteHostRelay
import me.ayuilos.miffan.data.repository.RemoteMiffanHelper
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.data.repository.screenHelperCommand
import me.rerere.workspace.RemoteWorkspaceSession
import me.rerere.workspace.relay.RelayEndpoint
import me.rerere.workspace.relay.RelayIds
import me.rerere.workspace.relay.RelayInfo

/** The relay's official instance: same open-source code as any self-hosted relay (RELAY_PLAN §1). */
const val OFFICIAL_RELAY_URL = "https://relay.miffan.ayuilos.me"

/** `relay status` from the helper (remote-helper/PROTOCOL.md). Unknown fields are ignored. */
@Serializable
data class RelayConnectorStatus(
    val installed: Boolean = false,
    val service: String = "",
    val version: String = "",
    val hostId: String = "",
    val relay: String = "",
    val running: Boolean = false,
    val connected: Boolean = false,
    val lastError: String? = null,
    val linger: Boolean? = null,
    val devices: List<RelayConnectorDevice> = emptyList(),
)

@Serializable
data class RelayConnectorDevice(val id: String, val name: String = "", val addedAt: String = "")

@Serializable
private data class RelayPairResult(val hostId: String, val relay: String = "", val deviceId: String = "")

/** Thrown when a helper `relay` command fails; [reason] is the helper's reason code when it gave one. */
class RelaySetupException(val reason: String?, message: String) : IllegalStateException(message)

/**
 * Sets a computer up for the relay over its existing SSH connection (normally on the home
 * network): installs and starts the connector, allows this phone, and stores the relay on the
 * computer's record. The phone's device key never leaves Android Keystore; only its public key
 * is sent.
 */
class RemoteRelaySetup internal constructor(
    private val workspaces: WorkspaceRepository,
    private val hostDao: RemoteHostDAO,
    private val access: RelayAccess,
    private val helper: RemoteMiffanHelper,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Checks the relay first, so a wrong address fails before anything is installed. */
    suspend fun checkRelay(endpoint: RelayEndpoint): RelayInfo = withContext(Dispatchers.IO) {
        access.client.info(endpoint, refresh = true)
    }

    suspend fun status(workspaceId: String): RelayConnectorStatus = withRemote(workspaceId) { ssh ->
        helper.ensureInstalled(ssh)
        json.decodeFromString<RelayConnectorStatus>(run(ssh, "relay status", null))
    }

    /**
     * Installs the connector for [endpoint], allows this phone and saves the relay on [hostId].
     * Idempotent: running it again on a set-up computer only refreshes the pairing.
     */
    suspend fun enable(workspaceId: String, hostId: String, endpoint: RelayEndpoint): RemoteHostRelay {
        checkRelay(endpoint)
        val devicePub = withContext(Dispatchers.IO) { access.deviceKeys.signer().publicKeySpki }
        val host = hostDao.getById(hostId) ?: throw RelaySetupException(null, "Computer not found")
        val paired = withRemote(workspaceId) { ssh ->
            helper.ensureInstalled(ssh)
            run(ssh, "relay install", buildJsonObject {
                put("relay", endpoint.baseUrl)
                endpoint.accessToken?.takeIf { it.isNotEmpty() }?.let { put("accessToken", it) }
            }.toString())
            json.decodeFromString<RelayPairResult>(run(ssh, "relay pair", buildJsonObject {
                put("device", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(devicePub))
                put("name", deviceName())
            }.toString()))
        }
        if (!RelayIds.isHostId(paired.hostId)) throw RelaySetupException("bad_host_id", "The connector returned an invalid id")
        val relay = RemoteHostRelay(endpoint.baseUrl, paired.hostId, endpoint.accessToken?.takeIf { it.isNotEmpty() })
        if (hostDao.updateRelay(host.id, encodeRemoteHostRelay(relay), host.connectionRevision, System.currentTimeMillis()) == 0) {
            throw RelaySetupException("changed", "The computer's connection changed during setup")
        }
        return relay
    }

    /** Proves the relay route end to end; see [WorkspaceRepository.probeRelay]. */
    suspend fun probe(hostId: String): RelayProbe = workspaces.probeRelay(hostId)

    /** Turns the relay route on or off for this phone without touching the computer. */
    suspend fun setEnabled(hostId: String, enabled: Boolean) {
        val host = hostDao.getById(hostId) ?: return
        val relay = host.relay ?: return
        hostDao.updateRelay(host.id, encodeRemoteHostRelay(relay.copy(enabled = enabled)), host.connectionRevision,
            System.currentTimeMillis())
    }

    /** Removes this phone from the computer's connector and forgets the relay on this phone. */
    suspend fun forget(workspaceId: String, hostId: String) {
        val deviceId = withContext(Dispatchers.IO) { access.deviceKeys.deviceId() }
        withRemote(workspaceId) { ssh ->
            helper.ensureInstalled(ssh)
            run(ssh, "relay unpair", buildJsonObject { put("deviceId", deviceId) }.toString())
        }
        val host = hostDao.getById(hostId) ?: return
        hostDao.updateRelay(host.id, "", host.connectionRevision, System.currentTimeMillis())
    }

    private fun run(ssh: RemoteWorkspaceSession, operation: String, stdin: String?): String {
        val result = ssh.execute(screenHelperCommand(ssh.platform, operation), timeoutMillis = 180_000,
            stdin = stdin?.let { ByteArrayInputStream(it.toByteArray(Charsets.UTF_8)) })
        val line = result.stdout.trim().lines().lastOrNull().orEmpty()
        if (result.exitCode != 0 || result.timedOut) {
            val reason = runCatching { json.decodeFromString<HelperFailure>(line) }.getOrNull()
            throw RelaySetupException(reason?.reason, reason?.detail ?: result.stderr.trim().ifEmpty { "relay $operation failed" })
        }
        return line
    }

    @Serializable
    private data class HelperFailure(val reason: String? = null, val detail: String? = null)

    private suspend fun <T> withRemote(workspaceId: String, block: (RemoteWorkspaceSession) -> T): T {
        var value: Result<T>? = null
        workspaces.openLeasedRemote(workspaceId) { ssh ->
            value = runCatching { block(ssh) }
            Closeable {}
        }.close()
        return requireNotNull(value).getOrThrow()
    }

    private fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ")
            .filter { !it.isISOControl() }.take(64).ifBlank { "Phone" }
}
