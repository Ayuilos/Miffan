package me.ayuilos.miffan.data.ai.computer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Who is driving one remote desktop. A desktop has a single pointer and keyboard focus. */
enum class RemoteController { IDLE, PARTNER, USER }

/**
 * Arbitrates one remote machine between the user (screen page) and the partner (computer
 * tools). The user always wins: touching the screen while the partner acts hands control to
 * the user, and the partner's action tools refuse to run until the user hands it back.
 */
class RemoteComputerControl(private val onUserTransition: (String, Boolean) -> Unit = { _, _ -> }) {
    private val _states = MutableStateFlow<Map<String, RemoteController>>(emptyMap())

    /** Current controller per host id; absent means [RemoteController.IDLE]. */
    val states: StateFlow<Map<String, RemoteController>> = _states.asStateFlow()

    fun controller(hostId: String): RemoteController = _states.value[hostId] ?: RemoteController.IDLE

    /** The user touched the screen or typed while the partner was (or might be) acting. */
    fun userTakesOver(hostId: String) = userTransition(hostId, true)

    /** The user pressed "hand back" or left the screen page. */
    fun userHandsBack(hostId: String) {
        userTransition(hostId, false)
    }

    /**
     * Marks the partner as acting for the duration of [block]. Returns null without running it
     * when the user holds control.
     */
    suspend fun <T> partnerActs(hostId: String, block: suspend () -> T): T? {
        var allowed = false
        _states.update { states ->
            if (states[hostId] == RemoteController.USER) states
            else {
                allowed = true
                states + (hostId to RemoteController.PARTNER)
            }
        }
        if (!allowed) return null
        try {
            return block()
        } finally {
            _states.update { if (it[hostId] == RemoteController.PARTNER) it - hostId else it }
        }
    }

    private fun userTransition(hostId: String, takeover: Boolean) {
        while (true) {
            val before = _states.value
            if ((before[hostId] == RemoteController.USER) == takeover) return
            val after = if (takeover) before + (hostId to RemoteController.USER) else before - hostId
            if (_states.compareAndSet(before, after)) {
                // Never emit inside StateFlow.update: its lambda may be retried.
                runCatching { onUserTransition(hostId, takeover) }
                    .onFailure { android.util.Log.w("RemoteComputerControl", "Audit callback failed", it) }
                return
            }
        }
    }
}
