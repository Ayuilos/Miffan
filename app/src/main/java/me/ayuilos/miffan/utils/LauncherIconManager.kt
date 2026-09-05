package me.ayuilos.miffan.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.DrawableRes
import me.ayuilos.miffan.R

enum class LauncherIcon(
    val aliasName: String,
    val label: String,
    @param:DrawableRes val preview: Int,
    val enabledByDefault: Boolean = false,
) {
    MIFFAN("Miffan", "原版饭碗", R.mipmap.ic_launcher, enabledByDefault = true),
    WHALE_GIRL("WhaleGirl", "蓝色大肥鱼", R.mipmap.ic_launcher_whale_girl),
    WHALE_GIRL_DEEP_SEA("WhaleGirlDeepSea", "深海蓝鱼", R.mipmap.ic_launcher_whale_girl_deep_sea),
}

/** PackageManager persists the choice across app restarts and upgrades. */
class LauncherIconManager(context: Context) {
    private companion object {
        // All activities/managers share the same transaction, including API 26–32 rollback.
        val selectionLock = Any()
    }

    private val packageManager = context.applicationContext.packageManager
    private val packageName = context.packageName

    private fun component(icon: LauncherIcon) = ComponentName(
        packageName,
        "me.ayuilos.miffan.launcher.${icon.aliasName}",
    )

    private fun isEnabled(icon: LauncherIcon, state: Int): Boolean = when (state) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon.enabledByDefault
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        else -> false
    }

    fun selectedIcon(): LauncherIcon? = synchronized(selectionLock) {
        LauncherIcon.entries.singleOrNull { icon ->
            isEnabled(icon, packageManager.getComponentEnabledSetting(component(icon)))
        }
    }

    /** Never disable RouteActivity: it also handles incoming shares and existing shortcuts. */
    fun select(icon: LauncherIcon): Unit = synchronized(selectionLock) {
        val previous = LauncherIcon.entries.associateWith {
            packageManager.getComponentEnabledSetting(component(it))
        }
        val desired = LauncherIcon.entries.associateWith {
            if (it == icon) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (previous == desired) return@synchronized
        try {
            applyStates(desired)
            check(selectedIcon() == icon) { "Launcher icon selection was not applied" }
        } catch (failure: Exception) {
            // On older Android versions the operation takes several calls. Restore the
            // enabled entry first, so a failure never intentionally removes every icon.
            try {
                applyStates(previous)
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    private fun applyStates(states: Map<LauncherIcon, Int>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(states.map { (icon, state) ->
                PackageManager.ComponentEnabledSetting(
                    component(icon), state, PackageManager.DONT_KILL_APP,
                )
            })
        } else {
            // Enable before disabling on API 26–32, which has no atomic batch API.
            states.entries.sortedBy { (icon, state) -> !isEnabled(icon, state) }
                .forEach { (icon, state) ->
                    packageManager.setComponentEnabledSetting(
                        component(icon), state, PackageManager.DONT_KILL_APP,
                    )
                }
        }
    }
}
