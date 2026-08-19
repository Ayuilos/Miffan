package me.ayuilos.miffan.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.ayuilos.miffan.ui.hooks.CustomTtsState

val LocalTTSState = compositionLocalOf<CustomTtsState> { error("Not provided yet") }
