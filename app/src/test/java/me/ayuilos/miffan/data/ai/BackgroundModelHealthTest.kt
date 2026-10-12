package me.ayuilos.miffan.data.ai

import kotlinx.coroutines.CancellationException
import me.rerere.ai.provider.Model
import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

class BackgroundModelHealthTest {
    private val model = Model(modelId = "gpt-mini", displayName = "Mini")

    @Test
    fun failureRecordsLatestTaskModelMessageAndTimestamp() {
        val health = BackgroundModelHealth()
        assertNull(health.lastFailure.value)
        val before = System.currentTimeMillis()
        health.reportFailure(BackgroundTask.MEMORY, model, IllegalStateException("rejected"))
        val failure = requireNotNull(health.lastFailure.value)
        assertEquals(BackgroundTask.MEMORY, failure.task)
        assertEquals("Mini", failure.modelName)
        assertEquals("rejected", failure.message)
        assertTrue(failure.at >= before)
        health.reportFailure(BackgroundTask.TITLE, model.copy(displayName = ""), IllegalStateException())
        assertEquals(BackgroundTask.TITLE, health.lastFailure.value?.task)
        assertEquals(model.modelId, health.lastFailure.value?.modelName)
        assertEquals("java.lang.IllegalStateException", health.lastFailure.value?.message)
    }

    @Test
    fun successClearsOnlyItsOwnTaskAndDismissClearsAnyTask() {
        val health = BackgroundModelHealth()
        health.reportFailure(BackgroundTask.MEMORY, model, IllegalStateException("failed"))
        val failure = health.lastFailure.value
        health.reportSuccess(BackgroundTask.SUMMARY)
        assertEquals(failure, health.lastFailure.value)
        health.reportSuccess(BackgroundTask.MEMORY)
        assertNull(health.lastFailure.value)
        health.reportFailure(BackgroundTask.ROUTING, model, IllegalStateException("failed"))
        health.dismiss()
        assertNull(health.lastFailure.value)
    }

    @Test
    fun cancellationAndTimeoutsDoNotReplaceAModelFailure() {
        val health = BackgroundModelHealth()
        health.reportFailure(BackgroundTask.MEMORY, model, IllegalArgumentException("bad request"))
        val failure = health.lastFailure.value
        listOf(CancellationException(), SocketTimeoutException(), InterruptedIOException("timeout"), TimeoutException()).forEach { error ->
            health.reportFailure(BackgroundTask.SUMMARY, model, error)
            assertEquals(failure, health.lastFailure.value)
            health.reportFailure(BackgroundTask.SUMMARY, model, IllegalStateException("wrapped", error))
            assertEquals(failure, health.lastFailure.value)
        }
    }
}
