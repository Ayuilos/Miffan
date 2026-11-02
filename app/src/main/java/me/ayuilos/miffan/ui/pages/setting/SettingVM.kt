package me.ayuilos.miffan.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ayuilos.miffan.data.ai.mcp.McpManager
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.withWhaleThemeTrial
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.utils.UpdateChecker
import me.ayuilos.miffan.utils.UpdateInfo

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    private val updateChecker: UpdateChecker,
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    val availableUpdate: StateFlow<UpdateInfo?> =
        updateChecker.presentation
            .map { it.available?.takeIf { _ -> it.showCard } }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                null,
            )

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(transform)
        }
    }

    suspend fun experienceWhaleTheme(whaleName: String) {
        settingsStore.update { it.withWhaleThemeTrial(whaleName) }
    }
}
