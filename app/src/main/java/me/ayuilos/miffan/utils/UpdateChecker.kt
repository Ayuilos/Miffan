package me.ayuilos.miffan.utils

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.BuildConfig
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.UpdateReminder
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

/** A successful check is repeated after this long, when the app comes to the foreground or from the daily job. */
const val UPDATE_RECHECK_INTERVAL_MILLIS = 6 * 60 * 60 * 1000L

/** A failed check is retried sooner. */
const val UPDATE_RETRY_INTERVAL_MILLIS = 15 * 60 * 1000L

/** 「稍后提醒」 hides one version's card and badge for this long. */
const val UPDATE_SNOOZE_MILLIS = 3 * 24 * 60 * 60 * 1000L

/**
 * Device-local update bookkeeping. It lives outside [Settings] on purpose: a backup restored on
 * another phone must not carry "already notified" or "skipped" marks.
 */
@Serializable
data class UpdateLocalState(
    val cached: UpdateInfo? = null,
    val lastCheckedAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val lastFailed: Boolean = false,
    val notifiedVersion: String = "",
    val seenVersion: String = "",
    val snoozedVersion: String = "",
    val snoozedUntil: Long = 0L,
    val skippedVersion: String = "",
    val autoDownloadedVersion: String = "",
    val lastRunVersionCode: Int = 0,
    val whatsNewVersion: String = "",
)

interface UpdateLocalStore {
    fun read(): UpdateLocalState
    fun write(state: UpdateLocalState)
}

class SharedPreferencesUpdateLocalStore(context: Context) : UpdateLocalStore {
    private val prefs = context.applicationContext.getSharedPreferences("app_update_state", Context.MODE_PRIVATE)

    override fun read(): UpdateLocalState = prefs.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString<UpdateLocalState>(it) }.getOrNull() }
        ?: UpdateLocalState()

    override fun write(state: UpdateLocalState) {
        prefs.edit().putString(KEY, json.encodeToString(UpdateLocalState.serializer(), state)).apply()
    }

    private companion object {
        const val KEY = "state"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** The Android facts the checker depends on; JVM tests supply a fake. */
interface UpdateEnvironment {
    val currentVersion: String
    val currentVersionCode: Int
    val isPlayStore: Boolean

    /** Whether this install replaced an older one; only consulted before a version was ever recorded. */
    val isUpgradeInstall: Boolean
    fun isAppInForeground(): Boolean
    fun isUnmetered(): Boolean
    fun notifyNewVersion(info: UpdateInfo)
    fun cancelNewVersionNotification()
}

/** Everything a screen needs to present updates, derived from one place. */
data class UpdatePresentation(
    /** A newer release the user has not skipped. */
    val available: UpdateInfo? = null,
    /** The skipped version while it is still newer than the installed one. */
    val skippedVersion: String? = null,
    val download: UpdateDownloadState = UpdateDownloadState.Idle,
    val remindersOn: Boolean = true,
    val remindersPausedUntil: Long = 0L,
    val snoozed: Boolean = false,
    val seen: Boolean = false,
    val checking: Boolean = false,
    val lastCheckFailed: Boolean = false,
    val lastCheckedAt: Long = 0L,
    /** Notes of the version just installed, until the user dismisses them. */
    val whatsNew: UpdateInfo? = null,
    val isPlayStore: Boolean = false,
) {
    val downloadInProgress: Boolean
        get() = download is UpdateDownloadState.Downloading || download is UpdateDownloadState.Verifying

    /** The 「我」 page card: always while a download is under way, otherwise only when reminders are on. */
    val showCard: Boolean
        get() = available != null && (download !is UpdateDownloadState.Idle || (remindersOn && !snoozed))

    /** The red dot: a new version nobody has looked at yet, or an APK waiting to be installed. */
    val showBadge: Boolean
        get() = available != null &&
            (download is UpdateDownloadState.Ready || (remindersOn && !snoozed && !seen))
}

class UpdateChecker(
    client: OkHttpClient,
    private val appScope: CoroutineScope,
    settings: Flow<Settings>,
    private val downloader: UpdateApkDownloader,
    private val store: UpdateLocalStore,
    private val environment: UpdateEnvironment,
    private val clock: () -> Long = System::currentTimeMillis,
    fetch: (suspend (baseUrl: String) -> UpdateInfo)? = null,
) {
    private val fetch: suspend (String) -> UpdateInfo =
        fetch ?: { baseUrl -> UpdateReleaseSource(client, baseUrl).fetchLatest() }
    private val settingsState: StateFlow<Settings> =
        settings.stateIn(appScope, SharingStarted.Eagerly, Settings.dummy())
    private val local = MutableStateFlow(store.read())
    private val checking = MutableStateFlow(false)
    private val checkMutex = Mutex()
    private val localLock = Any()
    private val _debugUpdateOverrideEnabled = MutableStateFlow(false)
    val debugUpdateOverrideEnabled: StateFlow<Boolean> = _debugUpdateOverrideEnabled.asStateFlow()

    val downloadState: StateFlow<UpdateDownloadState> = downloader.downloadState

    val presentation: StateFlow<UpdatePresentation> = combine(
        local, checking, settingsState, downloader.downloadState, _debugUpdateOverrideEnabled,
    ) { state, isChecking, current, download, debug ->
        present(state, isChecking, current, download, debug)
    }.stateIn(
        appScope, SharingStarted.Eagerly,
        present(local.value, false, settingsState.value, downloader.downloadState.value, false),
    )

    init {
        recordLaunch()
        appScope.launch {
            settingsState.filter { !it.init }.map { it.updateBaseUrl() }.distinctUntilChanged()
                // The first value is startup; a later one is a new download source, checked right away.
                .collectIndexed { index, _ -> checkIfDueNow(force = index > 0) }
        }
        appScope.launch {
            // An APK finished in an earlier process is shown as ready again instead of "download".
            presentation.map { it.available?.downloads?.firstOrNull() }.distinctUntilChanged().filterNotNull()
                .collect { downloader.restoreReady(it) }
        }
    }

    fun setDebugUpdateOverrideEnabled(enabled: Boolean) {
        if (BuildConfig.DEBUG) _debugUpdateOverrideEnabled.value = enabled
    }

    /** Foreground and daily checks: skipped while reminders are paused or the last check is recent. */
    fun checkIfDue(force: Boolean = false): Job = appScope.launch { checkIfDueNow(force) }

    suspend fun checkIfDueNow(force: Boolean = false): Boolean {
        val current = settingsState.value
        if (current.init || environment.isPlayStore) return false
        if (current.displaySetting.updateCheckDisabledUntilEpochMillis > clock()) return false
        // Decided again under the lock, so a check that just finished elsewhere is not repeated.
        return check { state -> force || isDue(state) }
    }

    private fun isDue(state: UpdateLocalState): Boolean {
        val interval = if (state.lastFailed) UPDATE_RETRY_INTERVAL_MILLIS else UPDATE_RECHECK_INTERVAL_MILLIS
        return clock() - state.lastAttemptAt >= interval
    }

    /** The About page's button: always asks the server, even while reminders are paused. */
    fun checkNow(): Job = appScope.launch { check { true } }

    /** Checks run one at a time; [shouldRun] sees the state left by the previous one. */
    private suspend fun check(shouldRun: (UpdateLocalState) -> Boolean): Boolean {
        return checkMutex.withLock {
            if (!shouldRun(local.value)) return@withLock !local.value.lastFailed
            checking.value = true
            try {
                val startedAt = clock()
                val info = try {
                    withContext(Dispatchers.IO) { fetch(settingsState.value.updateBaseUrl()) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    updateLocal { it.copy(lastAttemptAt = startedAt, lastFailed = true) }
                    return@withLock false
                }
                updateLocal {
                    it.copy(cached = info, lastAttemptAt = startedAt, lastCheckedAt = startedAt, lastFailed = false)
                }
                afterCheck()
                true
            } finally {
                checking.value = false
            }
        }
    }

    private fun afterCheck() {
        val shown = present(local.value, false, settingsState.value, downloader.downloadState.value, false)
        val info = shown.available ?: return
        if (!shown.remindersOn) return
        val display = settingsState.value.displaySetting
        val download = info.downloads.firstOrNull()
        val current = shown.download
        val canAutoDownload = download != null && local.value.autoDownloadedVersion != info.version &&
            (current is UpdateDownloadState.Idle ||
                (current is UpdateDownloadState.Failed && current.error == UpdateDownloadError.Network))
        if (display.updateAutoDownloadOnWifi && canAutoDownload && environment.isUnmetered()) {
            updateLocal { it.copy(autoDownloadedVersion = info.version) }
            // The "ready to install" notification replaces the "new version" one.
            downloader.downloadUpdate(download, quiet = true)
            return
        }
        if (display.updateReminder == UpdateReminder.BADGE_AND_NOTIFY && !shown.snoozed && !shown.seen &&
            local.value.notifiedVersion != info.version && !environment.isAppInForeground()
        ) {
            environment.notifyNewVersion(info)
            updateLocal { it.copy(notifiedVersion = info.version) }
        }
    }

    /** The user opened the update page; the red dot for this version goes away. */
    fun markSeen(version: String) {
        updateLocal { if (it.seenVersion == version) it else it.copy(seenVersion = version) }
        environment.cancelNewVersionNotification()
    }

    fun snooze(version: String) {
        updateLocal { it.copy(snoozedVersion = version, snoozedUntil = clock() + UPDATE_SNOOZE_MILLIS) }
        environment.cancelNewVersionNotification()
    }

    fun skip(version: String) {
        updateLocal { it.copy(skippedVersion = version) }
        environment.cancelNewVersionNotification()
        val active = downloader.downloadState.value.activeDownload
        if (active != null && active.name == apkAssetName(version)) downloader.cancelUpdateDownload()
    }

    fun unskip() = updateLocal { it.copy(skippedVersion = "") }

    fun dismissWhatsNew() = updateLocal { it.copy(whatsNewVersion = "") }

    fun downloadUpdate(download: UpdateDownload) = downloader.downloadUpdate(download)

    fun cancelUpdateDownload() = downloader.cancelUpdateDownload()

    /** Launches the system installer for a Ready APK. Returns false when nothing is Ready. */
    fun installUpdate(context: Context): Boolean = downloader.installUpdate(context)

    private fun recordLaunch() {
        val code = environment.currentVersionCode
        val previous = local.value.lastRunVersionCode
        if (previous == code) return
        // The APK that installed this version (or an older one) is no longer needed.
        downloader.discardFiles()
        val upgraded = if (previous == 0) environment.isUpgradeInstall else previous < code
        updateLocal {
            it.copy(
                lastRunVersionCode = code,
                whatsNewVersion = if (upgraded) environment.currentVersion else "",
            )
        }
    }

    private fun updateLocal(transform: (UpdateLocalState) -> UpdateLocalState) = synchronized(localLock) {
        val next = transform(local.value)
        if (next != local.value) {
            local.value = next
            store.write(next)
        }
    }

    private fun present(
        state: UpdateLocalState,
        isChecking: Boolean,
        current: Settings,
        download: UpdateDownloadState,
        debug: Boolean,
    ): UpdatePresentation {
        val now = clock()
        val display = current.displaySetting
        val latest = if (BuildConfig.DEBUG && debug) debugUpdateInfo(state.cached) else state.cached
        val newer = latest?.takeIf { Version(it.version) > Version(environment.currentVersion) }
            ?.takeIf { !environment.isPlayStore }
        val available = newer?.takeIf { it.version != state.skippedVersion }
        val pausedUntil = display.updateCheckDisabledUntilEpochMillis
        return UpdatePresentation(
            available = available,
            skippedVersion = state.skippedVersion.takeIf {
                it.isNotEmpty() && Version(it) > Version(environment.currentVersion)
            },
            download = download.takeIf { d ->
                val active = d.activeDownload
                active != null && available?.downloads?.any { it.name == active.name } == true
            } ?: UpdateDownloadState.Idle,
            remindersOn = pausedUntil <= now,
            remindersPausedUntil = pausedUntil,
            snoozed = available != null && state.snoozedVersion == available.version && state.snoozedUntil > now,
            seen = available != null && state.seenVersion == available.version,
            checking = isChecking,
            lastCheckFailed = state.lastFailed,
            lastCheckedAt = state.lastCheckedAt,
            whatsNew = state.cached?.takeIf {
                display.updateShowWhatsNew && state.whatsNewVersion.isNotEmpty() &&
                    it.version == state.whatsNewVersion && it.version == environment.currentVersion
            },
            isPlayStore = environment.isPlayStore,
        )
    }
}

private fun Settings.updateBaseUrl(): String =
    normalizeUpdateDownloadBaseUrl(networkSetting.updateDownloadBaseUrl) ?: DEFAULT_UPDATE_DOWNLOAD_BASE_URL

val UpdateDownloadState.activeDownload: UpdateDownload?
    get() = when (this) {
        is UpdateDownloadState.Downloading -> download
        is UpdateDownloadState.Verifying -> download
        is UpdateDownloadState.Ready -> download
        is UpdateDownloadState.Failed -> download
        UpdateDownloadState.Idle -> null
    }

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val sizeBytes: Long?,
    val fallbackUrls: List<String> = emptyList(),
    val sha256: String? = null,
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>,
    val releaseUrl: String,
)

private fun debugUpdateInfo(releaseInfo: UpdateInfo?): UpdateInfo = UpdateInfo(
    version = DEBUG_UPDATE_VERSION,
    publishedAt = releaseInfo?.publishedAt ?: DEBUG_UPDATE_PUBLISHED_AT,
    changelog = """
        更新内容：

        ### Miffan 自有改动

        - **调试用的更新预览**：用来检查红点、「我」页面卡片、更新页和米饭的状态。
        - 没有真正的安装包可以下载。

        Updates:

        ### Miffan changes

        - **Debug update preview**: exercises the badge, the Me card, the update page and Miffan's state.
        - There is no real APK to download.
    """.trimIndent(),
    downloads = emptyList(),
    releaseUrl = releaseInfo?.releaseUrl ?: GITHUB_RELEASES_URL,
)

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
    val digest: String? = null,
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
                sha256 = asset.digest?.let { digest ->
                    Regex("sha256:([a-fA-F0-9]{64})", RegexOption.IGNORE_CASE)
                        .matchEntire(digest)?.groupValues?.get(1)
                },
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
    var title: String? = null

    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "entry" -> {
                    inEntry = true
                    releaseLink = null
                    publishedAt = null
                    changelog = null
                    title = null
                }

                "link" -> if (inEntry && parser.getAttributeValue(null, "rel") == "alternate") {
                    releaseLink = parser.getAttributeValue(null, "href")
                }

                "title", "updated", "content" -> if (inEntry) {
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
                            "title" -> title = text.toString().trim()
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
                            // The feed also lists bare tags, titled "<tag>: <commit subject>" and
                            // without an APK. A published release is titled exactly its tag.
                            ?.takeIf { it == title }
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
