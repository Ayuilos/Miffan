package me.ayuilos.miffan.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.ayuilos.miffan.R
import me.ayuilos.miffan.RouteActivity
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

private const val TAG = "ChatGenerationFgs"

/**
 * Keeps the app process in the foreground while one or more chat generations are active.
 *
 * Generation itself remains owned by [ChatService]. This service only provides the Android
 * foreground-service lifetime required for streaming to continue after the activity is hidden.
 */
class ChatGenerationForegroundService : Service() {
    companion object {
        private const val ACTION_ACQUIRE = "me.ayuilos.miffan.action.CHAT_GENERATION_ACQUIRE"
        private const val ACTION_RELEASE = "me.ayuilos.miffan.action.CHAT_GENERATION_RELEASE"
        private const val EXTRA_GENERATION_ID = "generation_id"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"

        const val NOTIFICATION_ID = 2002

        private var instance: ChatGenerationForegroundService? = null
        private val starting = mutableMapOf<Uuid, CompletableDeferred<Boolean>>()
        private var keeper: GenerationKeepAlive? = null
        // Main dispatcher only; preserve the chat destination for the lifetime of each lease.
        private val partnerChatTargets = mutableMapOf<Uuid, Pair<Uuid, Uuid>>()

        private fun keeper(context: Context): GenerationKeepAlive = keeper ?: GenerationKeepAlive(
            object : GenerationKeepAliveBackend {
                override suspend fun acquireForeground(id: Uuid, conversationId: Uuid): Boolean =
                    withContext(Dispatchers.Main.immediate) {
                        instance?.takeIf { it.isForeground }?.let { service ->
                            service.activeGenerations[id.toString()] = conversationId.toString()
                            // The process already owns an FGS; do not request a new background start.
                            return@withContext true
                        }
                        val ready = CompletableDeferred<Boolean>()
                        starting[id] = ready
                        try {
                            val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                                action = ACTION_ACQUIRE
                                putExtra(EXTRA_GENERATION_ID, id.toString())
                                putExtra(EXTRA_CONVERSATION_ID, conversationId.toString())
                            }
                            ContextCompat.startForegroundService(context, intent)
                            ready.await()
                        } catch (e: CancellationException) {
                            withContext(NonCancellable) { instance?.releaseId(id.toString()) }
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "FGS unavailable; using expedited work", e)
                            false
                        } finally {
                            starting.remove(id)
                        }
                    }
                override suspend fun acquireExpedited(id: Uuid, conversationId: Uuid) {
                    ChatGenerationKeepAliveWorker.acquire(context, id)
                }
                override suspend fun releaseForeground(id: Uuid) {
                    withContext(Dispatchers.Main.immediate) { instance?.releaseId(id.toString()) }
                }
                override suspend fun releaseExpedited(id: Uuid) {
                    ChatGenerationKeepAliveWorker.release(id)
                }
            }
        ).also { keeper = it }

        suspend fun acquire(context: Context, generationId: Uuid, conversationId: Uuid, chatId: Uuid? = null): Boolean =
            withContext(Dispatchers.Main.immediate) {
                if (chatId != null) partnerChatTargets[generationId] = conversationId to chatId
                try { keeper(context.applicationContext).acquire(generationId, conversationId) }
                catch (e: Exception) { partnerChatTargets.remove(generationId); throw e }
            }

        suspend fun release(context: Context, generationId: Uuid) =
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                try { keeper(context.applicationContext).release(generationId) }
                finally { partnerChatTargets.remove(generationId) }
            }
    }

    private val activeGenerations = linkedMapOf<String, String>()
    private var isForeground = false

    /** The newest start request; stopping is skipped while a newer acquire is still pending. */
    private var latestStartId = 0
    private val appScope: AppScope by inject()
    private val chatService: ChatService by inject()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_ACQUIRE -> acquire(intent)
            ACTION_RELEASE -> release(intent)
            else -> stopWhenIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        starting.values.forEach { it.complete(false) }
        activeGenerations.clear()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.e(TAG, "Foreground service timed out (type=$fgsType)")
        activeGenerations.values
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .distinct()
            .forEach { conversationId ->
                appScope.launch {
                    chatService.stopGeneration(conversationId)
                }
            }
        // Android only allows a few seconds after onTimeout before raising RemoteServiceException.
        stopService()
    }

    private fun acquire(intent: Intent) {
        val generationId = intent.getStringExtra(EXTRA_GENERATION_ID)
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        val ready = generationId?.let { runCatching { starting[Uuid.parse(it)] }.getOrNull() }
        // Every startForegroundService() must reach startForeground() before the service stops, even
        // when the generation that asked for it already ended; stopping first makes Android kill the app.
        if (!isForeground) updateForegroundNotification(conversationId.orEmpty())
        if (generationId == null || conversationId == null || ready == null) {
            // A stale request must not take the foreground away from generations still running.
            if (activeGenerations.isEmpty()) stopWhenIdle()
            return
        }
        activeGenerations[generationId] = conversationId
        updateForegroundNotification(conversationId)
        if (isForeground) instance = this
        ready.complete(isForeground)
    }

    private fun release(intent: Intent) {
        intent.getStringExtra(EXTRA_GENERATION_ID)?.let(::releaseId)
    }

    private fun releaseId(id: String) {
        activeGenerations.remove(id)
        if (activeGenerations.isEmpty()) {
            stopWhenIdle()
        } else {
            updateForegroundNotification(activeGenerations.values.last())
        }
    }

    private fun updateForegroundNotification(conversationId: String) {
        try {
            val notification = buildNotification(conversationId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
            activeGenerations.clear()
            stopSelf()
        }
    }

    /** Keep foreground state until destruction if a newer ACQUIRE is queued. */
    private fun stopWhenIdle() {
        if (stopSelfResult(latestStartId) && instance === this) {
            // Do not attach new in-process leases to an instance already scheduled to stop.
            instance = null
        }
    }

    private fun stopService() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        if (instance === this) instance = null
        stopSelfResult(latestStartId)
    }

    private fun buildNotification(conversationId: String) =
        NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_live_update_title))
            .setContentIntent(getConversationPendingIntent(conversationId))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun getConversationPendingIntent(conversationId: String): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            val chatId = partnerChatTargets.values.lastOrNull { it.first.toString() == conversationId }?.second
            if (chatId != null) putExtra("chatId", chatId.toString()) else putExtra("conversationId", conversationId)
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
