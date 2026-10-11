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
    /** Hidden threads reappear once they have activity after this time. */
    val hiddenAt: Map<String, Long> = emptyMap(),
    /** Collapsed Chats list groups, by group id. */
    val collapsedGroups: Set<String> = emptySet(),
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

    /** Read-only compatibility access until the settings migration has committed. */
    internal fun legacyPins(): Set<String> = prefs.all.keys
        .filter { it.startsWith(PINNED) }.mapTo(HashSet()) { it.removePrefix(PINNED) }

    internal fun clearLegacyPins() {
        prefs.edit { prefs.all.keys.filter { it.startsWith(PINNED) }.forEach { remove(it) } }
    }

    fun setGroupCollapsed(groupId: Uuid, collapsed: Boolean) {
        val key = groupId.toString()
        prefs.edit { if (collapsed) putBoolean(COLLAPSED + key, true) else remove(COLLAPSED + key) }
        state.update {
            it.copy(collapsedGroups = if (collapsed) it.collapsedGroups + key else it.collapsedGroups - key)
        }
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
            collapsedGroups = all.filter { (key, value) -> key.startsWith(COLLAPSED) && value == true }
                .keys.mapTo(HashSet()) { it.removePrefix(COLLAPSED) },
            hiddenAt = longs(HIDDEN),
        )
    }

    private companion object {
        const val READ = "read:"
        const val PINNED = "pinned:"
        const val COLLAPSED = "collapsed:"
        const val HIDDEN = "hidden:"
    }
}
