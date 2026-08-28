package me.ayuilos.miffan.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import me.ayuilos.miffan.service.AssistantReplyCompleted
import kotlin.uuid.Uuid

/** No replay on navigation, no success inferred from loading=false, and no stale queued-turn smile. */
@Composable
internal fun rememberCompletedMascotReply(
    conversationId: Uuid,
    completions: Flow<AssistantReplyCompleted>,
    generationJobs: StateFlow<Job?>,
    holdMillis: Long,
): State<Uuid?> {
    val reply = remember(conversationId) { mutableStateOf<Uuid?>(null) }
    val currentHold = rememberUpdatedState(holdMillis)
    LaunchedEffect(conversationId, completions, generationJobs) {
        completions.filter { it.conversationId == conversationId }.collectLatest { event ->
            reply.value = null
            event.job.join()
            if (event.job.isCancelled) return@collectLatest
            // The ViewModel's stateIn may deliver the cleared job one frame after join resumes.
            if (generationJobs.first { it !== event.job } != null) return@collectLatest
            try {
                reply.value = event.messageId
                delay(currentHold.value)
            } finally {
                reply.value = null
            }
        }
    }
    LaunchedEffect(conversationId, generationJobs) {
        generationJobs.collect { if (it != null) reply.value = null }
    }
    return reply
}
