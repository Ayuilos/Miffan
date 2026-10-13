package me.ayuilos.miffan.service

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.ayuilos.miffan.R
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** Expedited JobScheduler work keeps background turns runnable when Android denies an FGS. */
class ChatGenerationKeepAliveWorker(context: Context, params: androidx.work.WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val lease = running[id] ?: return Result.success() // Process died; no in-memory generation remains.
        Log.i("ChatGenerationWorker", "Keeping background generation alive: $id")
        lease.started.complete(Unit)
        try {
            lease.finished.await()
        } finally {
            lease.started.cancel()
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        ChatGenerationForegroundService.NOTIFICATION_ID,
        NotificationCompat.Builder(applicationContext, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.notification_live_update_title))
            .setOngoing(true).build(),
        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private class Lease {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
    }

    companion object {
        private val running = ConcurrentHashMap<java.util.UUID, Lease>()
        private val tokens = mutableSetOf<Uuid>() // Serialized by GenerationKeepAlive.
        private var workId: java.util.UUID? = null

        internal suspend fun acquire(context: Context, token: Uuid) {
            if (tokens.isNotEmpty()) {
                tokens.add(token)
                return
            }
            val request = OneTimeWorkRequestBuilder<ChatGenerationKeepAliveWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("chat-generation-keep-alive")
                .build()
            val lease = Lease()
            running[request.id] = lease
            workId = request.id
            try {
                WorkManager.getInstance(context).enqueue(request)
                // Never run the model before JobScheduler has actually granted us execution time.
                lease.started.await()
                tokens.add(token)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    running.remove(request.id)?.finished?.complete(Unit)
                    WorkManager.getInstance(context).cancelWorkById(request.id)
                }
                workId = null
                throw e
            }
        }

        internal fun release(token: Uuid) {
            if (!tokens.remove(token) || tokens.isNotEmpty()) return
            workId?.let { running.remove(it)?.finished?.complete(Unit) }
            workId = null
        }
    }
}
