package me.ayuilos.miffan.data.skills.install

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.files.SkillFrontmatterParser

class SkillInstallService(
    private val sourceClient: RemoteSkillSourceClient,
    private val target: SkillInstallTarget,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val pendingPreviews = ConcurrentHashMap<String, PendingSkillInstall>()
    private val applyMutex = Mutex()

    suspend fun preview(sourceUrl: String): SkillInstallPreview = withContext(Dispatchers.IO) {
        pruneExpiredPreviews()
        val requestedUrl = validateSourceIdentifier(sourceUrl)
        val remotePackage = try {
            sourceClient.fetch(requestedUrl)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SkillInstallException) {
            throw error
        } catch (error: Exception) {
            throw SkillInstallException(
                code = SkillInstallErrorCode.DOWNLOAD_FAILED,
                message = error.message ?: "Unable to download the remote skill",
                cause = error,
            )
        }
        requireMatchingRequestedSource(requestedUrl, remotePackage.source)
        val validated = validatePackage(remotePackage)
        if (target.exists(validated.metadata.name)) {
            throw SkillInstallException(
                SkillInstallErrorCode.CONFLICT,
                "A skill named '${validated.metadata.name}' is already installed; overwrite is not allowed",
            )
        }

        val bundleSha256 = validated.files.bundleSha256()
        val riskCategories = buildRiskCategories(validated)
        val summaries = listOf(
            "Install skill '${validated.metadata.name}' from ${remotePackage.source.canonicalUrl} at commit ${remotePackage.source.revision}",
            "Create ${validated.files.size} files (${validated.totalSizeBytes} bytes), bundle SHA-256 $bundleSha256, without executing scripts",
            "Risk categories: ${riskCategories.joinToString(",") { it.name }}",
        )
        val previewId = createSkillInstallPreviewId(
            nonce = UUID.randomUUID().toString(),
            summaries = summaries,
        )
        val preview = SkillInstallPreview(
            previewId = previewId,
            sourceUrl = remotePackage.source.canonicalUrl,
            provider = remotePackage.source.provider,
            revision = remotePackage.source.revision,
            skill = validated.metadata,
            fileCount = validated.files.size,
            totalSizeBytes = validated.totalSizeBytes,
            bundleSha256 = bundleSha256,
            riskCategories = riskCategories,
            summaries = summaries,
        )
        pendingPreviews[previewId] = PendingSkillInstall(
            preview = preview,
            files = validated.files.associate { it.relativePath to it.content.copyOf() },
            createdAtMillis = currentTimeMillis(),
        )
        trimPreviewCache()
        preview
    }

    suspend fun apply(previewId: String): SkillInstallApplyResult = withContext(Dispatchers.IO) {
        if (previewId.length !in 1..MAX_SKILL_INSTALL_PREVIEW_ID_LENGTH) {
            return@withContext SkillInstallApplyResult(
                applied = false,
                errorCode = SkillInstallErrorCode.PREVIEW_NOT_FOUND,
                error = "Preview not found, expired, already used, or modified; create a new preview",
            )
        }
        val pending = pendingPreviews.remove(previewId)
            ?: return@withContext SkillInstallApplyResult(
                applied = false,
                errorCode = SkillInstallErrorCode.PREVIEW_NOT_FOUND,
                error = "Preview not found, expired, already used, or modified; create a new preview",
            )
        if (currentTimeMillis() - pending.createdAtMillis > PREVIEW_TTL_MILLIS) {
            return@withContext SkillInstallApplyResult(
                applied = false,
                errorCode = SkillInstallErrorCode.PREVIEW_EXPIRED,
                error = "Preview expired; create a new preview",
            )
        }

        applyMutex.withLock {
            val skillName = pending.preview.skill.name
            if (target.exists(skillName)) {
                return@withLock SkillInstallApplyResult(
                    applied = false,
                    errorCode = SkillInstallErrorCode.CONFLICT,
                    error = "A skill named '$skillName' was installed after preview; overwrite is not allowed",
                )
            }
            val installed = try {
                target.installNewAtomically(skillName, pending.files)
            } catch (_: Exception) {
                false
            }
            if (!installed) {
                return@withLock SkillInstallApplyResult(
                    applied = false,
                    errorCode = SkillInstallErrorCode.INSTALL_FAILED,
                    error = "Unable to install '$skillName' atomically",
                )
            }
            SkillInstallApplyResult(
                applied = true,
                skillName = skillName,
                summaries = pending.preview.summaries,
            )
        }
    }

    private fun validatePackage(remotePackage: RemoteSkillPackage): ValidatedPackage {
        val source = remotePackage.source
        if (!isSafeCanonicalSource(source.canonicalUrl) ||
            !SAFE_PROVIDER.matches(source.provider) ||
            !SAFE_REVISION.matches(source.revision)
        ) {
            throw SkillInstallException(
                SkillInstallErrorCode.INVALID_SOURCE,
                "Remote source metadata is invalid",
            )
        }
        if (remotePackage.files.isEmpty()) {
            throw SkillInstallException(SkillInstallErrorCode.EMPTY_PACKAGE, "Remote skill has no files")
        }
        if (remotePackage.files.size > MAX_FILE_COUNT) {
            throw SkillInstallException(
                SkillInstallErrorCode.TOO_MANY_FILES,
                "Remote skill has ${remotePackage.files.size} files; limit is $MAX_FILE_COUNT",
            )
        }

        val seenPaths = HashSet<String>()
        val seenCaseFoldedPaths = HashSet<String>()
        var totalSize = 0L
        val files = remotePackage.files.map { entry ->
            if (entry.kind != RemoteSkillEntryKind.REGULAR_FILE) {
                throw SkillInstallException(
                    SkillInstallErrorCode.UNSUPPORTED_ENTRY,
                    "Remote skill contains an unsupported ${entry.kind.name.lowercase()} entry: ${entry.relativePath}",
                )
            }
            val path = validateRelativePath(entry.relativePath)
            if (!seenPaths.add(path) || !seenCaseFoldedPaths.add(path.lowercase(Locale.ROOT))) {
                throw SkillInstallException(
                    SkillInstallErrorCode.UNSAFE_PATH,
                    "Remote skill contains a duplicate path: $path",
                )
            }
            val fileLimit = if (path == SKILL_FILE_NAME) MAX_SKILL_FILE_BYTES else MAX_FILE_BYTES
            if (entry.content.size > fileLimit) {
                throw SkillInstallException(
                    SkillInstallErrorCode.FILE_TOO_LARGE,
                    "Remote file '$path' is ${entry.content.size} bytes; limit is $fileLimit",
                )
            }
            totalSize += entry.content.size
            if (totalSize > MAX_TOTAL_BYTES) {
                throw SkillInstallException(
                    SkillInstallErrorCode.PACKAGE_TOO_LARGE,
                    "Remote skill exceeds the $MAX_TOTAL_BYTES byte package limit",
                )
            }
            ValidatedFile(path, entry.content.copyOf())
        }

        val skillFile = files.singleOrNull { it.relativePath == SKILL_FILE_NAME }
            ?: throw SkillInstallException(
                SkillInstallErrorCode.MISSING_SKILL_FILE,
                "Remote skill must contain exactly one root SKILL.md",
            )
        val skillContent = decodeUtf8(skillFile.content)
        val frontmatter = SkillFrontmatterParser.parse(skillContent)
        val name = frontmatter["name"]?.trim().orEmpty()
        val description = frontmatter["description"]?.trim().orEmpty()
        if (name.isBlank()) {
            throw SkillInstallException(
                SkillInstallErrorCode.INVALID_SKILL_FILE,
                "SKILL.md must contain a name",
            )
        }
        if (!SAFE_SKILL_NAME.matches(name) || name in RESERVED_SKILL_NAMES) {
            throw SkillInstallException(
                SkillInstallErrorCode.INVALID_SKILL_NAME,
                "SKILL.md name must be 1-$MAX_SKILL_NAME_LENGTH safe ASCII characters",
            )
        }
        if (description.isBlank() || description.length > MAX_DESCRIPTION_LENGTH) {
            throw SkillInstallException(
                SkillInstallErrorCode.INVALID_SKILL_FILE,
                "SKILL.md must contain a description of at most $MAX_DESCRIPTION_LENGTH characters",
            )
        }
        val compatibility = frontmatter["compatibility"]?.trim()?.takeIf { it.isNotBlank() }
        if (compatibility != null && compatibility.length > MAX_COMPATIBILITY_LENGTH) {
            throw SkillInstallException(
                SkillInstallErrorCode.INVALID_SKILL_FILE,
                "SKILL.md compatibility is too long",
            )
        }
        val allowedTools = frontmatter["allowed-tools"]
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (allowedTools.size > MAX_ALLOWED_TOOLS || allowedTools.any { !SAFE_TOOL_NAME.matches(it) }) {
            throw SkillInstallException(
                SkillInstallErrorCode.INVALID_SKILL_FILE,
                "SKILL.md allowed-tools is too large",
            )
        }

        return ValidatedPackage(
            metadata = SkillInstallMetadata(
                name = name,
                declaredToolCount = allowedTools.size,
            ),
            files = files.sortedBy { it.relativePath },
            totalSizeBytes = totalSize,
        )
    }

    private fun validateRelativePath(rawPath: String): String {
        if (rawPath.isBlank() || rawPath.length > MAX_PATH_LENGTH ||
            rawPath.startsWith('/') || rawPath.startsWith('\\') ||
            rawPath.contains('\\') || rawPath.contains('\u0000')
        ) {
            throw unsafePath(rawPath)
        }
        val segments = rawPath.split('/')
        if (segments.size > MAX_PATH_DEPTH || segments.any { segment ->
                segment.isBlank() || segment == "." || segment == ".." ||
                    segment.length > MAX_PATH_SEGMENT_LENGTH ||
                    segment.any { it.isISOControl() }
            }
        ) {
            throw unsafePath(rawPath)
        }
        return segments.joinToString("/")
    }

    private fun unsafePath(path: String) = SkillInstallException(
        SkillInstallErrorCode.UNSAFE_PATH,
        "Remote skill contains an unsafe path: $path",
    )

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        throw SkillInstallException(
            SkillInstallErrorCode.INVALID_SKILL_FILE,
            "SKILL.md must be valid UTF-8",
            error,
        )
    }

    private fun buildRiskCategories(validated: ValidatedPackage): List<SkillInstallRiskCategory> = buildList {
        add(SkillInstallRiskCategory.UNTRUSTED_REMOTE_CONTENT)
        val scriptCount = validated.files.count { file ->
            file.relativePath.substringAfterLast('.', missingDelimiterValue = "")
                .lowercase(Locale.ROOT) in SCRIPT_EXTENSIONS
        }
        if (scriptCount > 0) {
            add(SkillInstallRiskCategory.SCRIPT_LIKE_FILES_PRESENT)
        }
        if (validated.metadata.declaredToolCount > 0) {
            add(SkillInstallRiskCategory.TOOL_DECLARATIONS_PRESENT)
        }
    }

    private fun validateSourceIdentifier(sourceUrl: String): String {
        val trimmed = sourceUrl.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        if (trimmed.isEmpty() || trimmed.length > MAX_SOURCE_LENGTH || uri?.scheme != "https" ||
            uri.host.isNullOrBlank() || uri.userInfo != null
        ) {
            throw SkillInstallException(
                SkillInstallErrorCode.INVALID_SOURCE,
                "Skill source must be a valid HTTPS URL supported by the source client",
            )
        }
        return trimmed
    }

    private fun isSafeCanonicalSource(value: String): Boolean {
        if (value.isBlank() || value.length > MAX_SOURCE_LENGTH) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null
    }

    private fun requireMatchingRequestedSource(requestedUrl: String, source: VerifiedSkillSource) {
        if (source.requestedUrl != requestedUrl) {
            throw SkillInstallException(
                SkillInstallErrorCode.INVALID_SOURCE,
                "Remote source client returned a package for a different request",
            )
        }
    }

    private fun pruneExpiredPreviews() {
        val now = currentTimeMillis()
        pendingPreviews.entries.removeIf { now - it.value.createdAtMillis > PREVIEW_TTL_MILLIS }
    }

    private fun trimPreviewCache() {
        if (pendingPreviews.size <= MAX_PENDING_PREVIEWS) return
        pendingPreviews.entries
            .sortedBy { it.value.createdAtMillis }
            .take(pendingPreviews.size - MAX_PENDING_PREVIEWS)
            .forEach { pendingPreviews.remove(it.key, it.value) }
    }

    private fun List<ValidatedFile>.bundleSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in sortedBy { it.relativePath }) {
            val path = file.relativePath.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(path.size).array())
            digest.update(path)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(file.content.size.toLong()).array())
            digest.update(file.content)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class PendingSkillInstall(
        val preview: SkillInstallPreview,
        val files: Map<String, ByteArray>,
        val createdAtMillis: Long,
    )

    private data class ValidatedPackage(
        val metadata: SkillInstallMetadata,
        val files: List<ValidatedFile>,
        val totalSizeBytes: Long,
    )

    private data class ValidatedFile(
        val relativePath: String,
        val content: ByteArray,
    )

    companion object {
        const val MAX_FILE_COUNT = 64
        const val MAX_FILE_BYTES = 1024 * 1024
        const val MAX_SKILL_FILE_BYTES = 256 * 1024
        const val MAX_TOTAL_BYTES = 4 * 1024 * 1024
        const val MAX_PATH_LENGTH = 240
        const val MAX_PATH_DEPTH = 12
        const val MAX_PATH_SEGMENT_LENGTH = 100
        const val MAX_SKILL_NAME_LENGTH = 64
        private const val MAX_SOURCE_LENGTH = 2048
        private const val MAX_DESCRIPTION_LENGTH = 2000
        private const val MAX_COMPATIBILITY_LENGTH = 500
        private const val MAX_ALLOWED_TOOLS = 64
        private const val MAX_TOOL_NAME_LENGTH = 100
        private const val MAX_PENDING_PREVIEWS = 32
        private const val PREVIEW_TTL_MILLIS = 10 * 60 * 1000L
        private const val SKILL_FILE_NAME = "SKILL.md"
        private val SAFE_SKILL_NAME = Regex("[a-z0-9][a-z0-9-]{0,63}")
        private val SAFE_PROVIDER = Regex("[a-z0-9][a-z0-9-]{0,31}")
        private val SAFE_REVISION = Regex("[0-9a-fA-F]{40}")
        private val SAFE_TOOL_NAME = Regex("[A-Za-z0-9_.:/@*?(){}\\[\\],+-]{1,$MAX_TOOL_NAME_LENGTH}")
        private val RESERVED_SKILL_NAMES = setOf("extension-management")
        private val SCRIPT_EXTENSIONS = setOf(
            "bat", "cmd", "exe", "js", "mjs", "ps1", "py", "rb", "sh", "zsh",
        )
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }
