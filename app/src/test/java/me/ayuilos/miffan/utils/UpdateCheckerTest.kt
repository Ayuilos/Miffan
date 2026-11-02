package me.ayuilos.miffan.utils

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.UpdateReminder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class UpdateCheckerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 1_000_000_000L
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private var latest = "4.9.0"
    private var failing = false
    private val settings = MutableStateFlow(Settings(providers = emptyList()))
    private val downloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    private val downloader = mockk<UpdateApkDownloader>(relaxed = true) { every { downloadState } returns this@UpdateCheckerTest.downloadState }
    private val environment = FakeEnvironment()
    private val store = MemoryStore()

    @After fun cleanup() = scope.cancel()

    private fun checker() = UpdateChecker(
        client = okhttp3.OkHttpClient(),
        appScope = scope,
        settings = settings,
        downloader = downloader,
        store = store,
        environment = environment,
        clock = { now },
        fetch = { baseUrl ->
            requests += baseUrl
            if (failing) throw java.io.IOException("offline")
            info(latest)
        },
    )

    @Test
    fun `startup checks once and foreground checks wait for the interval`() = runBlocking {
        val checker = checker()
        eventually { checker.presentation.value.available != null }
        assertEquals(1, requests.size)

        checker.checkIfDueNow()
        assertEquals(1, requests.size)

        now += UPDATE_RECHECK_INTERVAL_MILLIS
        checker.checkIfDueNow()
        assertEquals(2, requests.size)
    }

    @Test
    fun `a failed check keeps the last result and retries sooner`() = runBlocking {
        val checker = checker()
        eventually { requests.size == 1 && store.state.lastAttemptAt != 0L }
        failing = true
        now += UPDATE_RECHECK_INTERVAL_MILLIS
        checker.checkIfDueNow()
        eventually { checker.presentation.value.lastCheckFailed }
        assertEquals("4.9.0", checker.presentation.value.available?.version)

        now += UPDATE_RETRY_INTERVAL_MILLIS
        failing = false
        checker.checkIfDueNow()
        assertEquals(3, requests.size)
        eventually { !checker.presentation.value.lastCheckFailed }
    }

    @Test
    fun `a new download source is checked right away`() = runBlocking {
        checker()
        eventually { requests.size == 1 && store.state.lastAttemptAt != 0L }
        settings.value = settings.value.copy(developerMode = true)
        settings.value = settings.value.copy(
            networkSetting = settings.value.networkSetting.copy(updateDownloadBaseUrl = "https://mirror.example")
        )
        eventually { requests.size == 2 }
        settings.value = settings.value.copy(developerMode = false)
        delay(100)
        assertEquals(listOf(DEFAULT_UPDATE_DOWNLOAD_BASE_URL, "https://mirror.example"), requests)
    }

    @Test
    fun `uninitialized or paused settings skip automatic checks but not the manual one`() = runBlocking {
        settings.value = Settings(init = true, providers = emptyList())
        val checker = checker()
        delay(100)
        assertTrue(requests.isEmpty())
        settings.value = Settings(providers = emptyList()).let {
            it.copy(displaySetting = it.displaySetting.copy(updateCheckDisabledUntilEpochMillis = now + 60_000))
        }
        delay(100)
        assertTrue(requests.isEmpty())
        eventually { !checker.presentation.value.remindersOn }

        checker.checkNow().join()
        assertEquals(1, requests.size)
    }

    @Test
    fun `badge clears when seen, snooze hides the card, skip hides the version`() = runBlocking {
        val checker = checker()
        eventually { checker.presentation.value.available != null }
        assertTrue(checker.presentation.value.showBadge)
        assertTrue(checker.presentation.value.showCard)

        checker.markSeen("4.9.0")
        eventually { !checker.presentation.value.showBadge }
        assertTrue(checker.presentation.value.showCard)

        checker.snooze("4.9.0")
        eventually { !checker.presentation.value.showCard }

        checker.skip("4.9.0")
        eventually { checker.presentation.value.available == null }
        assertEquals("4.9.0", checker.presentation.value.skippedVersion)

        checker.unskip()
        eventually { checker.presentation.value.available != null }
        // A newer release is a fresh reminder.
        latest = "4.9.1"
        now += UPDATE_RECHECK_INTERVAL_MILLIS
        checker.checkIfDueNow()
        eventually { checker.presentation.value.showBadge }
    }

    @Test
    fun `a ready APK keeps the badge even after the page was seen`() = runBlocking {
        val checker = checker()
        eventually { checker.presentation.value.available != null }
        checker.markSeen("4.9.0")
        val download = info("4.9.0").downloads.single()
        downloadState.value = UpdateDownloadState.Ready(download)
        eventually { checker.presentation.value.showBadge }
        // A download from an older release is never shown against this one.
        downloadState.value = UpdateDownloadState.Ready(info("4.8.0").downloads.single())
        eventually { checker.presentation.value.download == UpdateDownloadState.Idle }
    }

    @Test
    fun `notifies once per version, only in the background and only when asked to`() = runBlocking {
        environment.foreground = true
        val checker = checker()
        eventually { requests.size == 1 && store.state.lastAttemptAt != 0L }
        assertTrue(environment.notified.isEmpty())

        environment.foreground = false
        now += UPDATE_RECHECK_INTERVAL_MILLIS
        checker.checkIfDueNow()
        assertEquals(listOf("4.9.0"), environment.notified)
        now += UPDATE_RECHECK_INTERVAL_MILLIS
        checker.checkIfDueNow()
        assertEquals(listOf("4.9.0"), environment.notified)

        settings.value = settings.value.copy(
            displaySetting = settings.value.displaySetting.copy(updateReminder = UpdateReminder.BADGE)
        )
        latest = "4.9.1"
        now += UPDATE_RECHECK_INTERVAL_MILLIS
        checker.checkIfDueNow()
        assertEquals(listOf("4.9.0"), environment.notified)
    }

    @Test
    fun `wifi download starts quietly once per version instead of notifying`() = runBlocking {
        settings.value = settings.value.copy(
            displaySetting = settings.value.displaySetting.copy(updateAutoDownloadOnWifi = true)
        )
        environment.unmetered = true
        val checker = checker()
        eventually { requests.size == 1 && store.state.lastAttemptAt != 0L }
        verify(exactly = 1) { downloader.downloadUpdate(info("4.9.0").downloads.single(), quiet = true) }
        assertTrue(environment.notified.isEmpty())

        now += UPDATE_RECHECK_INTERVAL_MILLIS
        checker.checkIfDueNow()
        verify(exactly = 1) { downloader.downloadUpdate(any(), quiet = true) }
    }

    @Test
    fun `no wifi download on a metered network or when the switch is off`() = runBlocking {
        environment.unmetered = true
        checker()
        eventually { requests.size == 1 && store.state.lastAttemptAt != 0L }
        verify(exactly = 0) { downloader.downloadUpdate(any(), any()) }
    }

    @Test
    fun `what's new appears after an upgrade with this version's notes`() = runBlocking {
        store.state = UpdateLocalState(lastRunVersionCode = 100)
        environment.versionCode = 101
        latest = environment.currentVersion
        val checker = checker()
        eventually { checker.presentation.value.whatsNew != null }
        assertNull(checker.presentation.value.available)
        checker.dismissWhatsNew()
        eventually { checker.presentation.value.whatsNew == null }
    }

    @Test
    fun `a fresh install shows no what's new`() = runBlocking {
        environment.versionCode = 101
        latest = environment.currentVersion
        val checker = checker()
        eventually { requests.size == 1 && store.state.lastAttemptAt != 0L }
        assertNull(checker.presentation.value.whatsNew)
        assertEquals(101, store.state.lastRunVersionCode)
    }

    private suspend fun eventually(condition: () -> Boolean) {
        repeat(500) {
            if (condition()) return
            delay(10)
        }
        throw AssertionError("Condition not met")
    }

    private fun info(version: String) = UpdateInfo(
        version = version,
        publishedAt = "2026-11-02T00:00:00Z",
        changelog = "",
        downloads = listOf(UpdateDownload(apkAssetName(version), "https://example.com/$version.apk", 10, sha256 = "a".repeat(64))),
        releaseUrl = releasePageUrl(version),
    )

    private class MemoryStore : UpdateLocalStore {
        var state = UpdateLocalState()
        override fun read() = state
        override fun write(state: UpdateLocalState) { this.state = state }
    }

    private class FakeEnvironment : UpdateEnvironment {
        override val currentVersion = "4.8.1"
        var versionCode = 100
        override val currentVersionCode get() = versionCode
        override val isPlayStore = false
        override val isUpgradeInstall = false
        var foreground = false
        var unmetered = false
        val notified = Collections.synchronizedList(mutableListOf<String>())
        override fun isAppInForeground() = foreground
        override fun isUnmetered() = unmetered
        override fun notifyNewVersion(info: UpdateInfo) { notified += info.version }
        override fun cancelNewVersionNotification() = Unit
    }
}
