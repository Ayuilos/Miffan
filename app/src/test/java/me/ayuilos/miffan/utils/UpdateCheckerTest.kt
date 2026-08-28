package me.ayuilos.miffan.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ayuilos.miffan.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class UpdateCheckerTest {
    @Test
    fun `source changes recheck immediately while unrelated settings do not`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val settings = MutableStateFlow(Settings(providers = emptyList()))
        val checker = UpdateChecker(
            client = UpdateSourceTest.client { url ->
                requests += url
                200 to UpdateSourceTest.MANIFEST_JSON
            },
            appScope = scope,
            settings = settings,
        )
        try {
            withTimeout(5_000) { checker.updateState.filterIsInstance<UiState.Success<UpdateInfo>>().first() }
            settings.value = settings.value.copy(developerMode = true)
            settings.value = settings.value.copy(
                networkSetting = settings.value.networkSetting.copy(updateDownloadBaseUrl = "https://mirror.example")
            )
            withTimeout(5_000) {
                checker.updateState.filterIsInstance<UiState.Success<UpdateInfo>>()
                    .first { it.data.downloads.single().url.startsWith("https://mirror.example/") }
            }
            settings.value = settings.value.copy(developerMode = false)
            delay(100)
            assertEquals(listOf("$DEFAULT_UPDATE_DOWNLOAD_BASE_URL/latest.json", "https://mirror.example/latest.json"), requests)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `uninitialized and temporarily disabled settings do not request updates`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val settings = MutableStateFlow(Settings(init = true, providers = emptyList()))
        val checker = UpdateChecker(
            client = UpdateSourceTest.client { url ->
                requests += url
                200 to UpdateSourceTest.MANIFEST_JSON
            },
            appScope = scope,
            settings = settings,
        )
        try {
            checker.updateState.first()
            delay(100)
            assertTrue(requests.isEmpty())
            settings.value = settings.value.copy(
                init = false,
                displaySetting = settings.value.displaySetting.copy(
                    updateCheckDisabledUntilEpochMillis = System.currentTimeMillis() + 60_000,
                ),
            )
            delay(100)
            assertTrue(requests.isEmpty())
            settings.value = settings.value.copy(
                displaySetting = settings.value.displaySetting.copy(updateCheckDisabledUntilEpochMillis = 0),
            )
            withTimeout(5_000) { checker.updateState.filterIsInstance<UiState.Success<UpdateInfo>>().first() }
            assertEquals(1, requests.size)
        } finally {
            scope.cancel()
        }
    }
}
