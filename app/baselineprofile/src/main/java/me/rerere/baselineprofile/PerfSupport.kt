package me.rerere.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/**
 * Shared steps of the benchmarks and the baseline-profile generator. Elements are found by the resource ids
 * the app exposes through `testTag` (`RouteActivity` turns on `testTagsAsResourceId`), not by text, so the
 * steps work in every language.
 */
internal object Perf {
    /** The main partner of the performance fixture; it owns the 2000-message thread. */
    const val MAIN_PARTNER_ID = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"

    /** The second partner ("阿青"), used for the profile-page journey. */
    const val SECOND_PARTNER_ID = "e4737cb6-57b0-3169-8c25-7dea81cc5df7"

    private const val FIXTURE_RECEIVER = "me.ayuilos.miffan.perf.PerfFixtureReceiver"
    private const val FIXTURE_ACTION = "me.ayuilos.miffan.PERF_FIXTURE"

    val targetPackage: String
        get() = InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: throw Exception("targetAppId not passed as instrumentation runner arg")

    /**
     * `-Pandroid.testInstrumentationRunnerArguments.compilation=none|partial|full` (default partial, which uses the
     * baseline profile the app ships with, like a Play Store install).
     */
    fun compilationMode(): CompilationMode = when (InstrumentationRegistry.getArguments().getString("compilation")) {
        "none" -> CompilationMode.None()
        "full" -> CompilationMode.Full()
        else -> CompilationMode.Partial(BaselineProfileMode.UseIfAvailable)
    }

    fun iterations(default: Int): Int =
        InstrumentationRegistry.getArguments().getString("iterations")?.toIntOrNull() ?: default

    /**
     * The benchmark library talks to an on-device trace processor at http://localhost:9001. A device-wide HTTP proxy
     * (the emulator has one, for the host's internet) would answer that with 502, so this process stops using it.
     * The app under test is another process and keeps the proxy, which is how it reaches the fake model on the host.
     */
    fun bypassHttpProxy() {
        listOf("http", "https").forEach {
            System.clearProperty("$it.proxyHost")
            System.clearProperty("$it.proxyPort")
        }
    }

    /** First-run permission cards would otherwise sit in the lists the benchmarks scroll. */
    fun grantRuntimePermissions() {
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        listOf("android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_LOCAL_NETWORK").forEach {
            runCatching { device.executeShellCommand("pm grant $targetPackage $it") }
        }
    }

    /** Replaces the app's data with the fixture. Returns the receiver's result line (`OK partners=6 ...`). */
    fun loadFixture(): String {
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val out = device.executeShellCommand("am broadcast -a $FIXTURE_ACTION -n $targetPackage/$FIXTURE_RECEIVER")
        check("data=\"OK" in out) { "Fixture did not load: $out" }
        return out.lineSequence().firstOrNull { "data=" in it }.orEmpty()
    }
}

/** Cold or warm start, then wait until the 消息 list is on screen with its data. */
internal fun MacrobenchmarkScope.startToChats() {
    pressHome()
    startActivityAndWait()
    waitForChats()
}

internal fun MacrobenchmarkScope.waitForChats() {
    check(device.wait(Until.hasObject(By.res("im_chat_list")), 20_000)) { "The 消息 list did not appear" }
    check(device.wait(Until.hasObject(By.res("im_chat_row_${Perf.MAIN_PARTNER_ID}")), 10_000)) {
        "The fixture's main partner is missing from the 消息 list; load the fixture first"
    }
}

/** Opens the main partner's thread from the 消息 list and waits for the thread to show. */
internal fun MacrobenchmarkScope.openMainThread() {
    device.findObject(By.res("im_chat_row_${Perf.MAIN_PARTNER_ID}")).click()
    check(device.wait(Until.hasObject(By.res("thread_list")), 20_000)) { "The thread did not open" }
    device.waitForIdle()
}

/** A thread left alone for a while opens on the partner's welcome screen; show the old messages instead. */
internal fun MacrobenchmarkScope.showHistoryIfWelcome() {
    device.wait(Until.findObject(By.res("thread_show_history")), 1_500)?.let {
        it.click()
        device.waitForIdle()
    }
}

/** Fling the open thread back through its history. A DOWN gesture moves older messages into view. */
internal fun MacrobenchmarkScope.flingThreadHistory(times: Int) {
    val list = device.findObject(By.res("thread_list")) ?: return
    list.setGestureMargin(device.displayWidth / 8)
    repeat(times) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
    }
}

internal fun MacrobenchmarkScope.flingList(res: String, rounds: Int) {
    val list = device.findObject(By.res(res)) ?: return
    list.setGestureMargin(device.displayWidth / 8)
    repeat(rounds) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }
}

/** 消息 → 伙伴 → 发现 → 我 → 消息. */
internal fun MacrobenchmarkScope.visitAllTabs(settleMillis: Long = 400) {
    for (tab in listOf("partners", "discover", "me", "chats")) {
        device.findObject(By.res("im_tab_$tab"))?.click()
        device.waitForIdle()
        Thread.sleep(settleMillis)
    }
}
