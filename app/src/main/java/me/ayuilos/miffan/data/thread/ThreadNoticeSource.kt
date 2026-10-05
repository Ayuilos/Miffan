package me.ayuilos.miffan.data.thread

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.uuid.Uuid

/** Supplies timeline notices (settings and memory changes) for an assistant's thread. */
fun interface ThreadNoticeSource {
    fun observe(assistantId: Uuid): Flow<List<ThreadNotice>>

    companion object {
        val Empty = ThreadNoticeSource { flowOf(emptyList()) }
    }
}
