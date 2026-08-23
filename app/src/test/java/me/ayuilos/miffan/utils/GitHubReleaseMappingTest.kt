package me.ayuilos.miffan.utils

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseMappingTest {

    @Test
    fun `formal release maps GitHub metadata and arm64 APK`() {
        val release = json.decodeFromString<GitHubRelease>(
            """
            {
              "tag_name": "2.4.10-miffan.6",
              "published_at": "2026-08-24T00:00:00Z",
              "body": "Release notes",
              "assets": [
                {
                  "name": "Miffan-2.4.10-miffan.6-arm64-v8a.apk",
                  "browser_download_url": "https://github.com/Ayuilos/Miffan/releases/download/2.4.10-miffan.6/Miffan-2.4.10-miffan.6-arm64-v8a.apk",
                  "size": 40277098
                },
                {
                  "name": "unrelated.txt",
                  "browser_download_url": "https://github.com/Ayuilos/Miffan/releases/download/2.4.10-miffan.6/unrelated.txt",
                  "size": 10
                }
              ],
              "ignored_field": true
            }
            """.trimIndent()
        )

        val info = release.toUpdateInfo()

        assertEquals("2.4.10-miffan.6", info.version)
        assertEquals("2026-08-24T00:00:00Z", info.publishedAt)
        assertEquals("Release notes", info.changelog)
        assertEquals(
            "https://github.com/Ayuilos/Miffan/releases/tag/2.4.10-miffan.6",
            info.releaseUrl,
        )
        assertEquals(1, info.downloads.size)
        assertEquals(40_277_098L, info.downloads.single().sizeBytes)
    }

    @Test
    fun `non-Miffan release tag is rejected`() {
        val result = runCatching {
            GitHubRelease(
                tagName = "2.4.10",
                publishedAt = "2026-08-24T00:00:00Z",
            ).toUpdateInfo()
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `draft and prerelease releases are rejected`() {
        val draftResult = runCatching {
            GitHubRelease(
                tagName = "2.4.10-miffan.6",
                publishedAt = "2026-08-24T00:00:00Z",
                draft = true,
            ).toUpdateInfo()
        }
        val prereleaseResult = runCatching {
            GitHubRelease(
                tagName = "2.4.10-miffan.6",
                publishedAt = "2026-08-24T00:00:00Z",
                prerelease = true,
            ).toUpdateInfo()
        }

        assertTrue(draftResult.isFailure)
        assertTrue(prereleaseResult.isFailure)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
