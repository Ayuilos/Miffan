package me.ayuilos.miffan.utils

import kotlinx.serialization.json.Json
import org.kxml2.io.KXmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `independent SemVer release maps new APK name`() {
        val release = GitHubRelease(
            tagName = "3.0.0",
            publishedAt = "2026-09-01T00:00:00Z",
            assets = listOf(
                GitHubReleaseAsset(
                    name = "Miffan-3.0.0-arm64-v8a.apk",
                    browserDownloadUrl =
                        "https://github.com/Ayuilos/Miffan/releases/download/3.0.0/" +
                            "Miffan-3.0.0-arm64-v8a.apk",
                    size = 41_000_000,
                )
            ),
        )

        val info = release.toUpdateInfo()

        assertEquals("3.0.0", info.version)
        assertEquals("Miffan-3.0.0-arm64-v8a.apk", info.downloads.single().name)
        assertEquals(
            "https://github.com/Ayuilos/Miffan/releases/tag/3.0.0",
            info.releaseUrl,
        )
    }

    @Test
    fun `legacy and independent SemVer tags are supported`() {
        assertTrue("2.4.11-miffan.1".isSupportedMiffanReleaseVersion())
        assertTrue("3.0.0".isSupportedMiffanReleaseVersion())
        assertTrue("3.0.0-rc.1".isSupportedMiffanReleaseVersion())
        assertFalse("03.0.0".isSupportedMiffanReleaseVersion())
        assertFalse("3.0.0-rc.01".isSupportedMiffanReleaseVersion())
    }

    @Test
    fun `unsupported release tag is rejected`() {
        val result = runCatching {
            GitHubRelease(
                tagName = "release-3.0.0",
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
                tagName = "3.0.0",
                publishedAt = "2026-08-24T00:00:00Z",
                prerelease = true,
            ).toUpdateInfo()
        }

        assertTrue(draftResult.isFailure)
        assertTrue(prereleaseResult.isFailure)
    }

    @Test
    fun `SemVer prerelease tag is rejected even when release metadata is misclassified`() {
        val result = runCatching {
            GitHubRelease(
                tagName = "3.0.0-rc.1",
                publishedAt = "2026-08-24T00:00:00Z",
                prerelease = false,
            ).toUpdateInfo()
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `Atom fallback skips SemVer prereleases and maps next formal release`() {
        val info = parseAtom(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <updated>2026-08-25T00:00:00Z</updated>
                <link rel="alternate" href="https://github.com/Ayuilos/Miffan/releases/tag/3.0.0-rc.1" />
                <content>Release candidate</content>
              </entry>
              <entry>
                <updated>2026-09-01T00:00:00Z</updated>
                <link rel="alternate" href="https://github.com/Ayuilos/Miffan/releases/tag/3.0.0" />
                <content>Formal release</content>
              </entry>
            </feed>
            """.trimIndent()
        )

        assertEquals("3.0.0", info.version)
        assertEquals("Miffan-3.0.0-arm64-v8a.apk", info.downloads.single().name)
        assertEquals(
            "https://github.com/Ayuilos/Miffan/releases/download/3.0.0/" +
                "Miffan-3.0.0-arm64-v8a.apk",
            info.downloads.single().url,
        )
    }

    @Test
    fun `Atom fallback retains historical formal release compatibility`() {
        val info = parseAtom(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <updated>2026-08-23T15:29:40Z</updated>
                <link rel="alternate" href="https://github.com/Ayuilos/Miffan/releases/tag/2.4.11-miffan.1" />
                <content>Historical release</content>
              </entry>
            </feed>
            """.trimIndent()
        )

        assertEquals("2.4.11-miffan.1", info.version)
        assertEquals("Miffan-2.4.11-miffan.1-arm64-v8a.apk", info.downloads.single().name)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun parseAtom(xml: String): UpdateInfo =
            parseLatestMiffanReleaseAtom(xml, KXmlParser())
    }
}
