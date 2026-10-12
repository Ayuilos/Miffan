package me.ayuilos.miffan.data.bugreport

import android.app.Application
import io.mockk.*
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.*
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.data.ai.BackgroundModelHealth
import me.ayuilos.miffan.data.ai.BackgroundTask
import me.ayuilos.miffan.service.ChatService
import me.rerere.ai.provider.Model
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class ErrorRecordingIntegrationTest {
    @Test fun dismissingChatErrorsDoesNotRemovePersistedDiagnosticsOrStoreConversationId() = runTest {
        val file = File("build/bug-reporter-tests/${java.util.UUID.randomUUID()}/errors.json")
        val store = RecentErrorStore(file)
        val application = mockk<Application>(relaxed = true)
        every { application.cacheDir } returns file.parentFile
        val scope = mockk<AppScope>()
        val job = SupervisorJob()
        every { scope.coroutineContext } returns (StandardTestDispatcher(testScheduler) + job)
        val service = ChatService(application, scope, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
            OkHttpClient(), recentErrorStore = store)
        try {
            val conversationId = kotlin.uuid.Uuid.random()
            service.addError(IllegalArgumentException("token=secret"), conversationId, "Sending failed")
            service.dismissError(service.errors.value.single().id)
            service.addError(CancellationException("cancelled"))
            runCurrent()
            job.children.toList().forEach { it.join() }
            assertTrue(service.errors.value.isEmpty())
            val persisted = RecentErrorStore(file).recent()
            assertEquals(1, persisted.size)
            assertEquals("[已隐藏]", persisted.single().message)
            assertFalse(file.readText().contains(conversationId.toString()))
            service.clearAllErrors()
            assertEquals(persisted, store.recent())
        } finally {
            job.cancel(); file.parentFile?.deleteRecursively()
        }
    }

    @Test fun backgroundRecorderReceivesExceptionWithoutModelConfiguration() {
        val recorded = mutableListOf<Pair<BackgroundTask, Throwable>>()
        val health = BackgroundModelHealth { task, error -> recorded += task to error }
        val error = IllegalStateException("failed")
        health.reportFailure(BackgroundTask.MEMORY, Model(displayName = "private-model"), error)
        assertEquals(listOf(BackgroundTask.MEMORY to error), recorded)
        health.dismiss()
        assertEquals(1, recorded.size)
        health.reportFailure(BackgroundTask.MEMORY, Model(), CancellationException("cancelled"))
        assertEquals(1, recorded.size)
    }
}
