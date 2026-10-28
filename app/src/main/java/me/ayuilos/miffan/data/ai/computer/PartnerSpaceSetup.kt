package me.ayuilos.miffan.data.ai.computer

import android.content.res.Resources
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.MessageOrigin
import me.ayuilos.miffan.data.thread.ThreadService
import me.rerere.ai.ui.UIMessagePart

/** Kind of the event that tells a partner its own computer is ready. */
const val PARTNER_SPACE_READY_EVENT = "partner_space_ready"

/** Creating partners' own computers on one host, as the setup screens show it. */
data class SpaceSetupJob(
    val hostId: String,
    val partnerIds: List<Uuid>,
    val stage: Stage = Stage.CREATING,
    val startedAt: Long = System.currentTimeMillis(),
    /** Partner → its new SPACE workspace, as each is created. */
    val created: Map<Uuid, String> = emptyMap(),
    /** Workspace → its network check; null where the check could not run. */
    val network: Map<String, CuaSpaceNetcheck?> = emptyMap(),
    val error: Throwable? = null,
) {
    enum class Stage { CREATING, CHECKING_NETWORK, DONE, FAILED }

    /** Every created Space reaches the internet (an unknown result counts as offline). */
    val online: Boolean get() = network.isNotEmpty() && network.values.all { it?.online == true }

    /** The host firewall likely blocks the Spaces (Linux ufw); the fix is a command the user runs. */
    val firewallHint: Boolean get() = network.values.any { it?.hint == "ufw" }
}

/**
 * Runs Space creation on the app's scope, so it goes on when the setup page closes (the first Space on a
 * host downloads for minutes). Each partner hears in its chat when its computer is ready.
 */
class PartnerSpaceSetup(
    private val spaces: PartnerSpaces,
    private val threadService: ThreadService,
    private val appScope: AppScope,
    private val resources: Resources,
) {
    private val _jobs = MutableStateFlow<Map<String, SpaceSetupJob>>(emptyMap())
    val jobs: StateFlow<Map<String, SpaceSetupJob>> = _jobs.asStateFlow()
    private val running = mutableMapOf<String, Job>()

    /** Starts creating a Space for each partner on [hostId]; a run already going there is kept. */
    @Synchronized
    fun start(hostId: String, partnerIds: List<Uuid>) {
        if (running[hostId]?.isActive == true) return
        _jobs.update { it + (hostId to SpaceSetupJob(hostId, partnerIds)) }
        running[hostId] = appScope.launch {
            try {
                partnerIds.forEach { partnerId ->
                    val created = spaces.create(hostId, partnerId)
                    update(hostId) {
                        it.copy(created = it.created + (partnerId to created.workspaceId),
                            network = it.network + (created.workspaceId to created.netcheck))
                    }
                }
                update(hostId) { it.copy(stage = SpaceSetupJob.Stage.DONE) }
                announce(_jobs.value.getValue(hostId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                update(hostId) { it.copy(stage = SpaceSetupJob.Stage.FAILED, error = error) }
            }
        }
    }

    /** Checks the network of every Space this run created again, e.g. after the user fixed the firewall. */
    @Synchronized
    fun recheckNetwork(hostId: String) {
        val job = _jobs.value[hostId] ?: return
        if (running[hostId]?.isActive == true) return
        update(hostId) { it.copy(stage = SpaceSetupJob.Stage.CHECKING_NETWORK) }
        running[hostId] = appScope.launch {
            val results = job.created.values.associateWith { workspaceId ->
                runCatching { spaces.netcheck(workspaceId) }.getOrNull()
            }
            update(hostId) { it.copy(network = results, stage = SpaceSetupJob.Stage.DONE) }
        }
    }

    fun clear(hostId: String) {
        _jobs.update { it - hostId }
    }

    private fun update(hostId: String, change: (SpaceSetupJob) -> SpaceSetupJob) =
        _jobs.update { jobs -> jobs[hostId]?.let { jobs + (hostId to change(it)) } ?: jobs }

    private suspend fun announce(job: SpaceSetupJob) {
        job.created.keys.forEach { partnerId ->
            runCatching {
                threadService.deliverFrom(
                    origin = MessageOrigin.Event(PARTNER_SPACE_READY_EVENT, resources.getString(R.string.im_space_ready_event)),
                    assistantId = partnerId,
                    content = listOf(UIMessagePart.Text(resources.getString(R.string.im_space_ready_instruction))),
                )
            }
        }
    }
}
