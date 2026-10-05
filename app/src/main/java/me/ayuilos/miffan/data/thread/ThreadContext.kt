package me.ayuilos.miffan.data.thread

import kotlinx.coroutines.launch
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.dao.ThreadSegmentDigest
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.service.ChatService
import me.ayuilos.miffan.utils.applyPlaceholders
import me.rerere.ai.core.MessageRole
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.uuid.Uuid

/** Writes rolling summaries for segments that stopped receiving new topics. */
class SegmentSummarizer(
    private val models: ThreadModels,
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val chatService: ChatService,
    private val appScope: AppScope,
) {
    fun summarizeLater(segmentIds: Collection<Uuid>) {
        if (segmentIds.isEmpty()) return
        appScope.launch { segmentIds.forEach { summarize(it) } }
    }

    suspend fun summarize(segmentId: Uuid) {
        val segment = conversationRepository.getConversationById(segmentId) ?: return
        if (segment.threadSummary.isNotBlank()) return
        val messages = segment.currentMessages.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        if (messages.isEmpty()) return
        val prompt = settingsStore.settingsFlow.value.compressPrompt.applyPlaceholders(
            "content" to messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) },
            "target_tokens" to SUMMARY_TOKENS.toString(),
            "additional_context" to "",
            "locale" to Locale.getDefault().displayName,
        )
        val summary = models.compress(prompt, SUMMARY_TIMEOUT_MILLIS) ?: return
        chatService.updateThreadSegment(segmentId) { it.copy(threadSummary = summary) }
    }

    companion object {
        const val SUMMARY_TOKENS = 200
        const val SUMMARY_TIMEOUT_MILLIS = 60_000L
    }
}

/** System-prompt section carrying summaries of earlier segments into a new one. */
object ThreadContext {
    const val MAX_DIGESTS = 3

    suspend fun build(conversationRepository: ConversationRepository, assistantId: Uuid, segmentId: Uuid): String? =
        format(conversationRepository.getThreadDigests(assistantId, segmentId, MAX_DIGESTS))

    internal fun format(digests: List<ThreadSegmentDigest>, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (digests.isEmpty()) return null
        return buildString {
            appendLine("<earlier_conversations>")
            appendLine("Summaries of your earlier conversations with the user, newest first. Use them for continuity when relevant; the user experiences them as one ongoing chat.")
            digests.forEach { digest ->
                val date = Instant.ofEpochMilli(digest.updateAt).atZone(zone).toLocalDate()
                appendLine("- ($date) ${digest.threadSummary.trim()}")
            }
            append("</earlier_conversations>")
        }
    }
}
