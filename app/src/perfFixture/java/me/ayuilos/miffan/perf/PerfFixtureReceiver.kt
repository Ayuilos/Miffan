package me.ayuilos.miffan.perf

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import me.ayuilos.miffan.AppScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * `adb shell am broadcast -a me.ayuilos.miffan.PERF_FIXTURE -n <package>/me.ayuilos.miffan.perf.PerfFixtureReceiver`
 *
 * Replaces the app's data with the performance data set (see `docs/development/PERF_FIXTURE.md`). Only compiled
 * into the debug and macrobenchmark variants; the shipping release APK has neither this class nor its manifest entry.
 */
class PerfFixtureReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        // The fixture wipes every table. Only the debug and macrobenchmark packages may run it, so a build that
        // somehow carries it under another id (the shipping one) refuses instead of destroying real data.
        if (!context.packageName.endsWith(".debug") && !context.packageName.endsWith(".bench")) {
            Log.e(TAG, "Refusing to run in ${context.packageName}: not a .debug or .bench package")
            pending.setResult(Activity.RESULT_CANCELED, "REFUSED ${context.packageName}", null)
            pending.finish()
            return
        }
        val anchor = if (intent.hasExtra(EXTRA_ANCHOR)) intent.getLongExtra(EXTRA_ANCHOR, 0L) else PerfFixture.defaultAnchor()
        get<AppScope>().launch {
            try {
                val summary = PerfFixture(context.applicationContext).run(anchor)
                pending.setResult(Activity.RESULT_OK, "OK $summary", null)
            } catch (e: Throwable) {
                Log.e(TAG, "Fixture failed", e)
                pending.setResult(Activity.RESULT_CANCELED, "FAILED ${e::class.java.simpleName}: ${e.message}", null)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "me.ayuilos.miffan.PERF_FIXTURE"

        /** Epoch millis of "now" for the generated timestamps; defaults to midnight at the start of today. */
        const val EXTRA_ANCHOR = "anchor"
        private const val TAG = "PerfFixture"
    }
}
