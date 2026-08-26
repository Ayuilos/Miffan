package me.ayuilos.miffan.data.ai.tools

import android.webkit.MimeTypeMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.ui.UIMessagePart
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea

/**
 * A persistent reference to a user-facing file produced in a workspace.
 *
 * The workspace id is deliberately stored with the message tool output instead of being looked up
 * from the Assistant at render time. This keeps historical artifacts attached to the workspace in
 * which they were created even if the Assistant is rebound later.
 */
data class WorkspaceArtifact(
    val workspaceId: String,
    val path: String,
    val name: String = path.substringAfterLast('/'),
    val mimeType: String = workspaceMimeType(name),
    val sizeBytes: Long? = null,
    val updatedAt: Long? = null,
) {
    init {
        require(workspaceId.isNotBlank()) { "workspaceId is required" }
        require(path.startsWith('/')) { "Workspace artifact path must be absolute: $path" }
        require(name.isNotBlank()) { "Workspace artifact name is required" }
    }

    fun location(): WorkspaceFileLocation {
        val normalized = path.trimEnd('/')
        return if (normalized == "/workspace" || normalized.startsWith("/workspace/")) {
            WorkspaceFileLocation(
                area = WorkspaceStorageArea.FILES,
                relativePath = normalized.removePrefix("/workspace").trimStart('/'),
            )
        } else {
            WorkspaceFileLocation(
                area = WorkspaceStorageArea.LINUX,
                relativePath = normalized.trimStart('/'),
            )
        }
    }
}

data class WorkspaceFileLocation(
    val area: WorkspaceStorageArea,
    val relativePath: String,
)

internal fun WorkspaceFileEntry.toWorkspaceArtifact(
    workspaceId: String,
    absolutePath: String = path,
): WorkspaceArtifact = WorkspaceArtifact(
    workspaceId = workspaceId,
    path = absolutePath,
    name = name,
    mimeType = workspaceMimeType(name),
    sizeBytes = sizeBytes,
    updatedAt = updatedAt,
)

internal fun WorkspaceArtifact.toJson(): JsonObject = buildJsonObject {
    put("type", "workspace_artifact")
    put("workspaceId", workspaceId)
    put("path", path)
    put("name", name)
    put("mimeType", mimeType)
    sizeBytes?.let { put("sizeBytes", it) }
    updatedAt?.let { put("updatedAt", it) }
}

internal fun workspaceArtifactsJson(artifacts: List<WorkspaceArtifact>): JsonObject = buildJsonObject {
    put("artifacts", buildJsonArray {
        artifacts.forEach { add(it.toJson()) }
    })
}

/** Extract persisted artifacts from a completed workspace tool call, including legacy messages. */
internal fun UIMessagePart.Tool.workspaceArtifacts(
    fallbackWorkspaceId: String? = null,
): List<WorkspaceArtifact> {
    if (!isExecuted) return emptyList()

    val outputObjects = output.filterIsInstance<UIMessagePart.Text>().mapNotNull { part ->
        runCatching { JsonInstant.parseToJsonElement(part.text).jsonObject }.getOrNull()
    }
    val persisted = outputObjects.flatMap { output ->
        val nested = output["artifacts"] as? JsonArray
        if (nested != null) {
            nested.mapNotNull { element ->
                val value = element as? JsonObject
                value?.takeIf { it.string("type") == "workspace_artifact" }
                    ?.toWorkspaceArtifactOrNull(fallbackWorkspaceId)
            }
        } else if (
            output.string("type") == "workspace_artifact" || toolName in LEGACY_ARTIFACT_TOOL_NAMES
        ) {
            listOfNotNull(output.toWorkspaceArtifactOrNull(fallbackWorkspaceId))
        } else {
            emptyList()
        }
    }
    if (persisted.isNotEmpty()) return persisted.distinctBy { "${it.workspaceId}:${it.path}" }

    // Messages created before workspaceId was added to tool outputs only retain the input path.
    if (toolName !in LEGACY_ARTIFACT_TOOL_NAMES || fallbackWorkspaceId.isNullOrBlank()) {
        return emptyList()
    }
    val path = runCatching {
        val input = inputAsJson().jsonObject
        (input["path"] ?: input["destination_path"])?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.startsWith('/') } ?: return emptyList()
    return listOf(WorkspaceArtifact(workspaceId = fallbackWorkspaceId, path = path))
}

private fun JsonElement.toWorkspaceArtifactOrNull(fallbackWorkspaceId: String?): WorkspaceArtifact? {
    val value = this as? JsonObject ?: return null
    val path = value.string("path")?.toAbsoluteWorkspacePath() ?: return null
    val workspaceId = value.string("workspaceId") ?: fallbackWorkspaceId
    if (workspaceId.isNullOrBlank()) return null
    val name = value.string("name")?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/')
    return runCatching {
        WorkspaceArtifact(
            workspaceId = workspaceId,
            path = path,
            name = name,
            mimeType = value.string("mimeType") ?: workspaceMimeType(name),
            sizeBytes = value["sizeBytes"]?.jsonPrimitive?.longOrNull,
            updatedAt = value["updatedAt"]?.jsonPrimitive?.longOrNull,
        )
    }.getOrNull()
}

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun String.toAbsoluteWorkspacePath(): String =
    if (startsWith('/')) this else "/workspace/${trimStart('/')}"

internal fun workspaceMimeType(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension.isBlank()) return "application/octet-stream"
    return EXTRA_MIME_TYPES[extension]
        ?: runCatching {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }.getOrNull()
        ?: "application/octet-stream"
}

private val LEGACY_ARTIFACT_TOOL_NAMES = setOf(
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_fetch_url",
)

private val EXTRA_MIME_TYPES = mapOf(
    "txt" to "text/plain",
    "md" to "text/markdown",
    "markdown" to "text/markdown",
    "json" to "application/json",
    "json5" to "application/json",
    "yaml" to "text/yaml",
    "yml" to "text/yaml",
    "toml" to "text/plain",
    "csv" to "text/csv",
    "tsv" to "text/tab-separated-values",
    "pdf" to "application/pdf",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "svg" to "image/svg+xml",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "avif" to "image/avif",
)
