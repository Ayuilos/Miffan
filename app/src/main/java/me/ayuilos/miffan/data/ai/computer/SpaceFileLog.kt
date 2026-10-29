package me.ayuilos.miffan.data.ai.computer

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import me.ayuilos.miffan.utils.JsonInstant

enum class SpaceFileDirection { TO_PARTNER, FROM_PARTNER }

/** [path] is relative to the Space's Shared folder. */
@Serializable
data class SpaceFileNote(val path: String, val direction: SpaceFileDirection, val at: Long)

/**
 * Which way files went between the owner and a partner's own computer. The Shared folder itself cannot
 * say: only what the app saw (the owner adding files, the partner handing files out) is noted here.
 * It lives in [dir], apart from the Space, so it can be read while the Space sleeps.
 */
class SpaceFileLog(private val dir: File) {
    private val notes = ConcurrentHashMap<String, List<SpaceFileNote>>()
    private val changes = MutableStateFlow(0L)

    /** Newest first. */
    fun notes(workspaceId: String): Flow<List<SpaceFileNote>> = changes.map { read(workspaceId) }.distinctUntilChanged()

    fun record(workspaceId: String, paths: Collection<String>, direction: SpaceFileDirection, at: Long = System.currentTimeMillis()) {
        if (paths.isEmpty()) return
        write(workspaceId) { old ->
            val moved = paths.toSet()
            (paths.map { SpaceFileNote(it, direction, at) } + old.filter { it.path !in moved }).sortedByDescending { it.at }.take(MAX_NOTES)
        }
    }

    /** [path] and everything under it, for a file or folder that was deleted. */
    fun forget(workspaceId: String, path: String) = write(workspaceId) { old -> old.filterNot { it.path == path || it.path.startsWith("$path/") } }

    fun clear(workspaceId: String) = write(workspaceId) { emptyList() }

    @Synchronized
    private fun write(workspaceId: String, change: (List<SpaceFileNote>) -> List<SpaceFileNote>) {
        val old = read(workspaceId)
        val new = change(old)
        if (new == old) return
        notes[workspaceId] = new
        runCatching {
            if (new.isEmpty()) file(workspaceId).delete()
            else { dir.mkdirs(); file(workspaceId).writeText(JsonInstant.encodeToString(new)) }
        }
        changes.value = changes.value + 1
    }

    private fun read(workspaceId: String): List<SpaceFileNote> = notes.getOrPut(workspaceId) {
        runCatching { JsonInstant.decodeFromString<List<SpaceFileNote>>(file(workspaceId).readText()) }.getOrDefault(emptyList())
    }

    private fun file(workspaceId: String) = File(dir, workspaceId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    companion object {
        private const val MAX_NOTES = 200
    }
}
