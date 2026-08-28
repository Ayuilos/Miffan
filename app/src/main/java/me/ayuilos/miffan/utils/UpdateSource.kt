package me.ayuilos.miffan.utils

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.BuildConfig
import me.rerere.common.http.await
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

const val DEFAULT_UPDATE_DOWNLOAD_BASE_URL = "https://downloads.ayuilos.me"

/** A directory containing latest.json and releases/<tag>/<APK>, not an APK URL. */
fun normalizeUpdateDownloadBaseUrl(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() } || '\\' in trimmed) return null
    val url = trimmed.toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.query != null || url.fragment != null ||
        url.pathSegments.lastOrNull { it.isNotEmpty() }
            ?.let { it.endsWith(".json", ignoreCase = true) || it.endsWith(".apk", ignoreCase = true) } == true
    ) return null
    return url.toString().trimEnd('/')
}

internal fun updateDownloadSources(customBaseUrl: String): List<String> =
    listOfNotNull(
        normalizeUpdateDownloadBaseUrl(customBaseUrl),
        DEFAULT_UPDATE_DOWNLOAD_BASE_URL,
    ).distinct()

internal fun interface ReleaseSource {
    suspend fun fetchLatest(): UpdateInfo
}

/** Each source is independent; a working mirror never needs a successful GitHub request. */
internal class UpdateReleaseSource(client: OkHttpClient, customBaseUrl: String) : ReleaseSource {
    private val client = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()
    private val baseUrls = updateDownloadSources(customBaseUrl)
    private val sources: List<ReleaseSource> = baseUrls.mapIndexed { index, baseUrl ->
        ManifestReleaseSource(this.client, baseUrl, baseUrls.drop(index + 1))
    } + GitHubReleaseSource(this.client)

    override suspend fun fetchLatest(): UpdateInfo = fetchFirstRelease(sources)
}

internal suspend fun fetchFirstRelease(sources: List<ReleaseSource>): UpdateInfo {
    val failure = IOException("All update sources failed")
    for (source in sources) {
        try {
            return source.fetchLatest()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure.addSuppressed(error)
        }
    }
    throw failure
}

internal class ManifestReleaseSource(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val fallbackBaseUrls: List<String>,
) : ReleaseSource {
    override suspend fun fetchLatest(): UpdateInfo {
        val request = Request.Builder()
            .url("$baseUrl/latest.json")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Miffan-Android/${BuildConfig.VERSION_NAME}")
            .build()
        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Update manifest request failed with HTTP ${response.code}")
            }
            json.decodeFromString<UpdateManifest>(response.body.string())
                .toUpdateInfo(baseUrl, fallbackBaseUrls)
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
internal data class UpdateManifest(
    val version: String,
    val publishedAt: String,
    val architecture: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val downloadUrl: String,
    val checksumUrl: String,
    val releaseUrl: String,
    val changelog: String = "",
) {
    fun toUpdateInfo(baseUrl: String, fallbackBaseUrls: List<String>): UpdateInfo {
        require(version.isFormalMiffanReleaseVersion()) { "Not a formal Miffan release" }
        Instant.parse(publishedAt)
        require(architecture == "arm64-v8a" && fileName == apkAssetName(version)) {
            "Unexpected update APK"
        }
        require(size > 0 && sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "Invalid APK metadata"
        }
        val path = "/releases/$version/$fileName"
        // A mirror may serve an unchanged copy of the official manifest. Rebase its APK
        // path onto the selected source instead of silently downloading from the original.
        require(downloadUrl in listOf("$baseUrl$path", "$DEFAULT_UPDATE_DOWNLOAD_BASE_URL$path")) {
            "APK URL does not match the selected source"
        }
        require(checksumUrl == "$downloadUrl.sha256" && releaseUrl == releasePageUrl(version)) {
            "Unexpected checksum or release URL"
        }
        return UpdateInfo(
            version = version,
            publishedAt = publishedAt,
            changelog = changelog,
            downloads = listOf(
                UpdateDownload(
                    name = fileName,
                    url = "$baseUrl$path",
                    sizeBytes = size,
                    fallbackUrls = fallbackBaseUrls.map { "$it$path" } + releaseDownloadUrl(version),
                )
            ),
            releaseUrl = releasePageUrl(version),
        )
    }
}
