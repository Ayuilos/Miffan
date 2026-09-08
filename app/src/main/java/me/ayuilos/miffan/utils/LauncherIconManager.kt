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
    // Retained only to recognize component overrides from older installations.
    WHALE_GIRL_DEEP_SEA("WhaleGirlDeepSea", "蓝色大肥鱼", R.mipmap.ic_launcher_whale_girl);

    val canonical: LauncherIcon get() = if (this == WHALE_GIRL_DEEP_SEA) WHALE_GIRL else this

    companion object {
        val choices: List<LauncherIcon> = listOf(MIFFAN, WHALE_GIRL)
    }
}

/** PackageManager persists the choice across app restarts and upgrades. */
class LauncherIconManager(context: Context) {
    private companion object {
        // All entry families change in one transaction, including API 26–32 rollback.
        val selectionLock = Any()
        val externalFamilies = listOf("ProcessText", "Shortcut", "McpOAuth", "OpenRouterOAuth")
    }

    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager
    private val packageName = context.packageName

    private data class Entry(val component: ComponentName, val icon: LauncherIcon)

    private fun launcherEntry(icon: LauncherIcon) = Entry(
        ComponentName(packageName, "me.ayuilos.miffan.launcher.${icon.aliasName}"), icon,
    )

    private val launcherEntries get() = LauncherIcon.entries.map(::launcherEntry)
    private val externalEntries get() = externalFamilies.flatMap { family ->
        LauncherIcon.entries.map { icon ->
            Entry(ComponentName(packageName, "me.ayuilos.miffan.external.$family.${icon.aliasName}"), icon)
        }
    }

    private fun isEnabled(entry: Entry, state: Int): Boolean = when (state) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> entry.icon.enabledByDefault
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        else -> false
    }

    private fun selectedEntry(): Entry? = launcherEntries.singleOrNull { entry ->
        isEnabled(entry, packageManager.getComponentEnabledSetting(entry.component))
    }

    fun selectedIcon(): LauncherIcon? = synchronized(selectionLock) {
        selectedEntry()?.icon?.canonical
    }

    /** Keep every concrete activity enabled for existing explicit intents and shortcuts. */
    fun select(icon: LauncherIcon): Unit = synchronized(selectionLock) {
        updateEntries(launcherEntries + externalEntries, icon.canonical)
        AppStartupAppearanceController.onLauncherIconChanged(applicationContext)
    }

    /** New aliases start at manifest defaults after upgrade; preserve the prior launcher choice. */
    fun reconcileExternalEntries(): Unit = synchronized(selectionLock) {
        val selected = selectedEntry()?.icon ?: return@synchronized
        if (selected != selected.canonical) {
            // Keep legacy aliases declared, but retire their enabled overrides atomically.
            select(selected.canonical)
        } else {
            updateEntries(externalEntries, selected)
        }
    }

    private fun updateEntries(entries: List<Entry>, icon: LauncherIcon) {
        val previous = entries.associateWith { packageManager.getComponentEnabledSetting(it.component) }
        val desired = entries.associateWith {
            if (it.icon == icon) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        // Manifest defaults already matching the selection need no redundant package writes.
        if (previous.all { (entry, state) -> isEnabled(entry, state) == (entry.icon == icon) }) return
        try {
            applyStates(desired)
            check(desired.all { (entry, state) ->
                packageManager.getComponentEnabledSetting(entry.component) == state
            }) { "App icon selection was not applied to all entry points" }
        } catch (failure: Exception) {
            try {
                applyStates(previous)
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    private fun applyStates(states: Map<Entry, Int>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(states.map { (entry, state) ->
                PackageManager.ComponentEnabledSetting(
                    entry.component, state, PackageManager.DONT_KILL_APP,
                )
            })
        } else {
            // Enable all replacements before disabling old entries on API 26–32.
            states.entries.sortedBy { (entry, state) -> !isEnabled(entry, state) }
                .forEach { (entry, state) ->
                    packageManager.setComponentEnabledSetting(
                        entry.component, state, PackageManager.DONT_KILL_APP,
                    )
                }
        }
    }
}
