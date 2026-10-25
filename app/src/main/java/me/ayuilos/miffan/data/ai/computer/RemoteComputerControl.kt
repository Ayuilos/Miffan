package me.ayuilos.miffan.data.ai.computer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Who is driving one remote desktop. A desktop has a single pointer and keyboard focus. */
enum class RemoteController { IDLE, PARTNER, USER }

/** The partner turn holding a desktop. [name] is only for display; identity is the turn. */
data class ComputerHolder(val assistantId: String, val conversationId: String, val name: String) {
    fun sameTurn(other: ComputerHolder): Boolean =
        assistantId == other.assistantId && conversationId == other.conversationId
}

sealed interface PartnerActResult<out T> {
    data class Done<T>(val value: T) : PartnerActResult<T>
    /** The user holds the desktop; the action did not run. */
    data object UserHolds : PartnerActResult<Nothing>
    /** Another partner turn holds the desktop; the action did not run. */
    data class Busy(val holder: ComputerHolder) : PartnerActResult<Nothing>
    /** The computer's cua-driver is being updated; the action did not run. */
    data object Updating : PartnerActResult<Nothing>
}

private data class HostControl(
    val holder: ComputerHolder? = null,
    /** When the holder last started or finished an action. */
    val lastActionAt: Long = 0,
    /** Actions running now; the holder never loses the desktop in the middle of one. */
    val acting: Int = 0,
    val user: Boolean = false,
    /** cua-driver is being updated; see [RemoteComputerControl.beginUpdate]. */
    val updating: Boolean = false,
) {
    val unused: Boolean get() = holder == null && acting == 0 && !user && !updating
}

/**
 * Arbitrates one remote machine between the user (screen page) and partners (computer tools).
 *
 * The user always wins: touching the screen while a partner acts hands control to the user, and
 * partners' action tools refuse to run until the user hands it back. Between partners, the first
 * turn to act holds the desktop until its generation ends ([release]) or it stays idle for
 * [holdMillis]; other turns are refused meanwhile, so their clicks and typing never interleave.
 */
class RemoteComputerControl(
    private val onUserTransition: (String, Boolean) -> Unit = { _, _ -> },
    private val holdMillis: Long = 90_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var hosts = emptyMap<String, HostControl>()

    private val _states = MutableStateFlow<Map<String, RemoteController>>(emptyMap())

    /**
     * Current controller per host id; absent means [RemoteController.IDLE]. PARTNER only while an
     * action runs; [holders] tells which turn holds the desktop between actions.
     */
    val states: StateFlow<Map<String, RemoteController>> = _states.asStateFlow()

    private val _holders = MutableStateFlow<Map<String, ComputerHolder>>(emptyMap())

    /** The partner turn holding each host, kept while the user has taken over so it resumes after. */
    val holders: StateFlow<Map<String, ComputerHolder>> = _holders.asStateFlow()

    fun controller(hostId: String): RemoteController = _states.value[hostId] ?: RemoteController.IDLE

    private val _waiting = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Hosts where a partner tried to act while the user held control. The screen page tells
     * the user only then, instead of every time they touch a desktop the partner is not using.
     */
    val waiting: StateFlow<Set<String>> = _waiting.asStateFlow()

    /** The user touched the screen or typed while the partner was (or might be) acting. */
    fun userTakesOver(hostId: String) = userTransition(hostId, true)

    /** The user pressed "hand back" or left the screen page. */
    fun userHandsBack(hostId: String) {
        userTransition(hostId, false)
    }

    /**
     * Runs [block] as an action of [holder]'s turn, which then holds the desktop. Refuses without
     * running it while the user or another turn holds the desktop. A holder idle for [holdMillis]
     * gives way, so a stalled turn never locks the computer.
     */
    suspend fun <T> partnerActs(hostId: String, holder: ComputerHolder, block: suspend () -> T): PartnerActResult<T> {
        val refusal = synchronized(lock) {
            val now = clock()
            val current = hosts[hostId] ?: HostControl()
            val other = current.holder?.takeUnless { it.sameTurn(holder) }
            when {
                current.updating -> PartnerActResult.Updating
                current.user -> PartnerActResult.UserHolds
                other != null && (current.acting > 0 || now - current.lastActionAt < holdMillis) -> PartnerActResult.Busy(other)
                else -> {
                    write(hostId, current.copy(holder = holder, lastActionAt = now, acting = current.acting + 1))
                    null
                }
            }
        }
        if (refusal == PartnerActResult.UserHolds) _waiting.update { it + hostId }
        if (refusal != null) return refusal
        try {
            return PartnerActResult.Done(block())
        } finally {
            synchronized(lock) {
                hosts[hostId]?.let { current ->
                    val mine = current.holder?.sameTurn(holder) == true
                    write(hostId, current.copy(
                        acting = (current.acting - 1).coerceAtLeast(0),
                        lastActionAt = if (mine) clock() else current.lastActionAt,
                    ))
                }
            }
        }
    }

    /** The partner turn using [hostId] now: acting, or holding it between actions; null when none is. */
    fun partnerUsing(hostId: String): ComputerHolder? = synchronized(lock) {
        hosts[hostId]?.takeIf { it.acting > 0 || clock() - it.lastActionAt < holdMillis }?.holder
    }

    /**
     * Reserves [hostId] for updating its cua-driver, which restarts the driver under any action. Refused
     * (returning the turn) while a partner uses the computer; once reserved, partner actions are refused
     * with [PartnerActResult.Updating] until [endUpdate].
     */
    fun beginUpdate(hostId: String): ComputerHolder? = synchronized(lock) {
        val current = hosts[hostId] ?: HostControl()
        val using = current.holder?.takeIf { current.acting > 0 || clock() - current.lastActionAt < holdMillis }
        if (using == null) write(hostId, current.copy(updating = true))
        using
    }

    fun endUpdate(hostId: String) = synchronized(lock) {
        hosts[hostId]?.let { write(hostId, it.copy(updating = false)) }
    }

    /** The turn of [conversationId] ended (finished, failed or cancelled): it no longer holds any desktop. */
    fun release(conversationId: String) = synchronized(lock) {
        hosts.filterValues { it.holder?.conversationId == conversationId }.forEach { (hostId, current) ->
            write(hostId, current.copy(holder = null))
        }
    }

    private fun userTransition(hostId: String, takeover: Boolean) {
        val changed = synchronized(lock) {
            val current = hosts[hostId] ?: HostControl()
            if (current.user == takeover) return@synchronized false
            // Handing back gives the holder a fresh window to resume instead of losing the desktop
            // to whoever acts first because the user's turn outlasted holdMillis.
            write(hostId, current.copy(user = takeover, lastActionAt = if (takeover) current.lastActionAt else clock()))
            true
        }
        if (!changed) return
        if (!takeover) _waiting.update { it - hostId }
        runCatching { onUserTransition(hostId, takeover) }
            .onFailure { android.util.Log.w("RemoteComputerControl", "Audit callback failed", it) }
    }

    /** Callers hold [lock]. */
    private fun write(hostId: String, control: HostControl) {
        hosts = if (control.unused) hosts - hostId else hosts + (hostId to control)
        _states.value = hosts.mapNotNull { (id, it) ->
            when {
                it.user -> id to RemoteController.USER
                it.acting > 0 -> id to RemoteController.PARTNER
                else -> null
            }
        }.toMap()
        _holders.value = hosts.mapNotNull { (id, it) -> it.holder?.let { holder -> id to holder } }.toMap()
    }
}
