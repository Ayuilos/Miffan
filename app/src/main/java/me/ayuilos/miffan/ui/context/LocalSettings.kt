package me.ayuilos.miffan.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import me.ayuilos.miffan.data.datastore.Settings

val LocalSettings = staticCompositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
