package me.ayuilos.miffan.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.CHAT_APPROVAL_NOTIFICATION_CHANNEL_ID
import me.ayuilos.miffan.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.ayuilos.miffan.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.ayuilos.miffan.R
import me.ayuilos.miffan.RouteActivity
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.event.AppEvent
import me.ayuilos.miffan.data.event.AppEventBus
import me.ayuilos.miffan.utils.cancelNotification
import me.ayuilos.miffan.utils.sendNotification
import me.ayuilos.miffan.data.repository.ConversationRepository
import kotlin.uuid.Uuid

// Live Update 通知节流间隔：流式输出每个chunk都会触发一次更新，
// notify() 是 binder IPC 且系统本身会对高频更新限流，必须在应用侧节流
private const val LIVE_UPDATE_NOTIFICATION_THROTTLE_MS = 1000L

/**
 * 订阅 [AppEventBus] 上的聊天生成事件，负责后台生成相关的系统通知
 * （Live Update 进度通知和生成完成通知）。
 */
class ChatNotificationManager(
    private val context: Application,
    private val appScope: AppScope,
    private val conversationRepo: ConversationRepository,
    eventBus: AppEventBus,
    private val settingsStore: SettingsStore,
) {
    private val isForeground = MutableStateFlow(false)
    private val liveUpdateLastSentAt = mutableMapOf<Uuid, Long>()
    // Accessed only on AppScope's main dispatcher, including lifecycle callbacks.
    private val liveUpdates = linkedMapOf<Uuid, AppEvent.ChatGenerationUpdate>()
    private val approvalObservers = mutableMapOf<Uuid, Job>()

    init {
        // ProcessLifecycleOwner 要求在主线程注册观察者
        appScope.launch {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            isForeground.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> isForeground.value = true
                        Lifecycle.Event.ON_STOP -> {
                            isForeground.value = false
                            // A tool/network pause may produce no new chunks after the app is hidden.
                            liveUpdates.values.lastOrNull()?.let { update ->
                                liveUpdateLastSentAt.remove(update.conversationId)
                                handleGenerationUpdate(update)
                            }
                        }
                        else -> {}
                    }
                }
            )
        }
        appScope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ChatGenerationUpdate -> handleGenerationUpdate(event)
                    is AppEvent.ChatGenerationEnded -> handleGenerationEnded(event)
                    else -> {}
                }
            }
        }
    }

    private fun handleGenerationUpdate(event: AppEvent.ChatGenerationUpdate) {
        liveUpdates[event.conversationId] = event
        if (isForeground.value) return
        val displaySetting = settingsStore.settingsFlow.value.displaySetting
        if (!displaySetting.enableNotificationOnMessageGeneration) return
        if (!displaySetting.enableLiveUpdateNotification) return

        val now = SystemClock.elapsedRealtime()
        val lastSentAt = liveUpdateLastSentAt[event.conversationId]
        if (lastSentAt != null && now - lastSentAt < LIVE_UPDATE_NOTIFICATION_THROTTLE_MS) return
        liveUpdateLastSentAt[event.conversationId] = now

        sendLiveUpdateNotification(event.conversationId, event.lastMessage, event.senderName)
    }

    private fun handleGenerationEnded(event: AppEvent.ChatGenerationEnded) {
        cancelLiveUpdateNotification(event.conversationId)

        if (event.pendingApprovals.isNotEmpty()) {
            observeApprovalNotification(event.conversationId, event.senderName)
            return
        }
        cancelApprovalNotification(event.conversationId)
        val contentPreview = event.contentPreview ?: return
        if (isForeground.value) return
        if (!settingsStore.settingsFlow.value.displaySetting.enableNotificationOnMessageGeneration) return
        sendGenerationDoneNotification(event.conversationId, event.senderName, contentPreview)
    }

    private fun observeApprovalNotification(conversationId: Uuid, senderName: String) {
        approvalObservers.remove(conversationId)?.cancel()
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            // Read the persisted state, not an old event payload: an answer may already have arrived.
            combine(
                conversationRepo.observeConversation(conversationId)
                    .map { it?.pendingApprovalTools().orEmpty() }.distinctUntilChanged(),
                isForeground,
            ) { tools, foreground -> tools to foreground }.collect { (tools, foreground) ->
                val summary = approvalNotificationSummary(tools) { id, args -> context.getString(id, *args.toTypedArray()) }
                if (summary == null) {
                    cancelApprovalNotification(conversationId)
                } else if (!foreground) {
                    context.sendNotification(CHAT_APPROVAL_NOTIFICATION_CHANNEL_ID, approvalNotificationId(conversationId)) {
                        title = context.getString(R.string.notification_approval_title, senderName)
                        content = summary
                        autoCancel = true
                        onlyAlertOnce = true
                        useDefaults = true
                        category = NotificationCompat.CATEGORY_MESSAGE
                        contentIntent = getPendingIntent(context, conversationId)
                    }
                }
            }
        }
        approvalObservers[conversationId] = job
        job.start()
    }

    private fun cancelApprovalNotification(conversationId: Uuid) {
        approvalObservers.remove(conversationId)?.cancel()
        context.cancelNotification(approvalNotificationId(conversationId))
    }

    private fun sendGenerationDoneNotification(
        conversationId: Uuid,
        senderName: String,
        contentPreview: String
    ) {
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = contentPreview
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        lastMessage: UIMessage,
        senderName: String
    ) {
        // 确定当前状态
        val (chipText, statusText, contentText) = determineNotificationContent(lastMessage.parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            // 更新前台服务正在使用的同一条通知，避免重复显示生成进度。
            notificationId = ChatGenerationForegroundService.NOTIFICATION_ID
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        // 检查最近的 part 来确定状态
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                val toolName = lastTool.toolName.substringAfterLast("__")
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100)
                )
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回复
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状态
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        liveUpdates.remove(conversationId)
        liveUpdateLastSentAt.remove(conversationId)
        // 前台服务持有通知时系统会保留它；启动失败时则清理普通 ongoing 通知。
        context.cancelNotification(ChatGenerationForegroundService.NOTIFICATION_ID)
        liveUpdates.values.lastOrNull()?.let { remaining ->
            liveUpdateLastSentAt.remove(remaining.conversationId)
            handleGenerationUpdate(remaining)
        }
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
