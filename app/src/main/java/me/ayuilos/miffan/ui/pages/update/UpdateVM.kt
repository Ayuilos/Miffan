package me.ayuilos.miffan.ui.pages.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.ayuilos.miffan.data.datastore.DisplaySetting
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.utils.UpdateChecker
import me.ayuilos.miffan.utils.UpdateDownload
import me.ayuilos.miffan.utils.UpdatePresentation

class UpdateVM(
    private val checker: UpdateChecker,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val presentation: StateFlow<UpdatePresentation> = checker.presentation
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    fun download(download: UpdateDownload) = checker.downloadUpdate(download)
    fun cancelDownload() = checker.cancelUpdateDownload()
    fun install(context: Context): Boolean = checker.installUpdate(context)
    fun markSeen(version: String) = checker.markSeen(version)
    fun snooze(version: String) = checker.snooze(version)
    fun skip(version: String) = checker.skip(version)
    fun unskip() = checker.unskip()
    fun dismissWhatsNew() = checker.dismissWhatsNew()
    fun checkNow() = checker.checkNow()

    fun updateDisplay(transform: (DisplaySetting) -> DisplaySetting) {
        viewModelScope.launch {
            settingsStore.update { it.copy(displaySetting = transform(it.displaySetting)) }
        }
    }
}
