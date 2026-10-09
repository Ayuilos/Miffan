package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform

/** The same drawing/input path as professional mode; callers own the surrounding layout. */
@Composable
internal fun RemoteScreenViewport(
    vm: RemoteScreenVM,
    trackpad: Boolean,
    modifier: Modifier = Modifier,
    /** The phone-side zoom buttons; small previews leave them out. */
    zoomControls: Boolean = true,
    failure: @Composable (RemoteScreenUiState) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val bitmap by vm.bitmap.collectAsStateWithLifecycle()
    val frames = vm.frameVersion.collectAsState()
    val cursor = vm.cursor.collectAsState()
    val platform by vm.platform.collectAsStateWithLifecycle()
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (state is RemoteScreenUiState.Connected) bitmap?.let {
            RemoteScreenCanvas(it, frames, cursor, trackpad, platform == RemoteScreenPlatform.MACOS, vm, Modifier.fillMaxSize(),
                zoomControls = zoomControls)
        }
        when (val current = state) {
            RemoteScreenUiState.Connecting -> CircularProgressIndicator()
            is RemoteScreenUiState.Connected -> Unit
            else -> failure(current)
        }
    }
}

@Composable
internal fun RemoteScreenVisibility(vm: RemoteScreenVM) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    DisposableEffect(vm, lifecycle, view) {
        val previousKeepScreenOn = view.keepScreenOn
        fun visible(shown: Boolean) {
            vm.setVisible(shown)
            view.keepScreenOn = shown || previousKeepScreenOn
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> visible(true)
                Lifecycle.Event.ON_STOP -> visible(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        visible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            vm.setVisible(false)
            view.keepScreenOn = previousKeepScreenOn
        }
    }
}
