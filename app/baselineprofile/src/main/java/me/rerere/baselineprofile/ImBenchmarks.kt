package me.rerere.baselineprofile

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingGfxInfoMetric
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Macrobenchmarks for the IM shell, run against the `benchmarkRelease` build with the performance fixture
 * loaded (`scripts/perf/run_bench.sh` does both). Numbers are only meaningful on a real device; on the
 * emulator they check that the harness works.
 *
 * The class loads the performance fixture once before its tests, so every run starts from the same data; the
 * streaming test reloads it first because its replies add messages to the main thread.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class ImBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @get:Rule
    val testName = TestName()

    @Before
    fun resetDataBeforeStreaming() {
        if (testName.methodName == "streaming") Perf.loadFixture()
    }

    private val frames get() = listOf(FrameTimingMetric(), FrameTimingGfxInfoMetric())

    /** Cold start into the 消息 tab: TTID and TTFD (the list reports fully drawn once it has data). */
    @Test
    fun startupCold() = rule.measureRepeated(
        packageName = Perf.targetPackage,
        metrics = listOf(StartupTimingMetric()) + frames,
        compilationMode = Perf.compilationMode(),
        startupMode = StartupMode.COLD,
        iterations = Perf.iterations(8),
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        waitForChats()
    }

    /** Warm start: the process lives, the activity is recreated. */
    @Test
    fun startupWarm() = rule.measureRepeated(
        packageName = Perf.targetPackage,
        metrics = listOf(StartupTimingMetric()) + frames,
        compilationMode = Perf.compilationMode(),
        startupMode = StartupMode.WARM,
        iterations = Perf.iterations(8),
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        waitForChats()
    }

    /** Fling the 消息 list down and up. */
    @Test
    fun chatsScroll() = rule.measureRepeated(
        packageName = Perf.targetPackage,
        metrics = frames,
        compilationMode = Perf.compilationMode(),
        startupMode = StartupMode.WARM,
        iterations = Perf.iterations(6),
        setupBlock = { startToChats() },
    ) {
        flingList("im_chat_list", rounds = 3)
    }

    /** Open the 2000-message thread (trace slice `ThreadOpen`), show its history, fling back through it. */
    @Test
    fun threadOpenAndScroll() = rule.measureRepeated(
        packageName = Perf.targetPackage,
        metrics = listOf(TraceSectionMetric("ThreadOpen", TraceSectionMetric.Mode.First)) + frames,
        compilationMode = Perf.compilationMode(),
        startupMode = StartupMode.WARM,
        iterations = Perf.iterations(6),
        setupBlock = { startToChats() },
    ) {
        openMainThread()
        showHistoryIfWelcome()
        flingThreadHistory(times = 6)
    }

    /** 消息 → 伙伴 → 发现 → 我 → 消息, three rounds. */
    @Test
    fun tabSwitch() = rule.measureRepeated(
        packageName = Perf.targetPackage,
        metrics = frames,
        compilationMode = Perf.compilationMode(),
        startupMode = StartupMode.WARM,
        iterations = Perf.iterations(6),
        setupBlock = { startToChats() },
    ) {
        repeat(3) { visitAllTabs() }
    }

    /**
     * Send a message in the main thread and let the fake model (scripts/perf/fake_llm.py) stream ~1500 tokens of
     * Markdown at ~40 tokens/s. The frames measured are those of the whole reply.
     */
    @Test
    fun streaming() = rule.measureRepeated(
        packageName = Perf.targetPackage,
        metrics = frames,
        compilationMode = Perf.compilationMode(),
        startupMode = StartupMode.WARM,
        iterations = Perf.iterations(3),
        setupBlock = {
            startToChats()
            openMainThread()
            showHistoryIfWelcome()
        },
    ) {
        val input = device.wait(Until.findObject(By.res("thread_input")), 10_000)
            ?: error("The composer did not appear")
        input.text = "帮我整理一下这周的安排，列成清单和表格"
        device.wait(Until.findObject(By.res("thread_send")), 5_000)?.click() ?: error("Send did not appear")
        check(device.wait(Until.hasObject(By.res("thread_stop")), 20_000)) {
            "The reply never started; is scripts/perf/fake_llm.py running (adb reverse tcp:18799 tcp:18799 on a phone)?"
        }
        check(device.wait(Until.gone(By.res("thread_stop")), 120_000)) { "The reply did not finish" }
        device.waitForIdle()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun prepare() {
            Perf.bypassHttpProxy()
            Perf.grantRuntimePermissions()
            Perf.loadFixture()
        }
    }
}
