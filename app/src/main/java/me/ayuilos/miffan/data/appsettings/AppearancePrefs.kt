package me.ayuilos.miffan.data.appsettings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Light, dark or follow the system, and pure black in the dark; kept outside [me.ayuilos.miffan.data.datastore.Settings]. */
data class Appearance(val colorMode: String = COLOR_MODE_SYSTEM, val amoled: Boolean = false)

const val COLOR_MODE_SYSTEM = "SYSTEM"
const val COLOR_MODE_LIGHT = "LIGHT"
const val COLOR_MODE_DARK = "DARK"
private val COLOR_MODES = setOf(COLOR_MODE_SYSTEM, COLOR_MODE_LIGHT, COLOR_MODE_DARK)

private const val PREFS = "miffan.preferences"
private const val COLOR_MODE_KEY = "colorMode"
private const val AMOLED_KEY = "amoledDark"

/** Where light / dark is kept; [AppearancePrefs] on the phone, a fake in tests. */
interface AppearanceStore {
    fun current(): Appearance
    fun write(appearance: Appearance)

    /** Emits whenever the appearance changes, from anywhere. */
    fun changes(): Flow<Appearance>
}

/** The same preferences the appearance page reads and writes (`rememberColorMode`, `rememberAmoledDarkMode`). */
class AppearancePrefs(context: Context) : AppearanceStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun current(): Appearance = Appearance(
        colorMode = prefs.getString(COLOR_MODE_KEY, COLOR_MODE_SYSTEM)?.takeIf { it in COLOR_MODES } ?: COLOR_MODE_SYSTEM,
        amoled = prefs.getBoolean(AMOLED_KEY, false),
    )

    override fun write(appearance: Appearance) {
        prefs.edit(commit = true) {
            putString(COLOR_MODE_KEY, appearance.colorMode)
            putBoolean(AMOLED_KEY, appearance.amoled)
        }
    }

    override fun changes(): Flow<Appearance> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == COLOR_MODE_KEY || key == AMOLED_KEY) trySend(current())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
