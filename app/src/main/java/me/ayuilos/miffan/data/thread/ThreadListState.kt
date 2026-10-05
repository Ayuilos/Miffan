package me.ayuilos.miffan.data.thread

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

/** Per-device Chats list state of each assistant thread. */
data class ThreadListPrefs(
    /** When the thread was last seen, for unread badges. */
    val readAt: Map<String, Long> = emptyMap(),
    val pinned: Set<String> = emptySet(),
    /** Hidden threads reappear once they have activity after this time. */
    val hiddenAt: Map<String, Long> = emptyMap(),
)

class ThreadListState(context: Context) {
    private val prefs = context.getSharedPreferences("im_thread_list", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val value: StateFlow<ThreadListPrefs> = state.asStateFlow()

    fun markRead(assistantId: Uuid, at: Long = System.currentTimeMillis()) {
        val key = assistantId.toString()
        if ((state.value.readAt[key] ?: 0L) >= at) return
        prefs.edit { putLong(READ + key, at) }
        state.update { it.copy(readAt = it.readAt + (key to at)) }
    }

    fun setPinned(assistantId: Uuid, pinned: Boolean) {
        val key = assistantId.toString()
        prefs.edit { if (pinned) putBoolean(PINNED + key, true) else remove(PINNED + key) }
        state.update { it.copy(pinned = if (pinned) it.pinned + key else it.pinned - key) }
    }

    /** Removes the thread from the Chats list until it has new activity; history is kept. */
    fun hide(assistantId: Uuid, at: Long = System.currentTimeMillis()) {
        val key = assistantId.toString()
        prefs.edit { putLong(HIDDEN + key, at) }
        state.update { it.copy(hiddenAt = it.hiddenAt + (key to at)) }
    }

    private fun load(): ThreadListPrefs {
        val all = prefs.all
        fun longs(prefix: String) = all.mapNotNull { (key, value) ->
            if (key.startsWith(prefix)) (value as? Long)?.let { key.removePrefix(prefix) to it } else null
        }.toMap()
        return ThreadListPrefs(
            readAt = longs(READ),
            pinned = all.keys.filter { it.startsWith(PINNED) }.mapTo(HashSet()) { it.removePrefix(PINNED) },
            hiddenAt = longs(HIDDEN),
        )
    }

    private companion object {
        const val READ = "read:"
        const val PINNED = "pinned:"
        const val HIDDEN = "hidden:"
    }
}
