package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.utils.generateUnifiedDiff
import me.rerere.workspace.GuestPath
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_publish_files" to false,
    "workspace_shell" to true,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createFetchUrlTool(workspaceId, workspaceRepository),
        createPublishFilesTool(workspaceId, workspaceRepository),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
    )
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun createFetchUrlTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_fetch_url",
    description = "Download one public HTTPS URL through the approved host broker into /workspace. " +
        "Private/local addresses, cross-host redirects, non-standard ports, and responses larger than " +
        "8 MiB are rejected. Shell commands share Miffan's network permission and can access the " +
        "network directly after shell approval.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Public HTTPS URL to download")
                })
                put("destination_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute destination file path below /workspace")
                })
            },
            required = listOf("url", "destination_path"),
        )
    },
    // Network access never inherits a workspace override; every request remains user-approved.
    needsApproval = { true },
    execute = {
        val params = it.jsonObject
        val url = params.string("url") ?: error("url is required")
        val destination = params.guestPath("destination_path")
        val entry = workspaceRepository.fetchUrl(workspaceId, url, destination.value)
        val artifact = entry.toWorkspaceArtifact(workspaceId, absolutePath = destination.value)
        listOf(UIMessagePart.Text(artifact.toJson().toString()))
    },
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val path = it.jsonObject.guestPath("path")
        if (path.value.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path.value)
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path.value)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path.value)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = {
        needsApproval("workspace_write_file") || it.pathOutsideWritableRoots("path")
    },
    execute = {
        val params = it.jsonObject
        val path = params.guestPath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeRootfsText(workspaceId, path.value, text, overwrite)
        listOf(UIMessagePart.Text(entry.toWorkspaceArtifact(workspaceId).toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.guestPath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path.value)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: ${path.value})")
        }
        val entry = workspaceRepository.writeRootfsText(
            workspaceId,
            path.value,
            result.updated,
            overwrite = true,
        )
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("type", "workspace_artifact")
                    put("workspaceId", workspaceId)
                    put("path", entry.path)
                    put("name", entry.name)
                    put("mimeType", workspaceMimeType(entry.name))
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready. Commands share Miffan's app UID and permissions; PRoot is not a security sandbox.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                    if (result.resourceLimitExceeded) put("resourceLimitExceeded", true)
                }.toString()
            )
        )
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Shell commands can inspect parts only when the path is mounted in workspace_shell."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

/**
 * Marks existing files as user-facing artifacts after a shell command creates binary or generated
 * output. The tool only validates and describes files; it never copies or mutates them.
 */
private fun createPublishFilesTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_publish_files",
    description = "Publish one or more existing user-facing files so the app can show them as " +
        "previewable artifacts below the response. Use this after workspace_shell creates files " +
        "such as reports, text/code, images, PDFs, documents, archives, audio, or video. " +
        "Paths must be absolute Rootfs paths.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("paths", buildJsonObject {
                    put("type", "array")
                    put("description", "Absolute Rootfs paths of user-facing output files")
                    put("items", buildJsonObject {
                        put("type", "string")
                    })
                })
            },
            required = listOf("paths"),
        )
    },
    needsApproval = { false },
    execute = { input ->
        val paths = input.jsonObject["paths"]?.jsonArray
            ?.map { GuestPath.parse(it.jsonPrimitive.content, "paths") }
            ?.distinct()
            ?: error("paths is required")
        require(paths.isNotEmpty()) { "paths must not be empty" }
        require(paths.size <= MAX_PUBLISHED_ARTIFACTS) {
            "Too many artifacts: ${paths.size}, max $MAX_PUBLISHED_ARTIFACTS"
        }
        val artifacts = paths.map { path ->
            val size = workspaceRepository.rootfsFileSize(workspaceId, path.value)
            WorkspaceArtifact(
                workspaceId = workspaceId,
                path = path.value,
                name = path.name,
                mimeType = workspaceMimeType(path.name),
                sizeBytes = size,
            )
        }
        listOf(UIMessagePart.Text(workspaceArtifactsJson(artifacts).toString()))
    },
)

private fun kotlinx.serialization.json.JsonObject.guestPath(name: String): GuestPath =
    GuestPath.parse(string(name) ?: error("$name is required"), name)

// 免强制审批的可写安全区: 工作区文件目录, 以及临时目录 /tmp
private val WRITABLE_ROOTS = listOf(GuestPath.parse("/workspace"), GuestPath.parse("/tmp"))

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(name: String): Boolean =
    runCatching {
        workspaceWriteRequiresApproval(jsonObject.guestPath(name))
    }.getOrDefault(true)

internal fun workspaceWriteRequiresApproval(path: GuestPath): Boolean =
    WRITABLE_ROOTS.none(path::isWithin)

internal fun workspaceWriteRequiresApproval(path: String): Boolean =
    runCatching { workspaceWriteRequiresApproval(GuestPath.parse(path)) }.getOrDefault(true)

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private const val MAX_PUBLISHED_ARTIFACTS = 20
