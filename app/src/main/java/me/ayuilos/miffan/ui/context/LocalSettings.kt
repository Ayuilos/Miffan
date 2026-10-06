package me.ayuilos.miffan.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.ayuilos.miffan.data.datastore.Settings

val LocalSettings = compositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
