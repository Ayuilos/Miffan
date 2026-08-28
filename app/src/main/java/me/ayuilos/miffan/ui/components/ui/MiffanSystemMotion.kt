package me.ayuilos.miffan.ui.components.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/** One application-context observer, shared by every avatar and released when none is visible. */
private object MiffanSystemMotion {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var state: StateFlow<Boolean>? = null

    @Synchronized
    fun observe(context: Context): StateFlow<Boolean> = state ?: run {
        val resolver = context.applicationContext.contentResolver
        fun disabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { trySend(disabled()) }
            }
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
            trySend(disabled())
            awaitClose { resolver.unregisterContentObserver(observer) }
        }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5_000), disabled())
            .also { state = it }
    }
}

@Composable
fun rememberMiffanReducedMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current.applicationContext
    val flow = remember(context) { MiffanSystemMotion.observe(context) }
    val systemDisabled by flow.collectAsStateWithLifecycle()
    val composeDisabled = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f
    return systemDisabled || composeDisabled
}
