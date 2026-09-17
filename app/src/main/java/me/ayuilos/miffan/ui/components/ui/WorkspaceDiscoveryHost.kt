package me.ayuilos.miffan.ui.components.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** This campaign is acknowledged on display; Back, outside tap and Later all count as seen. */
@Composable
internal fun WorkspaceDiscoveryHost(
    seen: Boolean,
    eligible: Boolean,
    markSeen: suspend () -> Unit,
    onOpenWorkspaces: () -> Unit,
) {
    var evaluated by rememberSaveable { mutableStateOf(false) }
    var visible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(eligible, seen) {
        if (!eligible || seen || evaluated) return@LaunchedEffect
        evaluated = true
        visible = true
        try {
            withContext(NonCancellable) { markSeen() }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // A discovery prompt must never prevent the user from entering the app.
            Log.w("WorkspaceDiscovery", "Unable to acknowledge introduction", failure)
        }
    }
    if (visible && eligible) {
        WorkspaceIntroduction(
            onDismiss = { visible = false },
            onOpenWorkspaces = {
                visible = false
                onOpenWorkspaces()
            },
        )
    }
}
