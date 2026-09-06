package me.ayuilos.miffan.utils

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StyleRes
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.hooks.readStringPreference
import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID
import java.lang.ref.WeakReference

/** Names/styles are persisted by Android; keep them stable across app upgrades. */
enum class AppStartupAppearance(
    @param:StyleRes val themeRes: Int,
    @param:ColorRes val backgroundRes: Int,
    val whale: Boolean,
) {
    MIFFAN_SYSTEM(R.style.Theme_Miffan_Startup_Default, R.color.startup_neutral_background, false),
    MIFFAN_LIGHT(R.style.Theme_Miffan_Startup_DefaultLight, R.color.startup_neutral_light, false),
    MIFFAN_DARK(R.style.Theme_Miffan_Startup_DefaultDark, R.color.startup_neutral_dark, false),
    WHALE_SYSTEM(R.style.Theme_Miffan_Startup_Whale, R.color.startup_whale_background, true),
    WHALE_LIGHT(R.style.Theme_Miffan_Startup_WhaleLight, R.color.whale_girl_icon_background, true),
    WHALE_DARK(R.style.Theme_Miffan_Startup_WhaleDark, R.color.whale_girl_deep_sea_icon_background, true);

    @get:DrawableRes
    val portraitRes: Int get() = if (whale) R.drawable.whale_girl_idle_poster
        else R.drawable.ic_launcher_foreground_vector_miffan
}

internal fun usesWhaleStartupTheme(themeId: String, dynamicColor: Boolean): Boolean =
    themeId == WHALE_THEME_ID && !dynamicColor

internal fun resolveAppStartupAppearance(
    whaleThemeEnabled: Boolean,
    launcherIcon: LauncherIcon?,
    colorMode: ColorMode,
): AppStartupAppearance {
    // An independent deep-sea launcher choice explicitly requests the deep-sea starting view.
    if (launcherIcon == LauncherIcon.WHALE_GIRL_DEEP_SEA) return AppStartupAppearance.WHALE_DARK
    val whale = whaleThemeEnabled || launcherIcon == LauncherIcon.WHALE_GIRL
    return when (colorMode) {
        ColorMode.SYSTEM -> if (whale) AppStartupAppearance.WHALE_SYSTEM else AppStartupAppearance.MIFFAN_SYSTEM
        ColorMode.LIGHT -> if (whale) AppStartupAppearance.WHALE_LIGHT else AppStartupAppearance.MIFFAN_LIGHT
        ColorMode.DARK -> if (whale) AppStartupAppearance.WHALE_DARK else AppStartupAppearance.MIFFAN_DARK
    }
}

/**
 * DataStore remains the source of truth for themes. This tiny mirror is used only before its
 * first emission, so the app's loading view does not briefly show the wrong character.
 * Android 12+ separately persists the system splash theme before the next Activity is created.
 */
object AppStartupAppearanceController {
    private const val PREFERENCES = "miffan.startup"
    private const val WHALE_THEME_ENABLED = "whale_theme_enabled"
    @Volatile private var activeActivity = WeakReference<Activity>(null)
    private var lastApplied: Pair<String, AppStartupAppearance>? = null

    fun current(context: Context): AppStartupAppearance {
        val whaleTheme = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(WHALE_THEME_ENABLED, false)
        val icon = runCatching { LauncherIconManager(context).selectedIcon() }.getOrNull()
        val colorMode = runCatching {
            ColorMode.valueOf(context.readStringPreference("colorMode", ColorMode.SYSTEM.name) ?: ColorMode.SYSTEM.name)
        }.getOrDefault(ColorMode.SYSTEM)
        return resolveAppStartupAppearance(whaleTheme, icon, colorMode)
    }

    fun syncSettings(activity: Activity, themeId: String, dynamicColor: Boolean): AppStartupAppearance {
        val preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val enabled = usesWhaleStartupTheme(themeId, dynamicColor)
        if (!preferences.contains(WHALE_THEME_ENABLED) || preferences.getBoolean(WHALE_THEME_ENABLED, false) != enabled) {
            preferences.edit().putBoolean(WHALE_THEME_ENABLED, enabled).apply()
        }
        return syncCached(activity)
    }

    /** Called on creation/resume as well as after a real settings change. */
    fun syncCached(activity: Activity): AppStartupAppearance {
        val appearance = current(activity)
        applySystemTheme(activity, appearance)
        return appearance
    }

    internal fun onLauncherIconChanged(context: Context) {
        val activity = context.getActivity() ?: activeActivity.get() ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) syncCached(activity)
        else Handler(Looper.getMainLooper()).post {
            if (!activity.isDestroyed) syncCached(activity)
        }
    }

    private fun applySystemTheme(activity: Activity, appearance: AppStartupAppearance) {
        activeActivity = WeakReference(activity)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || activity.isDestroyed) return
        val selection = activity.packageName to appearance
        if (lastApplied == selection) return
        runCatching {
            // This changes only the OS starting window, never enabled launcher aliases.
            activity.splashScreen.setSplashScreenTheme(appearance.themeRes)
        }.onSuccess {
            // Retry once on a new process, even if preferences were restored from backup.
            // Recomposition or Activity recreation within this process does not repeat the call.
            lastApplied = selection
        }.onFailure {
            Log.w("AppStartupAppearance", "Unable to persist the system splash theme", it)
        }
    }
}
