package me.ayuilos.miffan.data.ai.computer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ayuilos.miffan.COMPUTER_NOTIFICATION_CHANNEL_ID
import me.ayuilos.miffan.R
import me.ayuilos.miffan.RouteActivity
import me.ayuilos.miffan.data.repository.RemoteCommandOutcome
import me.ayuilos.miffan.data.repository.RemoteCuaUpdateCheck
import me.ayuilos.miffan.data.repository.RemoteMachineProbe
import me.ayuilos.miffan.data.repository.RemoteScreenRepository
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.utils.NotificationUtil

/** Intent extra naming the computer whose status page a notification opens. */
const val COMPUTER_STATUS_EXTRA = "computerStatus"

/** Reuse a computer's update check this long before asking GitHub again. */
private const val CHECK_REUSE_MILLIS = 30 * 60_000L
/** How often a waiting update looks whether the partner is done with the computer. */
private const val WAIT_POLL_MILLIS = 5_000L

/** One computer's cua-driver update, from the user's tap to its outcome. */
sealed interface CuaUpdateState {
    /** Starts once [partner]'s turn is done with the computer. */
    data class Waiting(val partner: String) : CuaUpdateState
    /** Installing, then reading the computer again ([verifying]); [target] is the release it goes to, when known. */
    data class Running(val target: String?, val verifying: Boolean) : CuaUpdateState
    /** Done; [probe] was read after the update. */
    data class Updated(val probe: RemoteMachineProbe) : CuaUpdateState
    /**
     * Not done. [probe] was read after the attempt (null: the computer did not answer); when its driver
     * still works at [previous], nothing changed for partners.
     */
    data class Failed(val output: String, val probe: RemoteMachineProbe?, val previous: String?) : CuaUpdateState {
        val stillWorks: Boolean get() = probe?.cua?.ok == true
        /** The computer could not download the release (GitHub unreachable from its network). */
        val unreachable: Boolean get() = NETWORK_FAILURES.containsMatchIn(output)
    }
}

/** Installer output that means the download never reached GitHub; nothing on the computer changed. */
private val NETWORK_FAILURES = Regex("Cua download failed|Could not resolve host|Failed to connect|Could not connect|Connection timed out|Could not reach GitHub", RegexOption.IGNORE_CASE)

/** A computer's update check and the identity it was read from. */
data class CuaUpdateCheck(val result: RemoteCuaUpdateCheck, val revision: String, val checkedAt: Long)

/**
 * Checks for and runs cua-driver updates per computer, outside any page: an update keeps going when
 * the user leaves, waits for a partner's turn to finish first, keeps partners off the computer while
 * the driver restarts, and posts a notification when the user is not watching.
 */
class CuaUpdates(
    private val context: Context,
    private val scope: CoroutineScope,
    private val screens: RemoteScreenRepository,
    private val control: RemoteComputerControl,
    private val registry: RemoteComputerRegistry,
    private val workspaces: WorkspaceRepository,
    private val statuses: ComputerStatusStore,
) {
    private val _states = MutableStateFlow<Map<String, CuaUpdateState>>(emptyMap())
    val states: StateFlow<Map<String, CuaUpdateState>> = _states.asStateFlow()

    private val _checks = MutableStateFlow<Map<String, CuaUpdateCheck>>(emptyMap())
    val checks: StateFlow<Map<String, CuaUpdateCheck>> = _checks.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()
    private val watchers = mutableMapOf<String, Int>()

    /**
     * Whether a newer cua-driver exists for [hostId]: a check from the last half hour unless [fresh].
     * Null when the computer could not ask (offline, GitHub unreachable); that is never a problem to show.
     */
    suspend fun check(hostId: String, revision: String, fresh: Boolean = false): RemoteCuaUpdateCheck? {
        _checks.value[hostId]?.takeIf { !fresh && it.revision == revision && System.currentTimeMillis() - it.checkedAt < CHECK_REUSE_MILLIS }
            ?.let { return it.result }
        val result = try {
            screens.checkCuaUpdateOnHost(hostId, revision)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (result.latestVersion.isNullOrBlank()) return null
        _checks.update { it + (hostId to CuaUpdateCheck(result, revision, System.currentTimeMillis())) }
        return result
    }

    /**
     * Updates cua-driver on [hostId] (installed at [installedPath], now at [previous]) to [target]; the
     * caller has the user's confirmation. Waits while a partner uses the computer. One update per computer.
     */
    fun start(hostId: String, revision: String, installedPath: String?, previous: String?, target: String?) = synchronized(jobs) {
        if (jobs[hostId]?.isActive == true) return@synchronized
        jobs[hostId] = scope.launch { run(hostId, revision, installedPath, previous, target) }
    }

    /** Gives up an update still waiting for a partner; one already running finishes. */
    fun cancelWaiting(hostId: String) = synchronized(jobs) {
        if (_states.value[hostId] !is CuaUpdateState.Waiting) return@synchronized
        jobs.remove(hostId)?.cancel()
        _states.update { it - hostId }
    }

    /** Forgets a finished update's outcome once the user has seen it. */
    fun clear(hostId: String) = _states.update { states ->
        if (states[hostId] is CuaUpdateState.Updated || states[hostId] is CuaUpdateState.Failed) states - hostId else states
    }

    /** The user is looking at [hostId]'s status; no notification while any watcher remains. */
    fun watch(hostId: String): () -> Unit {
        synchronized(watchers) { watchers[hostId] = (watchers[hostId] ?: 0) + 1 }
        return { synchronized(watchers) { watchers[hostId] = ((watchers[hostId] ?: 1) - 1).coerceAtLeast(0) } }
    }

    private suspend fun run(hostId: String, revision: String, installedPath: String?, previous: String?, target: String?) {
        while (true) {
            val partner = control.beginUpdate(hostId) ?: break
            _states.update { it + (hostId to CuaUpdateState.Waiting(partner.name)) }
            delay(WAIT_POLL_MILLIS)
        }
        val state = try {
            _states.update { it + (hostId to CuaUpdateState.Running(target, verifying = false)) }
            // Open MCP sessions run the old driver; the next partner action starts a fresh one.
            workspaces.listFlow().first().filter { it.isRemote && it.remoteHostId == hostId }.forEach { registry.closeWorkspace(it.id) }
            val outcome = try {
                screens.installCuaDriverOnHost(hostId, revision)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                RemoteCommandOutcome(false, error.message.orEmpty())
            }
            _states.update { it + (hostId to CuaUpdateState.Running(target, verifying = true)) }
            val probe = try {
                screens.probeHost(hostId, revision)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            // An installer that left the same release in place did not update anything.
            val changed = previous == null || probe?.cua?.version != previous
            if (outcome.success && probe?.cua?.ok == true && changed) CuaUpdateState.Updated(probe)
            else CuaUpdateState.Failed(outcome.output, probe, previous)
        } finally {
            control.endUpdate(hostId)
        }
        if (state is CuaUpdateState.Updated) {
            _checks.update { it - hostId }
            statuses.update(hostId) { it.copy(checkedAt = System.currentTimeMillis(), updateTo = null) }
        }
        _states.update { it + (hostId to state) }
        notifyOutcome(hostId, state)
    }

    private suspend fun notifyOutcome(hostId: String, state: CuaUpdateState) {
        if (synchronized(watchers) { (watchers[hostId] ?: 0) > 0 }) return
        val name = workspaces.getHostById(hostId)?.name ?: return
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(COMPUTER_STATUS_EXTRA, hostId)
        }
        val pending = PendingIntent.getActivity(context, hostId.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val (heading, text) = when (state) {
            is CuaUpdateState.Updated -> context.getString(R.string.computer_update_done_title) to
                context.getString(R.string.computer_update_done_text, name)
            is CuaUpdateState.Failed -> context.getString(R.string.computer_update_failed_title) to
                context.getString(if (state.stillWorks) R.string.computer_update_failed_text_works else R.string.computer_update_failed_text, name)
            else -> return
        }
        NotificationUtil.notify(context, COMPUTER_NOTIFICATION_CHANNEL_ID, ("cua:$hostId").hashCode()) {
            title = heading
            content = text
            autoCancel = true
            category = NotificationCompat.CATEGORY_STATUS
            contentIntent = pending
        }
    }
}
