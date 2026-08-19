package me.ayuilos.miffan.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.ayuilos.miffan.ui.hooks.CustomAsrState

val LocalASRState = compositionLocalOf<CustomAsrState> { error("Not provided yet") }

