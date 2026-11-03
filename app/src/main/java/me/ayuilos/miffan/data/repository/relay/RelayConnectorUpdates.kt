package me.ayuilos.miffan.data.repository.relay

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private const val TAG = "RelayConnectorUpdates"

/**
 * Keeps the relay connector on the user's computers at the version this app pins. A connector only
 * changes when the app ships a new helper, and nothing else would update one installed earlier.
 *
 * Each computer is looked at once per run of the app, the first time it connects directly (a restart of
 * the connector would cut a session through the relay). A failed attempt waits for the next run.
 */
class RelayConnectorUpdates(
    directConnections: Flow<String>,
    /** The computer's own workspace, through which its helper runs; null when it has none. */
    private val workspaceOf: suspend (hostId: String) -> String?,
    private val upgrade: suspend (workspaceId: String) -> Boolean,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val looked = ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch(dispatcher) {
            directConnections.collect { hostId ->
                if (looked.add(hostId)) launch { check(hostId) }
            }
        }
    }

    private suspend fun check(hostId: String) {
        try {
            val workspaceId = workspaceOf(hostId) ?: return
            if (upgrade(workspaceId)) log("Updated the relay connector of $hostId")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("Could not update the relay connector of $hostId: ${error.message}")
        }
    }
}
