package me.ayuilos.miffan.data.ai.computer

import android.content.Context
import android.graphics.Bitmap
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayOutputStream
import java.io.File

/** A picture of a computer's screen and when it was taken. [url] is a file URI that changes with every new picture. */
data class ComputerSnapshot(val url: String, val at: Long)

/**
 * The last picture of each computer's screen, for pages that show it at rest (a mini box's detail, the chat, the
 * computer list). It comes from the partner's own screenshots and from the screen page as the user leaves it, and is
 * kept apart from chat files so deleting a conversation does not take it away.
 */
class ComputerSnapshots(context: Context) {
    private val dir = File(context.filesDir, "computer-snapshots")
    private val changes = MutableStateFlow(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun latest(workspaceId: String): Flow<ComputerSnapshot?> =
        changes.map { find(workspaceId) }.distinctUntilChanged()

    fun find(workspaceId: String): ComputerSnapshot? {
        val prefix = "${key(workspaceId)}-"
        return dir.listFiles { file -> file.name.startsWith(prefix) && file.name.endsWith(".jpg") }
            ?.maxByOrNull { it.lastModified() }
            ?.let { ComputerSnapshot(it.toUri().toString(), it.lastModified()) }
    }

    /** Keeps [jpeg] as the latest picture of [workspaceId]'s screen. */
    suspend fun save(workspaceId: String, jpeg: ByteArray) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val file = File(dir, "${key(workspaceId)}-$now.jpg")
        file.writeBytes(jpeg)
        prune(workspaceId, keep = file)
        changes.value = now
    }

    /** Keeps what [frame] shows now; it is copied at once, so the caller may keep drawing into it. */
    fun capture(workspaceId: String, frame: Bitmap) {
        val copy = runCatching { frame.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        scope.launch {
            val jpeg = ByteArrayOutputStream().use { out ->
                copy.compress(Bitmap.CompressFormat.JPEG, 80, out)
                copy.recycle()
                out.toByteArray()
            }
            runCatching { save(workspaceId, jpeg) }
        }
    }

    /** Keeps the last screenshot among a tool's [output], if it has one. */
    suspend fun record(workspaceId: String, output: List<UIMessagePart>) {
        val url = output.filterIsInstance<UIMessagePart.Image>().lastOrNull()?.url ?: return
        val source = runCatching { File(requireNotNull(url.toUri().path)) }.getOrNull()?.takeIf { it.isFile } ?: return
        runCatching { save(workspaceId, withContext(Dispatchers.IO) { source.readBytes() }) }
    }

    /** Forgets [workspaceId]'s pictures, for a computer that is gone. */
    suspend fun clear(workspaceId: String) = withContext(Dispatchers.IO) {
        prune(workspaceId, keep = null)
        changes.value = System.currentTimeMillis()
    }

    private fun prune(workspaceId: String, keep: File?) {
        val prefix = "${key(workspaceId)}-"
        dir.listFiles { file -> file.name.startsWith(prefix) && file != keep }?.forEach { it.delete() }
    }

    private fun key(workspaceId: String) = workspaceId.replace(Regex("[^A-Za-z0-9_]"), "_")
}
