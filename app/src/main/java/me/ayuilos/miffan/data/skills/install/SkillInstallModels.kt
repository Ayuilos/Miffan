package me.ayuilos.miffan.data.skills.install

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A source whose network redirects and final host have been verified by the source client. */
data class VerifiedSkillSource(
    /** The URL supplied by the caller. It is provenance only and must not be treated as trusted. */
    val requestedUrl: String,
    /** Stable, human-readable source selected by the source client after verification. */
    val canonicalUrl: String,
    val provider: String,
    /** Immutable 40-character commit SHA verified by the source client. */
    val revision: String,
)

enum class RemoteSkillEntryKind {
    REGULAR_FILE,
    DIRECTORY,
    SYMBOLIC_LINK,
    OTHER,
}

data class RemoteSkillFile(
    val relativePath: String,
    val content: ByteArray,
    val kind: RemoteSkillEntryKind = RemoteSkillEntryKind.REGULAR_FILE,
)

/** Exact bytes returned by a trusted remote-source adapter. No entry is ever executed. */
data class RemoteSkillPackage(
    val source: VerifiedSkillSource,
    val files: List<RemoteSkillFile>,
)

/**
 * Network boundary for skill installation.
 *
 * Implementations must allow-list supported providers, validate the host after every redirect and
 * describe the verified final source in [RemoteSkillPackage.source]. The caller-provided URL is
 * only a source identifier; it is never authorization to fetch arbitrary redirect targets.
 */
fun interface RemoteSkillSourceClient {
    suspend fun fetch(sourceUrl: String): RemoteSkillPackage
}

@Serializable
data class SkillInstallMetadata(
    val name: String,
    /** Count only: remote tool names are not returned to the model. */
    val declaredToolCount: Int = 0,
    /** Frontmatter tool declarations are metadata, not granted permissions. */
    val allowedToolsAreDeclarationsOnly: Boolean = true,
)

@Serializable
enum class SkillInstallRiskCategory {
    UNTRUSTED_REMOTE_CONTENT,
    SCRIPT_LIKE_FILES_PRESENT,
    TOOL_DECLARATIONS_PRESENT,
}

@Serializable
data class SkillInstallPreview(
    /** One-use, expiring capability whose serialized value also carries approval summaries. */
    val previewId: String,
    val sourceUrl: String,
    val provider: String,
    val revision: String,
    val skill: SkillInstallMetadata,
    /** Individual remote paths are intentionally omitted from model-visible output. */
    val fileCount: Int,
    val totalSizeBytes: Long,
    val bundleSha256: String,
    val riskCategories: List<SkillInstallRiskCategory>,
    val summaries: List<String>,
)

@Serializable
data class SkillInstallApplyResult(
    val applied: Boolean,
    val skillName: String? = null,
    val summaries: List<String> = emptyList(),
    val errorCode: SkillInstallErrorCode? = null,
    val error: String? = null,
)

@Serializable
enum class SkillInstallErrorCode {
    INVALID_SOURCE,
    DOWNLOAD_FAILED,
    EMPTY_PACKAGE,
    TOO_MANY_FILES,
    FILE_TOO_LARGE,
    PACKAGE_TOO_LARGE,
    UNSAFE_PATH,
    UNSUPPORTED_ENTRY,
    MISSING_SKILL_FILE,
    INVALID_SKILL_FILE,
    INVALID_SKILL_NAME,
    CONFLICT,
    PREVIEW_NOT_FOUND,
    PREVIEW_EXPIRED,
    INSTALL_FAILED,
}

class SkillInstallException(
    val code: SkillInstallErrorCode,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

@Serializable
private data class SkillInstallPreviewCapability(
    val nonce: String,
    val summaries: List<String>,
)

internal const val MAX_SKILL_INSTALL_PREVIEW_ID_LENGTH = 8 * 1024

internal fun createSkillInstallPreviewId(nonce: String, summaries: List<String>): String =
    Json.encodeToString(SkillInstallPreviewCapability(nonce = nonce, summaries = summaries))

fun decodeSkillInstallPreviewSummaries(previewId: String): List<String> {
    if (previewId.length !in 1..MAX_SKILL_INSTALL_PREVIEW_ID_LENGTH) return emptyList()
    return runCatching {
        Json.decodeFromString<SkillInstallPreviewCapability>(previewId).summaries
    }.getOrDefault(emptyList())
}
