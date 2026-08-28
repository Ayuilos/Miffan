package me.ayuilos.miffan.utils

import android.content.Context
import android.util.Xml
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.BuildConfig
import me.ayuilos.miffan.data.datastore.Settings
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader

private const val GITHUB_RELEASE_API_URL =
    "https://api.github.com/repos/Ayuilos/Miffan/releases/latest"
private const val GITHUB_RELEASE_ATOM_URL =
    "https://github.com/Ayuilos/Miffan/releases.atom"
private const val GITHUB_RELEASES_URL =
    "https://github.com/Ayuilos/Miffan/releases"
private const val DEBUG_UPDATE_VERSION = "999.0.0-debug-preview"
private const val DEBUG_UPDATE_PUBLISHED_AT = "2026-08-26T00:00:00Z"
private const val SEMVER_NUMERIC_IDENTIFIER = "(?:0|[1-9]\\d*)"
private const val SEMVER_NON_NUMERIC_IDENTIFIER = "(?:\\d*[A-Za-z-][0-9A-Za-z-]*)"
private const val SEMVER_PRERELEASE_IDENTIFIER =
    "(?:$SEMVER_NUMERIC_IDENTIFIER|$SEMVER_NON_NUMERIC_IDENTIFIER)"
private const val SEMVER_BUILD_IDENTIFIER = "[0-9A-Za-z-]+"
private val LEGACY_MIFFAN_RELEASE_VERSION = Regex(
    "$SEMVER_NUMERIC_IDENTIFIER\\.$SEMVER_NUMERIC_IDENTIFIER\\." +
        "$SEMVER_NUMERIC_IDENTIFIER-miffan\\.$SEMVER_NUMERIC_IDENTIFIER"
)
private val STANDARD_SEMVER = Regex(
    "($SEMVER_NUMERIC_IDENTIFIER)\\.($SEMVER_NUMERIC_IDENTIFIER)\\." +
        "($SEMVER_NUMERIC_IDENTIFIER)" +
        "(?:-($SEMVER_PRERELEASE_IDENTIFIER(?:\\.$SEMVER_PRERELEASE_IDENTIFIER)*))?" +
        "(?:\\+($SEMVER_BUILD_IDENTIFIER(?:\\.$SEMVER_BUILD_IDENTIFIER)*))?"
)

class UpdateChecker(
    client: OkHttpClient,
    private val appScope: CoroutineScope,
    settings: Flow<Settings>,
) {
    private val client = client
    private val _debugUpdateOverrideEnabled = MutableStateFlow(false)
    val debugUpdateOverrideEnabled: StateFlow<Boolean> =
        _debugUpdateOverrideEnabled.asStateFlow()

    val updateState: StateFlow<UiState<UpdateInfo>> =
        combine(
            settings.map {
                UpdateCheckConfig(
                    initialized = !it.init,
                    baseUrl = normalizeUpdateDownloadBaseUrl(it.networkSetting.updateDownloadBaseUrl)
                        ?: DEFAULT_UPDATE_DOWNLOAD_BASE_URL,
                    disabledUntil = it.displaySetting.updateCheckDisabledUntilEpochMillis,
                )
            }.distinctUntilChanged(),
            debugUpdateOverrideEnabled,
        ) { config, debugOverrideEnabled ->
            config to (BuildConfig.DEBUG && debugOverrideEnabled)
        }.flatMapLatest { (config, debugOverrideEnabled) ->
            flow {
                emit(UiState.Loading)
                if (!config.initialized) return@flow

                val now = System.currentTimeMillis()
                if (!debugOverrideEnabled && config.disabledUntil > now) {
                    delay(config.disabledUntil - now)
                }
                emitAll(checkUpdate(config.baseUrl).map { state ->
                    state.withDebugUpdateOverride(enabled = debugOverrideEnabled)
                })
            }
        }.stateIn(
            scope = appScope,
            started = SharingStarted.Lazily,
            initialValue = UiState.Loading,
        )

    fun setDebugUpdateOverrideEnabled(enabled: Boolean) {
        if (BuildConfig.DEBUG) {
            _debugUpdateOverrideEnabled.value = enabled
        }
    }

    private fun checkUpdate(baseUrl: String): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        emit(
            UiState.Success(
                data = try {
                    UpdateReleaseSource(client, baseUrl).fetchLatest()
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw Exception("Failed to fetch update info", e)
                }
            )
        )
    }.catch {
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        val appContext = context.applicationContext
        appScope.launch(Dispatchers.IO) {
            runCatching { UpdateDownloader.enqueue(appContext, download) }.onFailure {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "无法启动下载，请使用 GitHub 备用下载", Toast.LENGTH_SHORT).show()
                    context.openUrl(download.fallbackUrls.lastOrNull() ?: download.url)
                }
            }
        }
    }
}

private data class UpdateCheckConfig(
    val initialized: Boolean,
    val baseUrl: String,
    val disabledUntil: Long,
)

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val sizeBytes: Long?,
    val fallbackUrls: List<String> = emptyList(),
)

data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>,
    val releaseUrl: String,
)

fun UiState<UpdateInfo>.availableUpdate(
    currentVersion: String = BuildConfig.VERSION_NAME,
): UpdateInfo? = (this as? UiState.Success)?.data?.takeIf { info ->
    Version(info.version) > Version(currentVersion)
}

internal fun UiState<UpdateInfo>.withDebugUpdateOverride(
    enabled: Boolean,
): UiState<UpdateInfo> {
    if (!enabled) return this
    val releaseInfo = (this as? UiState.Success)?.data
    return UiState.Success(
        UpdateInfo(
            version = DEBUG_UPDATE_VERSION,
            publishedAt = releaseInfo?.publishedAt ?: DEBUG_UPDATE_PUBLISHED_AT,
            changelog = """
                ## Debug update preview

                This local preview exercises the update badge, settings banner, detail sheet,
                and Miffan semantic state. No fake APK download is provided.
            """.trimIndent(),
            downloads = emptyList(),
            releaseUrl = releaseInfo?.releaseUrl ?: GITHUB_RELEASES_URL,
        )
    )
}

internal class GitHubReleaseSource(
    private val client: OkHttpClient,
    private val apiUrl: String = GITHUB_RELEASE_API_URL,
    private val atomUrl: String = GITHUB_RELEASE_ATOM_URL,
) : ReleaseSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchLatest(): UpdateInfo {
        try {
            return fetchFromApi()
        } catch (apiError: Exception) {
            currentCoroutineContext().ensureActive()
            try {
                return fetchFromAtom()
            } catch (atomError: Exception) {
                currentCoroutineContext().ensureActive()
                apiError.addSuppressed(atomError)
                throw apiError
            }
        }
    }

    private suspend fun fetchFromApi(): UpdateInfo {
        val request = Request.Builder()
            .url(apiUrl)
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header(
                "User-Agent",
                "Miffan-Android/${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}",
            )
            .build()
        return json.decodeFromString<GitHubRelease>(requestBody(request)).toUpdateInfo()
    }

    private suspend fun fetchFromAtom(): UpdateInfo {
        val request = Request.Builder()
            .url(atomUrl)
            .get()
            .header("Accept", "application/atom+xml")
            .header("User-Agent", "Miffan-Android/${BuildConfig.VERSION_NAME}")
            .build()
        return parseLatestMiffanReleaseAtom(requestBody(request))
    }

    private suspend fun requestBody(request: Request): String =
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub request failed with HTTP ${response.code}")
            }
            response.body.string()
        }
}

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String,
    @SerialName("published_at")
    val publishedAt: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
internal data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    val size: Long,
)

internal fun GitHubRelease.toUpdateInfo(): UpdateInfo {
    require(!draft && !prerelease) { "The latest GitHub release is not a formal release" }
    require(tagName.isFormalMiffanReleaseVersion()) {
        "The latest GitHub release tag is not a formal Miffan version: $tagName"
    }
    val version = tagName.toMiffanReleaseVersion()
    val expectedAssetName = apkAssetName(version)
    val downloads = assets
        .filter { asset ->
            asset.name == expectedAssetName &&
                asset.browserDownloadUrl.startsWith("$GITHUB_RELEASES_URL/download/$version/")
        }
        .map { asset ->
            UpdateDownload(
                name = asset.name,
                url = asset.browserDownloadUrl,
                sizeBytes = asset.size,
            )
        }
    return UpdateInfo(
        version = version,
        publishedAt = publishedAt ?: createdAt
            ?: throw IllegalArgumentException("GitHub release has no publication time"),
        changelog = body.orEmpty(),
        downloads = downloads,
        releaseUrl = releasePageUrl(version),
    )
}

internal fun parseLatestMiffanReleaseAtom(
    xml: String,
    parser: XmlPullParser = Xml.newPullParser(),
): UpdateInfo {
    parser.setInput(StringReader(xml))
    var inEntry = false
    var currentTextTag: String? = null
    val text = StringBuilder()
    var releaseLink: String? = null
    var publishedAt: String? = null
    var changelog: String? = null

    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "entry" -> {
                    inEntry = true
                    releaseLink = null
                    publishedAt = null
                    changelog = null
                }

                "link" -> if (inEntry && parser.getAttributeValue(null, "rel") == "alternate") {
                    releaseLink = parser.getAttributeValue(null, "href")
                }

                "updated", "content" -> if (inEntry) {
                    currentTextTag = parser.name
                    text.clear()
                }
            }

            XmlPullParser.TEXT -> if (currentTextTag != null) {
                text.append(parser.text)
            }

            XmlPullParser.END_TAG -> {
                when {
                    parser.name == currentTextTag -> {
                        when (currentTextTag) {
                            "updated" -> publishedAt = text.toString().trim()
                            "content" -> changelog = text.toString().trim()
                        }
                        currentTextTag = null
                    }

                    parser.name == "entry" -> {
                        val tag = releaseLink
                            ?.substringAfter(
                                "$GITHUB_RELEASES_URL/tag/",
                                missingDelimiterValue = "",
                            )
                            ?.substringBefore('?')
                            ?.takeIf { it.isFormalMiffanReleaseVersion() }
                        if (tag != null) {
                            return UpdateInfo(
                                version = tag,
                                publishedAt = publishedAt
                                    ?: throw IllegalArgumentException(
                                        "GitHub release feed entry has no publication time"
                                    ),
                                changelog = changelog.orEmpty(),
                                downloads = listOf(
                                    UpdateDownload(
                                        name = apkAssetName(tag),
                                        url = releaseDownloadUrl(tag),
                                        sizeBytes = null,
                                    )
                                ),
                                releaseUrl = releasePageUrl(tag),
                            )
                        }
                        inEntry = false
                    }
                }
            }
        }
        parser.next()
    }
    throw IllegalArgumentException("GitHub release feed has no formal Miffan release")
}

private fun String.toMiffanReleaseVersion(): String {
    require(isSupportedMiffanReleaseVersion()) {
        "Unexpected Miffan release tag: $this"
    }
    return this
}

internal fun String.isSupportedMiffanReleaseVersion(): Boolean =
    LEGACY_MIFFAN_RELEASE_VERSION.matches(this) || STANDARD_SEMVER.matches(this)

internal fun String.isFormalMiffanReleaseVersion(): Boolean {
    if (LEGACY_MIFFAN_RELEASE_VERSION.matches(this)) return true
    val match = STANDARD_SEMVER.matchEntire(this) ?: return false
    return match.groupValues[4].isEmpty()
}

internal fun apkAssetName(version: String): String =
    "Miffan-$version-arm64-v8a.apk"

internal fun releasePageUrl(version: String): String =
    "$GITHUB_RELEASES_URL/tag/$version"

internal fun releaseDownloadUrl(version: String): String =
    "$GITHUB_RELEASES_URL/download/$version/${apkAssetName(version)}"

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
