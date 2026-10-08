package me.ayuilos.miffan.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.ayuilos.miffan.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

class ConversationSessionsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun session(id: Uuid) = ConversationSession(
        id = id,
        initial = Conversation(id = id, assistantId = Uuid.random(), messageNodes = emptyList()),
        scope = scope,
        onIdle = {},
    )

    @Test
    fun `a collector resumed in place sees the generation of a session created after it started`() = runBlocking {
        val sessions = ConversationSessions()
        val seen = MutableStateFlow<Set<Uuid>>(emptySet())
        // Unconfined resumes the collector inside the creating call, as Main.immediate does on the main thread.
        val collector = scope.launch { sessions.jobs().collect { seen.value = it.keys } }
        val id = Uuid.random()
        // A plain thread, like a main-thread task resumed after routing suspended.
        thread { sessions.getOrCreate(id, ::session).setJob(Job()) }.join()

        assertNotNull("new session's generation never reached the jobs flow",
            withTimeoutOrNull(1_000) { seen.first { id in it } })
        collector.cancel()
        scope.cancel()
    }

    @Test
    fun `getOrCreate returns the existing session`() {
        val sessions = ConversationSessions()
        val id = Uuid.random()
        val first = sessions.getOrCreate(id, ::session)
        assertSame(first, sessions.getOrCreate(id) { error("must not create twice") })
        assertEquals(1, sessions.size)
        scope.cancel()
    }
}
