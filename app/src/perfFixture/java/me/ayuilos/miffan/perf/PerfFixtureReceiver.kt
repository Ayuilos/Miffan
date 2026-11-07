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
