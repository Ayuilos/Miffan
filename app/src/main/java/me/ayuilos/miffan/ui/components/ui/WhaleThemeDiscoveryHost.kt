package me.ayuilos.miffan.ui.components.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.dismissWhaleThemeIntroduction
import me.ayuilos.miffan.data.model.markWhaleThemeSeen
import me.ayuilos.miffan.data.model.withWhaleThemeTrial
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID
import me.ayuilos.miffan.utils.LauncherIcon
import me.ayuilos.miffan.utils.LauncherIconManager
import me.ayuilos.miffan.R

/** One campaign with durable acknowledgement and a saveable in-progress dialog. */
@Composable
internal fun WhaleThemeDiscoveryHost(settings: Settings, store: SettingsStore, eligible: Boolean, onExperienced: () -> Unit = {}) {
    var evaluated by rememberSaveable { mutableStateOf(false) }
    var visible by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(eligible, settings.init) {
        if (!eligible || settings.init || evaluated) return@LaunchedEffect
        evaluated = true
        try {
            if (settings.themeId == WHALE_THEME_ID) {
                if (!settings.whaleThemeDiscovery.settingsSeen || settings.whaleThemeDiscovery.introPending) {
                    withContext(NonCancellable) { store.update { it.markWhaleThemeSeen() } }
                }
            } else if (settings.whaleThemeDiscovery.introPending && !settings.whaleThemeDiscovery.settingsSeen) {
                visible = true
                withContext(NonCancellable) { store.update { it.dismissWhaleThemeIntroduction() } }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // A promotional card must not prevent opening the app when persistence fails.
            Log.w("WhaleDiscovery", "Unable to claim theme introduction", failure)
        }
    }
    LaunchedEffect(settings.themeId, settings.whaleThemeDiscovery.settingsSeen) {
        // A previous composition may have finished applying while the device rotated.
        // Close the restored dialog using durable state rather than the old callback.
        if (settings.themeId == WHALE_THEME_ID && settings.whaleThemeDiscovery.settingsSeen) {
            if (visible) {
                visible = false
                onExperienced()
            }
        }
    }
    if (visible && eligible) {
        WhaleThemeIntroduction(
            busy = busy,
            errorMessage = error,
            onDismiss = { if (!busy) visible = false },
            onTryTheme = { changeIcon ->
                if (!busy) {
                    busy = true
                    error = null
                    scope.launch {
                        // Finish the short local transaction even if rotation disposes the
                        // dialog between changing PackageManager and writing appearance.
                        withContext(NonCancellable) {
                            val manager = LauncherIconManager(context)
                            var previousIcon: LauncherIcon? = null
                            var changedIcon = false
                            try {
                                if (changeIcon) {
                                    withContext(Dispatchers.IO) {
                                        previousIcon = manager.selectedIcon() ?: LauncherIcon.MIFFAN
                                        manager.select(LauncherIcon.WHALE_GIRL)
                                        changedIcon = true
                                    }
                                }
                                store.update { it.withWhaleThemeTrial(resources.getString(R.string.whale_name)) }
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                if (changedIcon) {
                                    runCatching {
                                        withContext(Dispatchers.IO) { manager.select(requireNotNull(previousIcon)) }
                                    }.onFailure { Log.w("WhaleDiscovery", "Unable to restore launcher choice", it) }
                                }
                                error = resources.getString(R.string.whale_switch_failed)
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
            },
        )
    }
}
