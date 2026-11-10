package me.ayuilos.miffan.ui.context

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** The scope pages share elements in when they change (see `heroAnimation`), or null where there is none. */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * Runs [content] in a shared transition scope when [enabled]. Everything inside such a scope is measured twice
 * (once ahead, once for real), on every page and every frame that lays anything out, so it is only worth having
 * where an element actually flies between pages: the assistant pages of the professional shell.
 *
 * [content] keeps its state when [enabled] changes: pages, their effects and the coroutines they started (a
 * page that switches the shell goes on to navigate afterwards) move along instead of starting over.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedElementScope(enabled: Boolean, content: @Composable (SharedTransitionScope?) -> Unit) {
    val latestContent by rememberUpdatedState(content)
    val movable = remember { movableContentOf<SharedTransitionScope?> { scope -> latestContent(scope) } }
    if (enabled) SharedTransitionLayout { movable(this) } else movable(null)
}
