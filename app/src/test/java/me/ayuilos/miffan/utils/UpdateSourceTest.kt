package me.ayuilos.miffan.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UpdateSourceTest {
    @Test
    fun `custom directories are normalized and the default is only tried once`() {
        assertEquals("https://mirror.example/miffan", normalizeUpdateDownloadBaseUrl(" https://MIRROR.example/miffan/// "))
        assertEquals(listOf(DEFAULT_UPDATE_DOWNLOAD_BASE_URL), updateDownloadSources(""))
        assertEquals(listOf(DEFAULT_UPDATE_DOWNLOAD_BASE_URL), updateDownloadSources("$DEFAULT_UPDATE_DOWNLOAD_BASE_URL/"))
        assertEquals(listOf("https://mirror.example/miffan", DEFAULT_UPDATE_DOWNLOAD_BASE_URL), updateDownloadSources("https://mirror.example/miffan/"))
    }

    @Test
    fun `invalid or insecure source addresses are rejected`() {
        listOf(
            "http://mirror.example", "ftp://mirror.example", "not a url",
            "https://user:password@mirror.example", "https://mirror.example?token=secret",
            "https://mirror.example/#fragment", "https://mirror.example/latest.json",
            "https://mirror.example/app.apk", "https://mirror.example/a b",
            "https://mirror.example/LATEST.JSON/", "https://mirror.example/APP.APK/",
        ).forEach { value ->
            assertNull(value, normalizeUpdateDownloadBaseUrl(value))
            assertEquals(listOf(DEFAULT_UPDATE_DOWNLOAD_BASE_URL), updateDownloadSources(value))
        }
    }

    @Test
    fun `existing manifest maps an official download with GitHub fallback`() {
        val info = manifest().toUpdateInfo(DEFAULT_UPDATE_DOWNLOAD_BASE_URL, emptyList())

        assertEquals("3.0.5", info.version)
        assertEquals(40_466_226L, info.downloads.single().sizeBytes)
        assertEquals("$DEFAULT_UPDATE_DOWNLOAD_BASE_URL$APK_PATH", info.downloads.single().url)
        assertEquals(listOf(releaseDownloadUrl("3.0.5")), info.downloads.single().fallbackUrls)
        assertEquals("", info.changelog)
    }

    @Test
    fun `unchanged official manifest is rebased onto the selected mirror directory`() {
        val info = manifest().toUpdateInfo("https://mirror.example/miffan", listOf(DEFAULT_UPDATE_DOWNLOAD_BASE_URL))

        assertEquals("https://mirror.example/miffan$APK_PATH", info.downloads.single().url)
        assertEquals(
            listOf("$DEFAULT_UPDATE_DOWNLOAD_BASE_URL$APK_PATH", releaseDownloadUrl("3.0.5")),
            info.downloads.single().fallbackUrls,
        )
    }

    @Test
    fun `manifest can supply optional changelog and ignore future fields`() {
        val value = MANIFEST_JSON.replace("\"version\":", "\"changelog\":\"Notes\",\"futureField\":true,\"version\":")
        val info = Json { ignoreUnknownKeys = true }.decodeFromString<UpdateManifest>(value)
            .toUpdateInfo(DEFAULT_UPDATE_DOWNLOAD_BASE_URL, emptyList())
        assertEquals("Notes", info.changelog)
    }

    @Test
    fun `malformed release metadata cannot produce a download`() {
        val valid = manifest()
        listOf(
            valid.copy(version = "3.0.6-rc.1"),
            valid.copy(version = "../../../other"),
            valid.copy(publishedAt = "not a date"),
            valid.copy(architecture = "x86_64"),
            valid.copy(fileName = "other.apk"),
            valid.copy(size = 0),
            valid.copy(sha256 = "invalid"),
            valid.copy(downloadUrl = "https://downloads.ayuilos.me.evil.example$APK_PATH"),
            valid.copy(downloadUrl = "$DEFAULT_UPDATE_DOWNLOAD_BASE_URL$APK_PATH?redirect=evil"),
            valid.copy(checksumUrl = "https://evil.example/checksum"),
            valid.copy(releaseUrl = "https://github.com/other/repo/releases/tag/3.0.5"),
        ).forEach { invalid ->
            assertTrue(runCatching { invalid.toUpdateInfo(DEFAULT_UPDATE_DOWNLOAD_BASE_URL, emptyList()) }.isFailure)
        }
    }

    @Test
    fun `a healthy custom source does not contact the official source or GitHub`() = runBlocking {
        val requests = mutableListOf<String>()
        val client = client { url ->
            requests += url
            200 to MANIFEST_JSON
        }
        val info = UpdateReleaseSource(client, "https://mirror.example/miffan").fetchLatest()

        assertEquals(listOf("https://mirror.example/miffan/latest.json"), requests)
        assertEquals("https://mirror.example/miffan$APK_PATH", info.downloads.single().url)
    }

    @Test
    fun `HTTP errors and malformed manifests fall back to official source`() = runBlocking {
        for (badResponse in listOf(503 to "Unavailable", 200 to "{}")) {
            val requests = mutableListOf<String>()
            val client = client { url ->
                requests += url
                if (url.startsWith("https://mirror.example/")) badResponse else 200 to MANIFEST_JSON
            }
            val info = UpdateReleaseSource(client, "https://mirror.example").fetchLatest()

            assertEquals(listOf("https://mirror.example/latest.json", "$DEFAULT_UPDATE_DOWNLOAD_BASE_URL/latest.json"), requests)
            assertEquals("$DEFAULT_UPDATE_DOWNLOAD_BASE_URL$APK_PATH", info.downloads.single().url)
            assertEquals(listOf(releaseDownloadUrl("3.0.5")), info.downloads.single().fallbackUrls)
        }
    }

    @Test
    fun `unavailable mirrors fall back to GitHub API`() = runBlocking {
        val requests = mutableListOf<String>()
        val client = client { url ->
            requests += url
            if (url.startsWith("https://api.github.com/")) {
                200 to """{
                  "tag_name":"3.0.5", "published_at":"2026-08-28T08:21:48Z",
                  "body":"GitHub release notes",
                  "assets":[{"name":"Miffan-3.0.5-arm64-v8a.apk",
                    "browser_download_url":"${releaseDownloadUrl("3.0.5")}", "size":40466226}]
                }"""
            } else {
                throw IOException("Source unreachable")
            }
        }
        val info = UpdateReleaseSource(client, "https://mirror.example").fetchLatest()

        assertEquals(listOf("https://mirror.example/latest.json", "$DEFAULT_UPDATE_DOWNLOAD_BASE_URL/latest.json", "https://api.github.com/repos/Ayuilos/Miffan/releases/latest"), requests)
        assertEquals(releaseDownloadUrl("3.0.5"), info.downloads.single().url)
        assertEquals("GitHub release notes", info.changelog)
    }

    @Test
    fun `cancellation stops fallback and all source errors remain diagnosable`() = runBlocking {
        val cancellation = CancellationException("source changed")
        var reachedFallback = false
        val cancelled = runCatching {
            fetchFirstRelease(listOf(
                ReleaseSource { throw cancellation },
                ReleaseSource { reachedFallback = true; error("Should not run") },
            ))
        }
        assertSame(cancellation, cancelled.exceptionOrNull())
        assertTrue(!reachedFallback)

        val first = IOException("First source")
        val last = IOException("Last source")
        val failure = runCatching {
            fetchFirstRelease(listOf(ReleaseSource { throw first }, ReleaseSource { throw last }))
        }.exceptionOrNull()!!
        assertEquals(listOf(first, last), failure.suppressed.toList())
    }

    companion object {
        private const val APK_PATH = "/releases/3.0.5/Miffan-3.0.5-arm64-v8a.apk"
        const val MANIFEST_JSON = """{
          "version":"3.0.5", "publishedAt":"2026-08-28T08:21:48Z", "architecture":"arm64-v8a",
          "fileName":"Miffan-3.0.5-arm64-v8a.apk", "size":40466226,
          "sha256":"69a92099778ffb7371881e0e0aef989d6c19021517dcf1eaa6d96eaf8469aae8",
          "downloadUrl":"https://downloads.ayuilos.me/releases/3.0.5/Miffan-3.0.5-arm64-v8a.apk",
          "checksumUrl":"https://downloads.ayuilos.me/releases/3.0.5/Miffan-3.0.5-arm64-v8a.apk.sha256",
          "releaseUrl":"https://github.com/Ayuilos/Miffan/releases/tag/3.0.5"
        }"""

        private fun manifest() = Json.decodeFromString<UpdateManifest>(MANIFEST_JSON)

        fun client(respond: (String) -> Pair<Int, String>): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val (code, body) = respond(chain.request().url.toString())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("Test response").body(body.toResponseBody()).build()
            }.build()
    }
}
