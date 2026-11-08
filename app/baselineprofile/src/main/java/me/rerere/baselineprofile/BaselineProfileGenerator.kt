package me.rerere.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the baseline and startup profiles from the journeys of the IM (easy) shell, which is the default
 * UI: cold start into 消息, open the main partner's long thread and scroll through it, the 伙伴 tab and one
 * profile page, 发现, 我, and back. Replying (the streaming path) is included when the fake model answers.
 *
 * It needs the debug-signed `nonMinifiedRelease` build, which carries the performance fixture, and it loads
 * the fixture itself so the lists have realistic content. Run on a connected device or emulator:
 * ```
 * ./gradlew :app:generateReleaseBaselineProfile
 * ```
 * and, for the streaming part, start `scripts/perf/fake_llm.py` on the host first (on a phone also
 * `adb reverse tcp:18799 tcp:18799`).
 *
 * Check the effect with [StartupBenchmarks] and `scripts/perf/run_bench.sh`.
 *
 * When using this class to generate a baseline profile, only API 33+ or rooted API 28+ are supported.
 * The minimum required version of androidx.benchmark to generate a baseline profile is 1.2.0.
 **/
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** The journey that opens the app: cold start into the 消息 tab. This one also feeds the startup profile. */
    @Test
    fun startup() {
        loadFixtureOnce()
        rule.collect(
            packageName = Perf.targetPackage,
            // Dex layout optimization for what runs until the first screen.
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            waitForChats()
        }
    }

    /** The IM journeys after start-up: thread, partners, discover, me, and back. Baseline profile only. */
    @Test
    fun journeys() {
        loadFixtureOnce()
        rule.collect(
            packageName = Perf.targetPackage,
            includeInStartupProfile = false,
        ) {
            pressHome()
            startActivityAndWait()
            waitForChats()
            flingList("im_chat_list", rounds = 1)

            // The main partner's thread: open, show the history, scroll back through it, reply.
            openMainThread()
            showHistoryIfWelcome()
            flingThreadHistory(times = 4)
            reply("帮我整理一下这周的安排")
            device.pressBack()
            device.wait(Until.hasObject(By.res("im_chat_list")), 5_000)

            // 伙伴 tab and one profile page.
            device.findObject(By.res("im_tab_partners")).click()
            device.wait(Until.hasObject(By.res("im_partner_list")), 5_000)
            flingList("im_partner_list", rounds = 1)
            device.findObject(By.res("im_partner_row_${Perf.SECOND_PARTNER_ID}"))?.let {
                it.click()
                device.waitForIdle()
                Thread.sleep(1_000)
                device.pressBack()
                device.wait(Until.hasObject(By.res("im_partner_list")), 5_000)
            }

            // 发现, 我, and back to 消息.
            visitAllTabs(settleMillis = 800)
        }
    }

    private fun loadFixtureOnce() {
        Perf.bypassHttpProxy()
        if (!fixtureLoaded) {
            Perf.loadFixture()
            fixtureLoaded = true
        }
    }

    /** Sends [text] in the open thread and waits for the (fake) streamed answer, if it arrives. */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.reply(text: String) {
        val input = device.wait(Until.findObject(By.res("thread_input")), 5_000) ?: return
        input.text = text
        device.wait(Until.findObject(By.res("thread_send")), 3_000)?.click() ?: return
        if (device.wait(Until.hasObject(By.res("thread_stop")), 15_000)) {
            device.wait(Until.gone(By.res("thread_stop")), 90_000)
        }
        device.waitForIdle()
    }

    private companion object {
        var fixtureLoaded = false
    }
}
