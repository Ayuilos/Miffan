package me.rerere.ai.util

import android.util.Log
import me.rerere.ai.BuildConfig

/**
 * Logs request bodies and stream events in debug builds only. They hold the whole conversation (attached images
 * included), so a release build must neither spend time encoding them for the log nor leave them in logcat, where
 * a bug report would carry them along.
 */
internal inline fun debugLog(tag: String, message: () -> String) {
    if (BuildConfig.DEBUG) Log.d(tag, message())
}
