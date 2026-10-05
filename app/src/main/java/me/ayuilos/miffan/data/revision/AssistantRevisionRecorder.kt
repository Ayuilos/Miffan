package me.ayuilos.miffan.data.revision

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.utils.JsonInstant

private const val TAG = "AssistantRevisions"

/** Records a revision for every assistant whose configuration changed in a settings update. */
class AssistantRevisionRecorder(private val revisions: RevisionRepository) {
    suspend fun onAssistantsChanged(old: List<Assistant>, new: List<Assistant>) {
        if (old == new) return
        val oldById = old.associateBy { it.id }
        for (assistant in new) {
            val before = oldById[assistant.id]
            if (before == assistant) continue
            try {
                revisions.record(
                    subject = RevisionSubject.ASSISTANT,
                    subjectId = assistant.id.toString(),
                    before = before?.let(::snapshot),
                    after = snapshot(assistant),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // History must never block saving the configuration itself.
                Log.e(TAG, "Unable to record revision of ${assistant.id}", e)
            }
        }
    }

    companion object {
        fun snapshot(assistant: Assistant): String = JsonInstant.encodeToString(assistant)

        fun restore(snapshot: String): Assistant = JsonInstant.decodeFromString(snapshot)
    }
}
