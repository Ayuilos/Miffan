package me.rerere.rikkahub.data.skills.source

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.skills.install.RemoteSkillEntryKind
import me.rerere.rikkahub.data.skills.install.RemoteSkillFile
import me.rerere.rikkahub.data.skills.install.RemoteSkillPackage
import me.rerere.rikkahub.data.skills.install.RemoteSkillSourceClient
import me.rerere.rikkahub.data.skills.install.SkillInstallErrorCode
import me.rerere.rikkahub.data.skills.install.SkillInstallException
import me.rerere.rikkahub.data.skills.install.SkillInstallService
import me.rerere.rikkahub.data.skills.install.VerifiedSkillSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads one public GitHub-backed skill selected through a canonical skills.sh detail URL.
 *
 * The moving default branch is resolved once to an immutable commit. Every tree and raw-content
 * request is then pinned to that commit. Redirects are disabled and each request is constructed
 * from locally validated path segments; caller-controlled URLs are never used as fetch targets.
 */
class GitHubRemoteSkillSourceClient(
    httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RemoteSkillSourceClient {
    private val client = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(GITHUB_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(GITHUB_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(GITHUB_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(GITHUB_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(sourceUrl: String): RemoteSkillPackage {
        return withTimeoutOrNull(PREVIEW_TOTAL_TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) {
                fetchPinnedPackage(sourceUrl)
            }
        } ?: throw SkillInstallException(
            SkillInstallErrorCode.DOWNLOAD_FAILED,
            "GitHub Skill preview timed out",
        )
    }

    private suspend fun fetchPinnedPackage(sourceUrl: String): RemoteSkillPackage {
        val source = parseCanonicalSkillShUrl(sourceUrl)
        val repository = getJson<GitHubRepository>(
            githubApiUrl("repos", source.owner, source.repo),
            MAX_SMALL_JSON_BYTES,
        )
        val defaultBranch = repository.defaultBranch
        if (defaultBranch.isBlank() || defaultBranch.length > MAX_REF_LENGTH ||
            defaultBranch.any(Char::isISOControl)
        ) {
            throw invalidSource("GitHub repository has an invalid default branch")
        }

        val commit = getJson<GitHubCommit>(
            githubApiUrl("repos", source.owner, source.repo, "commits", defaultBranch),
            MAX_SMALL_JSON_BYTES,
        )
        val revision = commit.sha.lowercase(Locale.ROOT)
        val rootTreeSha = commit.commit.tree.sha.lowercase(Locale.ROOT)
        if (!GIT_SHA.matches(revision) || !GIT_SHA.matches(rootTreeSha)) {
            throw invalidSource("GitHub returned an invalid commit revision")
        }

        val treeUrl = githubApiUrl("repos", source.owner, source.repo, "git", "trees", rootTreeSha)
            .newBuilder()
            .addQueryParameter("recursive", "1")
            .build()
        val tree = getJson<GitHubTree>(treeUrl, MAX_TREE_JSON_BYTES)
        if (tree.truncated) {
            throw SkillInstallException(
                SkillInstallErrorCode.DOWNLOAD_FAILED,
                "GitHub repository tree is truncated; refusing a partial skill download",
            )
        }

        val entries = tree.tree
        val candidateCache = HashMap<String, ByteArray>()
        val skillMdEntry = selectUniqueSkillEntry(source, revision, entries, candidateCache)
        val skillRoot = skillMdEntry.path.substringBeforeLast('/', missingDelimiterValue = "")
        val packageEntries = selectPackageEntries(skillRoot, skillMdEntry, entries)
        val files = ArrayList<RemoteSkillFile>(packageEntries.size)
        var totalBytes = 0L

        for (entry in packageEntries.sortedBy { it.relativePath }) {
            val limit = if (entry.relativePath == SKILL_FILE_NAME) {
                SkillInstallService.MAX_SKILL_FILE_BYTES
            } else {
                SkillInstallService.MAX_FILE_BYTES
            }
            val bytes = candidateCache[entry.repositoryPath]
                ?: downloadRawFile(source, revision, entry.repositoryPath, limit)
            requireStrictUtf8(bytes, entry.relativePath)
            rejectGitLfsPointer(bytes, entry.relativePath)
            totalBytes += bytes.size
            if (totalBytes > SkillInstallService.MAX_TOTAL_BYTES) {
                throw SkillInstallException(
                    SkillInstallErrorCode.PACKAGE_TOO_LARGE,
                    "Remote skill exceeds the ${SkillInstallService.MAX_TOTAL_BYTES} byte limit",
                )
            }
            files += RemoteSkillFile(
                relativePath = entry.relativePath,
                content = bytes,
                kind = RemoteSkillEntryKind.REGULAR_FILE,
            )
        }

        val canonicalUrl = buildGitHubRevisionUrl(source, revision, skillRoot)
        return RemoteSkillPackage(
            source = VerifiedSkillSource(
                requestedUrl = sourceUrl,
                canonicalUrl = canonicalUrl,
                provider = PROVIDER_ID,
                revision = revision,
            ),
            files = files,
        )
    }

    private suspend fun selectUniqueSkillEntry(
        source: SkillShSource,
        revision: String,
        entries: List<GitHubTreeEntry>,
        candidateCache: MutableMap<String, ByteArray>,
    ): GitHubTreeEntry {
        val candidates = entries.filter { entry ->
            entry.type == "blob" &&
                entry.mode in REGULAR_FILE_MODES &&
                (entry.path == SKILL_FILE_NAME || entry.path.endsWith("/$SKILL_FILE_NAME"))
        }
        if (candidates.isEmpty()) {
            throw SkillInstallException(
                SkillInstallErrorCode.MISSING_SKILL_FILE,
                "GitHub repository does not contain a SKILL.md",
            )
        }

        val preferred = candidates.filter { entry ->
            val parentName = entry.path.substringBeforeLast('/', missingDelimiterValue = "")
                .substringAfterLast('/', missingDelimiterValue = "")
            parentName.isNotEmpty() && toSkillSlug(parentName) == source.slug
        }
        val firstPass = preferred.ifEmpty {
            if (candidates.size > MAX_SKILL_CANDIDATES) {
                throw SkillInstallException(
                    SkillInstallErrorCode.TOO_MANY_FILES,
                    "Repository has too many SKILL.md candidates to resolve safely",
                )
            }
            candidates
        }
        if (firstPass.size > MAX_SKILL_CANDIDATES) {
            throw SkillInstallException(
                SkillInstallErrorCode.TOO_MANY_FILES,
                "Repository has too many matching SKILL.md candidates",
            )
        }

        var matches = firstPass.mapNotNull { entry ->
            validateRepositoryPath(entry.path)
            val content = downloadRawFile(
                source = source,
                revision = revision,
                repositoryPath = entry.path,
                maxBytes = SkillInstallService.MAX_SKILL_FILE_BYTES,
            )
            candidateCache[entry.path] = content
            val skillText = requireStrictUtf8(content, entry.path)
            rejectGitLfsPointer(content, entry.path)
            val name = SkillFrontmatterParser.parse(skillText)["name"]?.trim().orEmpty()
            entry.takeIf { toSkillSlug(name) == source.slug }
        }

        if (matches.isEmpty() && preferred.isNotEmpty() && candidates.size <= MAX_SKILL_CANDIDATES) {
            val preferredPaths = preferred.mapTo(HashSet()) { it.path }
            matches = candidates
                .filterNot { it.path in preferredPaths }
                .mapNotNull { entry ->
                    validateRepositoryPath(entry.path)
                    val content = downloadRawFile(
                        source = source,
                        revision = revision,
                        repositoryPath = entry.path,
                        maxBytes = SkillInstallService.MAX_SKILL_FILE_BYTES,
                    )
                    candidateCache[entry.path] = content
                    val skillText = requireStrictUtf8(content, entry.path)
                    rejectGitLfsPointer(content, entry.path)
                    val name = SkillFrontmatterParser.parse(skillText)["name"]?.trim().orEmpty()
                    entry.takeIf { toSkillSlug(name) == source.slug }
                }
                .toList()
        }

        return matches.singleOrNull() ?: throw invalidSource(
            if (matches.isEmpty()) {
                "No unique SKILL.md matches '${source.slug}'"
            } else {
                "Multiple SKILL.md files match '${source.slug}'"
            },
        )
    }

    private fun selectPackageEntries(
        skillRoot: String,
        skillMdEntry: GitHubTreeEntry,
        entries: List<GitHubTreeEntry>,
    ): List<SelectedFile> {
        // A root SKILL.md must not make the entire repository part of the skill package. If the
        // repository contains any other entry, we cannot distinguish Skill support files from
        // repository metadata without silently omitting or over-including content, so fail closed.
        if (skillRoot.isEmpty()) {
            if (entries.any { it.path != SKILL_FILE_NAME }) {
                throw SkillInstallException(
                    SkillInstallErrorCode.INVALID_SOURCE,
                    "A root-level SKILL.md can only be installed from a single-file repository",
                )
            }
            return listOf(SelectedFile(skillMdEntry.path, SKILL_FILE_NAME))
        }

        val prefix = "$skillRoot/"
        val selected = entries.filter { it.path.startsWith(prefix) }
        selected.forEach { entry ->
            validateRepositoryPath(entry.path)
            when {
                entry.mode == SYMBOLIC_LINK_MODE -> throw unsupportedEntry("symbolic link", entry.path)
                entry.mode == SUBMODULE_MODE || entry.type == "commit" -> {
                    throw unsupportedEntry("submodule", entry.path)
                }
                entry.type == "tree" && entry.mode == DIRECTORY_MODE -> Unit
                entry.type == "blob" && entry.mode in REGULAR_FILE_MODES -> Unit
                else -> throw unsupportedEntry("Git entry", entry.path)
            }
        }

        val files = selected.asSequence()
            .filter { it.type == "blob" }
            .map { entry ->
                val relativePath = entry.path.removePrefix(prefix)
                validateRelativePath(relativePath)
                if (entry.size != null && entry.size < 0) {
                    throw unsafePath(entry.path)
                }
                val limit = if (relativePath == SKILL_FILE_NAME) {
                    SkillInstallService.MAX_SKILL_FILE_BYTES
                } else {
                    SkillInstallService.MAX_FILE_BYTES
                }
                if (entry.size != null && entry.size > limit) {
                    throw SkillInstallException(
                        SkillInstallErrorCode.FILE_TOO_LARGE,
                        "Remote file '$relativePath' exceeds the $limit byte limit",
                    )
                }
                SelectedFile(entry.path, relativePath)
            }
            .toList()
        if (files.size > SkillInstallService.MAX_FILE_COUNT) {
            throw SkillInstallException(
                SkillInstallErrorCode.TOO_MANY_FILES,
                "Remote skill has ${files.size} files; limit is ${SkillInstallService.MAX_FILE_COUNT}",
            )
        }
        if (files.count { it.relativePath == SKILL_FILE_NAME } != 1) {
            throw SkillInstallException(
                SkillInstallErrorCode.MISSING_SKILL_FILE,
                "Remote skill must contain one root SKILL.md",
            )
        }
        return files
    }

    private suspend fun downloadRawFile(
        source: SkillShSource,
        revision: String,
        repositoryPath: String,
        maxBytes: Int,
    ): ByteArray {
        validateRepositoryPath(repositoryPath)
        val url = RAW_GITHUB_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment(source.owner)
            .addPathSegment(source.repo)
            .addPathSegment(revision)
            .apply { repositoryPath.split('/').forEach(::addPathSegment) }
            .build()
        return execute(url, RAW_GITHUB_HOST, maxBytes)
    }

    private suspend inline fun <reified T> getJson(url: HttpUrl, maxBytes: Int): T {
        val bytes = execute(url, GITHUB_API_HOST, maxBytes)
        val text = try {
            requireStrictUtf8(bytes, "GitHub API response")
        } catch (error: SkillInstallException) {
            throw SkillInstallException(
                SkillInstallErrorCode.DOWNLOAD_FAILED,
                "GitHub returned invalid JSON encoding",
                error,
            )
        }
        return try {
            json.decodeFromString<T>(text)
        } catch (error: Exception) {
            throw SkillInstallException(
                SkillInstallErrorCode.DOWNLOAD_FAILED,
                "GitHub returned an invalid response",
                error,
            )
        }
    }

    private suspend fun execute(url: HttpUrl, expectedHost: String, maxBytes: Int): ByteArray {
        if (url.scheme != "https" || url.port != 443 ||
            !url.host.equals(expectedHost, ignoreCase = true) ||
            url.username.isNotEmpty() || url.password.isNotEmpty()
        ) {
            throw invalidSource("Unexpected remote host")
        }
        val request = Request.Builder()
            .get()
            .url(url)
            .header("Accept", if (expectedHost == GITHUB_API_HOST) GITHUB_ACCEPT else "text/plain")
            .build()
        try {
            client.newCall(request).await().use { response ->
                val finalUrl = response.request.url
                if (finalUrl.scheme != "https" || finalUrl.port != 443 ||
                    !finalUrl.host.equals(expectedHost, ignoreCase = true) ||
                    finalUrl.username.isNotEmpty() || finalUrl.password.isNotEmpty()
                ) {
                    throw invalidSource("Remote response changed to an unexpected host")
                }
                if (response.code in HttpURLConnection.HTTP_MULT_CHOICE until 400) {
                    throw invalidSource("Remote redirects are not allowed")
                }
                if (!response.isSuccessful) {
                    throw SkillInstallException(
                        SkillInstallErrorCode.DOWNLOAD_FAILED,
                        "Remote source returned HTTP ${response.code}",
                    )
                }
                val declaredLength = response.body.contentLength()
                if (declaredLength > maxBytes) {
                    throw SkillInstallException(
                        SkillInstallErrorCode.FILE_TOO_LARGE,
                        "Remote response exceeds the $maxBytes byte limit",
                    )
                }
                return response.body.byteStream().readBoundedSource(maxBytes)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SkillInstallException) {
            throw error
        } catch (error: Exception) {
            throw SkillInstallException(
                SkillInstallErrorCode.DOWNLOAD_FAILED,
                "Unable to download verified GitHub content",
                error,
            )
        }
    }

    private fun parseCanonicalSkillShUrl(sourceUrl: String): SkillShSource {
        val url = sourceUrl.toHttpUrlOrNull() ?: throw invalidSource("Invalid skills.sh URL")
        if (url.scheme != "https" || url.host != SKILLS_SH_HOST || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null
        ) {
            throw invalidSource("Only canonical HTTPS skills.sh URLs are supported")
        }
        val segments = url.pathSegments
        if (segments.size != 3 || segments.any(String::isBlank)) {
            throw invalidSource("Expected https://skills.sh/{owner}/{repo}/{skill}")
        }
        val (owner, repo, slug) = segments
        if (!GITHUB_OWNER.matches(owner) || owner.contains("--") ||
            !GITHUB_REPOSITORY.matches(repo) || repo == "." || repo == ".." || repo.endsWith(".git") ||
            !SKILL_SLUG.matches(slug)
        ) {
            throw invalidSource("skills.sh URL contains an invalid GitHub source or skill slug")
        }
        val canonical = "https://$SKILLS_SH_HOST/$owner/$repo/$slug"
        if (sourceUrl != canonical || url.encodedPath != "/$owner/$repo/$slug") {
            throw invalidSource("skills.sh URL must be canonical and must not contain encoded path data")
        }
        return SkillShSource(owner = owner, repo = repo, slug = slug)
    }

    private fun validateRepositoryPath(path: String) {
        validatePath(path, MAX_REPOSITORY_PATH_LENGTH, MAX_REPOSITORY_PATH_DEPTH)
    }

    private fun validateRelativePath(path: String) {
        validatePath(path, SkillInstallService.MAX_PATH_LENGTH, SkillInstallService.MAX_PATH_DEPTH)
    }

    private fun validatePath(path: String, maxLength: Int, maxDepth: Int) {
        if (path.isBlank() || path.length > maxLength || path.startsWith('/') ||
            path.contains('\\') || path.contains('\u0000') ||
            Normalizer.normalize(path, Normalizer.Form.NFC) != path
        ) {
            throw unsafePath(path)
        }
        val segments = path.split('/')
        if (segments.size > maxDepth || segments.any { segment ->
                segment.isBlank() || segment == "." || segment == ".." ||
                    segment.length > SkillInstallService.MAX_PATH_SEGMENT_LENGTH ||
                    segment.any(Char::isISOControl)
            }
        ) {
            throw unsafePath(path)
        }
    }

    private fun requireStrictUtf8(bytes: ByteArray, path: String): String = try {
        val decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        if ('\u0000' in decoded) {
            throw IllegalArgumentException("NUL is not allowed in text skill files")
        }
        decoded
    } catch (error: Exception) {
        throw SkillInstallException(
            SkillInstallErrorCode.INVALID_SKILL_FILE,
            "Remote file '$path' must be valid NUL-free UTF-8",
            error,
        )
    }

    private fun rejectGitLfsPointer(bytes: ByteArray, path: String) {
        val prefix = bytes.copyOfRange(0, minOf(bytes.size, GIT_LFS_PREFIX.length + 8))
            .toString(Charsets.US_ASCII)
        if (prefix.startsWith(GIT_LFS_PREFIX)) {
            throw SkillInstallException(
                SkillInstallErrorCode.UNSUPPORTED_ENTRY,
                "Remote file '$path' is a Git LFS pointer, not file content",
            )
        }
    }

    private fun githubApiUrl(vararg segments: String): HttpUrl =
        GITHUB_API_BASE_URL.toHttpUrl().newBuilder()
            .apply { segments.forEach(::addPathSegment) }
            .build()

    private fun buildGitHubRevisionUrl(
        source: SkillShSource,
        revision: String,
        skillRoot: String,
    ): String = GITHUB_WEB_BASE_URL.toHttpUrl().newBuilder()
        .addPathSegment(source.owner)
        .addPathSegment(source.repo)
        .addPathSegment("tree")
        .addPathSegment(revision)
        .apply {
            if (skillRoot.isNotEmpty()) skillRoot.split('/').forEach(::addPathSegment)
        }
        .build()
        .toString()
        .removeSuffix("/")

    private fun toSkillSlug(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\s_]+"), "-")
        .replace(Regex("[^a-z0-9-]"), "")
        .replace(Regex("-+"), "-")
        .trim('-')

    private fun unsupportedEntry(kind: String, path: String) = SkillInstallException(
        SkillInstallErrorCode.UNSUPPORTED_ENTRY,
        "Remote skill contains an unsupported $kind entry: $path",
    )

    private fun unsafePath(path: String) = SkillInstallException(
        SkillInstallErrorCode.UNSAFE_PATH,
        "Remote source contains an unsafe path: $path",
    )

    private fun invalidSource(message: String) = SkillInstallException(
        SkillInstallErrorCode.INVALID_SOURCE,
        message,
    )

    private data class SkillShSource(
        val owner: String,
        val repo: String,
        val slug: String,
    )

    private data class SelectedFile(
        val repositoryPath: String,
        val relativePath: String,
    )

    companion object {
        private const val PROVIDER_ID = "github"
        private const val SKILLS_SH_HOST = "skills.sh"
        private const val GITHUB_API_HOST = "api.github.com"
        private const val RAW_GITHUB_HOST = "raw.githubusercontent.com"
        private const val GITHUB_API_BASE_URL = "https://api.github.com/"
        private const val RAW_GITHUB_BASE_URL = "https://raw.githubusercontent.com/"
        private const val GITHUB_WEB_BASE_URL = "https://github.com/"
        private const val GITHUB_ACCEPT = "application/vnd.github+json"
        private const val SKILL_FILE_NAME = "SKILL.md"
        private const val DIRECTORY_MODE = "040000"
        private const val SYMBOLIC_LINK_MODE = "120000"
        private const val SUBMODULE_MODE = "160000"
        private const val GIT_LFS_PREFIX = "version https://git-lfs.github.com/spec/v1"
        private const val MAX_REF_LENGTH = 255
        private const val MAX_SMALL_JSON_BYTES = 256 * 1024
        private const val MAX_TREE_JSON_BYTES = 8 * 1024 * 1024
        private const val MAX_REPOSITORY_PATH_LENGTH = 4096
        private const val MAX_REPOSITORY_PATH_DEPTH = 64
        private const val MAX_SKILL_CANDIDATES = 64
        private const val GITHUB_CONNECT_TIMEOUT_SECONDS = 10L
        private const val GITHUB_REQUEST_TIMEOUT_SECONDS = 30L
        private const val PREVIEW_TOTAL_TIMEOUT_MILLIS = 90_000L
        private val REGULAR_FILE_MODES = setOf("100644", "100755")
        private val GIT_SHA = Regex("[0-9a-f]{40}")
        private val GITHUB_OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
        private val GITHUB_REPOSITORY = Regex("[A-Za-z0-9._-]{1,100}")
        private val SKILL_SLUG = Regex("[a-z0-9][a-z0-9-]{0,127}")
    }
}

@Serializable
private data class GitHubRepository(
    @kotlinx.serialization.SerialName("default_branch")
    val defaultBranch: String = "",
)

@Serializable
private data class GitHubCommit(
    val sha: String = "",
    val commit: GitHubCommitData = GitHubCommitData(),
)

@Serializable
private data class GitHubCommitData(
    val tree: GitHubTreePointer = GitHubTreePointer(),
)

@Serializable
private data class GitHubTreePointer(
    val sha: String = "",
)

@Serializable
private data class GitHubTree(
    val truncated: Boolean = false,
    val tree: List<GitHubTreeEntry> = emptyList(),
)

@Serializable
private data class GitHubTreeEntry(
    val path: String = "",
    val mode: String = "",
    val type: String = "",
    val sha: String = "",
    val size: Long? = null,
)

private fun java.io.InputStream.readBoundedSource(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) {
            throw SkillInstallException(
                SkillInstallErrorCode.FILE_TOO_LARGE,
                "Remote response exceeds the $maxBytes byte limit",
            )
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
