package me.ayuilos.miffan.di

import me.ayuilos.miffan.ui.pages.assistant.AssistantVM
import me.ayuilos.miffan.ui.pages.assistant.detail.AssistantDetailVM
import me.ayuilos.miffan.ui.pages.backup.BackupVM
import me.ayuilos.miffan.ui.pages.chat.ChatDrawerVM
import me.ayuilos.miffan.ui.pages.chat.ChatVM
import me.ayuilos.miffan.ui.pages.debug.DebugVM
import me.ayuilos.miffan.ui.pages.favorite.FavoriteVM
import me.ayuilos.miffan.ui.pages.search.SearchVM
import me.ayuilos.miffan.ui.pages.history.HistoryVM
import me.ayuilos.miffan.ui.pages.stats.StatsVM
import me.ayuilos.miffan.ui.pages.imggen.ImgGenVM
import me.ayuilos.miffan.ui.pages.extensions.PromptVM
import me.ayuilos.miffan.ui.pages.extensions.QuickMessagesVM
import me.ayuilos.miffan.ui.pages.extensions.workspace.WorkspaceDetailVM
import me.ayuilos.miffan.ui.pages.extensions.workspace.WorkspaceVM
import me.ayuilos.miffan.ui.pages.setting.SettingVM
import me.ayuilos.miffan.ui.pages.share.handler.ShareHandlerVM
import me.ayuilos.miffan.ui.pages.translator.TranslatorVM
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        ChatVM(
            id = params.get(),
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
            updateChecker = get(),
            analytics = get(),
            filesManager = get(),
            favoriteRepository = get(),
        )
    }
    viewModelOf(::ChatDrawerVM)
    viewModelOf(::SettingVM)
    viewModelOf(::DebugVM)
    viewModelOf(::HistoryVM)
    viewModelOf(::AssistantVM)
    viewModel<AssistantDetailVM> {
        AssistantDetailVM(
            id = it.get(),
            settingsStore = get(),
            memoryRepository = get(),
            filesManager = get(),
            workspaceRepository = get(),
        )
    }
    viewModelOf(::TranslatorVM)
    viewModel<ShareHandlerVM> {
        ShareHandlerVM(
            text = it.get(),
            settingsStore = get(),
        )
    }
    viewModelOf(::BackupVM)
    viewModelOf(::ImgGenVM)
    viewModelOf(::PromptVM)
    viewModelOf(::QuickMessagesVM)
    viewModelOf(::WorkspaceVM)
    viewModel<WorkspaceDetailVM> {
        WorkspaceDetailVM(
            args = it.get(),
            repository = get(),
            skillManager = get(),
        )
    }
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)
}
