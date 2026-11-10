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
import androidx.compose.ui.Modifier

/** The scope pages share elements in when they change (see `heroAnimation`), or null where there is none. */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * Runs [content] in a shared transition scope when [enabled]. Everything inside such a scope is measured twice
 * (once ahead, once for real), on every page and every frame that lays anything out, so it is only worth having
 * where an element actually flies: between the assistant pages of the professional shell, and in a thread from
 * the welcome into the status row.
 *
 * [content] keeps its state when [enabled] changes: pages, their effects and the coroutines they started (a
 * page that switches the shell goes on to navigate afterwards) move along instead of starting over.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedElementScope(
    enabled: Boolean,
    /** For the scope's own layout; without a scope [content] is laid out by its parent as it is. */
    modifier: Modifier = Modifier,
    content: @Composable (SharedTransitionScope?) -> Unit,
) {
    val latestContent by rememberUpdatedState(content)
    val movable = remember { movableContentOf<SharedTransitionScope?> { scope -> latestContent(scope) } }
    if (enabled) SharedTransitionLayout(modifier) { movable(this) } else movable(null)
}
