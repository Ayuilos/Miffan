package me.ayuilos.miffan.di

import kotlinx.coroutines.launch

import me.ayuilos.miffan.data.revision.RevisionNoticeSource
import me.ayuilos.miffan.data.revision.RevisionService
import me.ayuilos.miffan.data.thread.LlmTopicClassifier
import me.ayuilos.miffan.data.thread.SegmentSummarizer
import me.ayuilos.miffan.data.thread.SmartSegmentRouter
import me.ayuilos.miffan.data.thread.ThreadModels
import me.ayuilos.miffan.data.thread.SegmentRouter
import me.ayuilos.miffan.data.thread.ThreadNoticeSource
import me.ayuilos.miffan.data.thread.ThreadService
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.BuildConfig
import me.ayuilos.miffan.data.ai.tools.local.LocalTools
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.event.AppEventBus
import me.ayuilos.miffan.service.ChatNotificationManager
import me.ayuilos.miffan.service.ChatService
import me.ayuilos.miffan.utils.AppAnalytics
import me.ayuilos.miffan.utils.EmojiData
import me.ayuilos.miffan.utils.EmojiUtils
import me.ayuilos.miffan.utils.JsonInstant
import me.ayuilos.miffan.utils.SoundEffectPlayer
import me.ayuilos.miffan.utils.UpdateApkDownloader
import me.ayuilos.miffan.utils.UpdateChecker
import me.ayuilos.miffan.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get())
    }

    single { UpdateApkDownloader(context = get(), client = get(), appScope = get<AppScope>()) }

    single {
        UpdateChecker(
            client = get(),
            appScope = get<AppScope>(),
            settings = get<SettingsStore>().settingsFlow,
            downloader = get(),
        )
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single<AppAnalytics> {
        if (BuildConfig.FIREBASE_ENABLED) {
            AppAnalytics { event -> Firebase.analytics.logEvent(event, null) }
        } else {
            AppAnalytics { }
        }
    }

    single {
        SoundEffectPlayer(get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            conversationRepo = { get() },
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = { get() },
        )
    }

    single { me.ayuilos.miffan.data.bugreport.RecentErrorStore(java.io.File(get<android.app.Application>().filesDir, "diagnostics/recent_errors.json")) }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            translationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            extensionManagementService = get(),
            workspaceRepository = get(),
            folderRepository = get(),
            auditRepository = get(),
            httpClient = get(),
            backgroundModelHealth = get(),
            recentErrorStore = get(),
            computerControl = get(),
        )
    }

    single<me.ayuilos.miffan.data.partner.PartnerChatStore> { me.ayuilos.miffan.data.partner.RoomPartnerChatStore(get(), { get() }) }
    single<me.ayuilos.miffan.data.partner.PartnerChatRuntime> {
        me.ayuilos.miffan.data.partner.ChatServicePartnerRuntime(get(), get(), { get<ChatService>() }, { get<ThreadService>() })
    }
    single { me.ayuilos.miffan.data.partner.DefaultPartnerChatService(get(), get(), get(), get<AppScope>(),
        listPrefs = get<me.ayuilos.miffan.data.thread.ThreadListState>().value,
        notifications = me.ayuilos.miffan.data.partner.PartnerGroupNotifications { chat, message, trigger ->
            get<ChatNotificationManager>().notifyGroupMessage(chat, message, trigger)
        }) }
    single<me.ayuilos.miffan.data.partner.PartnerChatService> { get<me.ayuilos.miffan.data.partner.DefaultPartnerChatService>() }

    single {
        val store = get<me.ayuilos.miffan.data.bugreport.RecentErrorStore>()
        val scope = get<AppScope>()
        me.ayuilos.miffan.data.ai.BackgroundModelHealth { task, error ->
            scope.launch { runCatching { store.record(error, "Background helper: ${task.name}") } }
        }
    }

    single { ThreadModels(settingsStore = get(), providerManager = get(), scope = get<AppScope>(), backgroundModelHealth = get()) }

    single<SegmentRouter> { SmartSegmentRouter(LlmTopicClassifier(get())) }

    single {
        SegmentSummarizer(
            models = get(),
            settingsStore = get(),
            conversationRepository = get(),
            chatService = get(),
            appScope = get(),
        )
    }

    single { RevisionService(revisions = get(), settingsStore = get(), memoryRepository = get()) }

    single<ThreadNoticeSource> { RevisionNoticeSource(revisions = get(), settingsStore = get()) }

    single { ThreadService(chatService = get(), settingsStore = get(), router = get(), summarizer = get(), conversationRepository = get()) }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
