package me.ayuilos.miffan.utils

import android.os.Build
import android.os.Trace

/**
 * An async trace slice from tapping a chat row to the thread having its messages, so macrobenchmarks
 * (and a Perfetto trace) can measure how long opening a thread takes. A no-op unless a trace is recording.
 */
object ThreadOpenTrace {
    private const val NAME = "ThreadOpen"
    private const val COOKIE = 7421
    private var open = false

    fun begin() {
        if (Build.VERSION.SDK_INT < 29 || open) return
        open = true
        Trace.beginAsyncSection(NAME, COOKIE)
    }

    fun end() {
        if (Build.VERSION.SDK_INT < 29 || !open) return
        open = false
        Trace.endAsyncSection(NAME, COOKIE)
    }
}
