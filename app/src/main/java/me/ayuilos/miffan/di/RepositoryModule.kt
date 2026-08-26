package me.ayuilos.miffan.di

import android.content.Context
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.data.files.FileFolders
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.data.files.SkillManager
import me.ayuilos.miffan.data.extensions.ExtensionManagementService
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.data.repository.FavoriteRepository
import me.ayuilos.miffan.data.repository.FolderRepository
import me.ayuilos.miffan.data.repository.FilesRepository
import me.ayuilos.miffan.data.repository.GenMediaRepository
import me.ayuilos.miffan.data.repository.MemoryRepository
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.data.repository.WorkspaceNetworkBroker
import me.ayuilos.miffan.data.skills.install.RemoteSkillSourceClient
import me.ayuilos.miffan.data.skills.install.SkillInstallService
import me.ayuilos.miffan.data.skills.install.RepositoryWorkspaceSkillInstallTargetResolver
import me.ayuilos.miffan.data.skills.install.WorkspaceSkillInstallTargetResolver
import me.ayuilos.miffan.data.skills.source.GitHubRemoteSkillSourceClient
import me.ayuilos.miffan.data.skills.source.SkillShCatalogClient
import me.rerere.workspace.AndroidPageSize
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import okhttp3.OkHttpClient
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
                hostPageSizeBytes = AndroidPageSize.currentBytes(),
            ),
            // 这些应用目录仅允许专用文件工具读取，绝不作为可写 PRoot bind mount 暴露。
            // tool_outputs 进一步按 workspace root 分区，避免工作区之间读取彼此的结果。
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                    exposeToShell = false,
                    writableByTools = false,
                    workspaceScoped = true,
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                    exposeToShell = false,
                    writableByTools = false,
                ),
            ),
        )
    }

    single {
        RootfsInstaller(
            manager = get(),
            hostPageSizeBytes = AndroidPageSize.currentBytes(),
        )
    }

    single { WorkspaceNetworkBroker() }

    single {
        WorkspaceRepository(get(), get(), get(), get(), get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get(), get())
    }

    single {
        SkillShCatalogClient(get<OkHttpClient>(), get<Json>())
    }

    single<RemoteSkillSourceClient> {
        GitHubRemoteSkillSourceClient(get<OkHttpClient>(), get<Json>())
    }

    single<WorkspaceSkillInstallTargetResolver> {
        RepositoryWorkspaceSkillInstallTargetResolver(get(), get())
    }

    single {
        SkillInstallService(
            sourceClient = get(),
            workspaceTargetResolver = get(),
        )
    }

    single {
        ExtensionManagementService(get(), get())
    }
}
