package me.ayuilos.miffan.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import org.koin.compose.koinInject

@Composable
fun rememberUserSettingsState(): State<Settings> {
    val store = koinInject<SettingsStore>()
    return store.settingsFlow.collectAsStateWithLifecycle(
        initialValue = Settings.dummy(),
    )
}
