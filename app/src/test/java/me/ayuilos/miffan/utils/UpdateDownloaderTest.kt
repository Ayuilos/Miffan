package me.ayuilos.miffan.utils

import android.app.DownloadManager
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UpdateDownloaderTest {
    @Test
    fun `enqueue failures advance through distinct URLs and persist only remaining fallbacks`() {
        val download = download().copy(fallbackUrls = listOf("https://custom.example/app.apk") + download().fallbackUrls)
        val attempted = mutableListOf<String>()
        val result = enqueueFirstAvailableDownload(download) { candidate ->
            attempted += candidate.url
            if (attempted.size == 1) throw IOException("Cannot enqueue custom source")
            42L
        }

        assertEquals(listOf(download.url, download.fallbackUrls[1]), attempted)
        assertEquals(42L, result.id)
        assertEquals(listOf(download.fallbackUrls.last()), result.download.fallbackUrls)
        assertEquals(result.download, Json.decodeFromString<UpdateDownload>(Json.encodeToString(result.download)))
    }

    @Test
    fun `network download failure advances to official and finally GitHub without looping`() {
        val official = download().afterFailure(DownloadManager.STATUS_FAILED, 404)!!
        assertEquals("https://official.example/app.apk", official.url)
        val github = official.afterFailure(DownloadManager.STATUS_FAILED, DownloadManager.ERROR_HTTP_DATA_ERROR)!!
        assertEquals("https://github.com/example/app.apk", github.url)
        assertNull(github.afterFailure(DownloadManager.STATUS_FAILED, 503))
    }

    @Test
    fun `completed paused running cancelled and storage failures do not trigger a new download`() {
        listOf(DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_PAUSED,
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, 0).forEach { status ->
            assertNull(download().afterFailure(status, 404))
        }
        listOf(DownloadManager.ERROR_INSUFFICIENT_SPACE, DownloadManager.ERROR_FILE_ALREADY_EXISTS,
            DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_DEVICE_NOT_FOUND).forEach { reason ->
            assertNull(download().afterFailure(DownloadManager.STATUS_FAILED, reason))
        }
    }

    @Test
    fun `failure to enqueue every source is reported`() {
        val failure = runCatching {
            enqueueFirstAvailableDownload(download()) { throw IOException("Unavailable") }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(3, failure!!.suppressed.size)
    }

    private fun download() = UpdateDownload(
        name = "Miffan-3.0.5-arm64-v8a.apk",
        url = "https://custom.example/app.apk",
        sizeBytes = 123L,
        fallbackUrls = listOf("https://official.example/app.apk", "https://github.com/example/app.apk"),
    )
}
