package me.ayuilos.miffan.ui.context

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavKey
import me.ayuilos.miffan.Screen

class Navigator(private val backStack: MutableList<NavKey>) {
    fun navigate(screen: Screen, builder: NavigateOptionsBuilder.() -> Unit = {}) {
        val options = NavigateOptionsBuilder().apply(builder)

        options.popUpToScreen?.let { target ->
            val targetIndex = backStack.indexOfLast { it == target }
            if (targetIndex != -1) {
                val removeFromIndex = if (options.popUpToInclusive) targetIndex else targetIndex + 1
                repeat(backStack.size - removeFromIndex) {
                    backStack.removeLastOrNull()
                }
            }
        }

        if (options.launchSingleTop && backStack.lastOrNull() == screen) {
            return
        }

        backStack.add(screen)
    }

    fun clearAndNavigate(screen: Screen) {
        backStack.clear()
        backStack.add(screen)
    }

    /**
     * Puts [replacement] where [screen] is, with [root] below it when the stack lacks one. Returns
     * false when [screen] already left the stack, e.g. because another navigation cleared it.
     */
    fun replace(screen: Screen, replacement: Screen, root: Screen): Boolean {
        val index = backStack.lastIndexOf(screen)
        if (index == -1) return false
        backStack[index] = replacement
        if (root !in backStack) backStack.add(0, root)
        return true
    }

    fun popBackStack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }
}

class NavigateOptionsBuilder {
    internal var popUpToScreen: Screen? = null
    internal var popUpToInclusive: Boolean = false
    var launchSingleTop: Boolean = false

    fun popUpTo(screen: Screen, builder: PopUpToBuilder.() -> Unit = {}) {
        val options = PopUpToBuilder().apply(builder)
        popUpToScreen = screen
        popUpToInclusive = options.inclusive
    }
}

class PopUpToBuilder {
    var inclusive: Boolean = false
}

val LocalNavController = compositionLocalOf<Navigator> {
    error("No Navigator provided")
}
