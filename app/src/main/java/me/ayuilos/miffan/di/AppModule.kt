package me.ayuilos.miffan.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.BuildConfig
import me.ayuilos.miffan.data.ai.tools.local.LocalTools
import me.ayuilos.miffan.data.event.AppEventBus
import me.ayuilos.miffan.service.ChatNotificationManager
import me.ayuilos.miffan.service.ChatService
import me.ayuilos.miffan.utils.AppAnalytics
import me.ayuilos.miffan.utils.EmojiData
import me.ayuilos.miffan.utils.EmojiUtils
import me.ayuilos.miffan.utils.JsonInstant
import me.ayuilos.miffan.utils.SoundEffectPlayer
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

    single {
        UpdateChecker(
            client = get(),
            appScope = get(),
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
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            extensionManagementService = get(),
            workspaceRepository = get(),
            folderRepository = get()
        )
    }

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
