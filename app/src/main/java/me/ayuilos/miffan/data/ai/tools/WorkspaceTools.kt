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
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import me.rerere.ai.ui.toMetadata
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.utils.generateUnifiedDiff
import me.rerere.workspace.GuestPath
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
const val WORKSPACE_SHELL_TOOL_NAME = "workspace_shell"
val WORKSPACE_TOOL_NAMES = setOf(
    "workspace_read_file",
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_fetch_url",
    "workspace_publish_files",
    WORKSPACE_SHELL_TOOL_NAME,
)

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_publish_files" to false,
    WORKSPACE_SHELL_TOOL_NAME to true,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    assistantId: String,
    workspaceId: String?,
    scopeId: String?,
    shellEnabled: Boolean,
    shellApprovalRequired: Boolean,
    shellApprovalTarget: WorkspaceToolTargetSnapshot?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
    val approvalOverrides = workspace.toolApprovalOverrides()
    val remoteHost = workspace.remoteHostId?.let { workspaceRepository.getHostById(it) }
    if (workspace.isRemote && (remoteHost?.trustedHostKeySha256 == null || workspace.shellStatus != WorkspaceShellStatus.READY.name)) return emptyList()
    val snapshot = workspaceRepository.currentWorkspaceToolTarget(assistantId, workspaceId, scopeId)
        ?: return emptyList()
    val remoteHostLabel = remoteHost?.let { "${it.username}@${it.host}:${it.port}" }
    val target = WorkspaceToolTarget(workspace, remoteHostLabel, snapshot)
    fun needsApproval(name: String) = if (name == WORKSPACE_SHELL_TOOL_NAME) {
        shellApprovalRequired || shellApprovalTarget?.sameTarget(snapshot) != true
    } else {
        resolveWorkspaceToolApproval(name, approvalOverrides)
    }

    val shellCwd = if (workspace.isRemote) cwd else cwd?.toWorkspaceRelativeCwd()

    return buildList {
        add(createReadFileTool(workspaceId, scopeId, ::needsApproval, workspaceRepository, target))
        add(createWriteFileTool(workspaceId, scopeId, ::needsApproval, workspaceRepository, target))
        add(createEditFileTool(workspaceId, scopeId, ::needsApproval, workspaceRepository, target))
        add(createFetchUrlTool(workspaceId, scopeId, workspaceRepository, target))
        add(createPublishFilesTool(workspaceId, scopeId, workspaceRepository, target))
        if (shellEnabled) {
            add(createShellTool(workspaceId, scopeId, ::needsApproval, workspaceRepository, shellCwd, target))
        }
    }
}

private data class WorkspaceToolTarget(
    val workspace: WorkspaceEntity,
    val remoteHostLabel: String?,
    val snapshot: WorkspaceToolTargetSnapshot,
) {
    val root: String get() = workspace.remotePath.orEmpty()
    val fileDescription: String get() = if (workspace.isRemote) {
        "Remote SSH files on ${remoteHostLabel ?: "the configured host"}. /workspace maps to the remote directory $root; file paths must be absolute."
    } else {
        "Files inside the assistant's bound local Rootfs. Paths must be absolute; /workspace is the workspace files area."
    }
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun createFetchUrlTool(
    workspaceId: String,
    scopeId: String?,
    workspaceRepository: WorkspaceRepository,
    target: WorkspaceToolTarget,
) = Tool(
    name = "workspace_fetch_url",
    workspaceTarget = target.snapshot,
    description = "Download one public HTTPS URL through the approved Android app broker into /workspace" +
        (if (target.workspace.isRemote) " (uploaded into remote directory '${target.root}' over SFTP). " else ". ") +
        "Private/local addresses, cross-host redirects, non-standard ports, and responses larger than " +
        "8 MiB are rejected. Shell commands may separately use their execution environment's network permissions after shell approval.",
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
        val entry = workspaceRepository.fetchUrl(
            workspaceId, url, destination.value, scopeId, expectedTarget = target.snapshot,
        )
        val artifact = entry.toWorkspaceArtifact(
            workspaceId,
            scopeId,
            absolutePath = destination.value,
        )
        listOf(UIMessagePart.Text(artifact.toJson().toString()))
    },
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    scopeId: String?,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    target: WorkspaceToolTarget,
) = Tool(
    name = "workspace_read_file",
    workspaceTarget = target.snapshot,
    description = """
        Read a file. ${target.fileDescription}
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true, target = target)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val path = it.jsonObject.guestPath("path")
        if (path.value.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, scopeId, path.value, target.snapshot)
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, scopeId, path.value, target.snapshot)
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
    scopeId: String?,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    target: WorkspaceToolTarget,
) = Tool(
    name = "workspace_write_file",
    workspaceTarget = target.snapshot,
    description = """
        Write a UTF-8 text file. ${target.fileDescription}
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true, target = target)
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
        val entry = workspaceRepository.writeRootfsText(
            workspaceId,
            path.value,
            text,
            overwrite,
            scopeId,
            expectedTarget = target.snapshot,
        )
        listOf(
            UIMessagePart.Text(entry.toWorkspaceArtifact(workspaceId, scopeId).toJson().toString())
        )
    },
)

private fun createEditFileTool(
    workspaceId: String,
    scopeId: String?,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    target: WorkspaceToolTarget,
) = Tool(
    name = "workspace_edit_file",
    workspaceTarget = target.snapshot,
    description = """
        Edit a UTF-8 text file. ${target.fileDescription}
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true, target = target)
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

        val original = workspaceRepository.readTextInRootfs(workspaceId, scopeId, path.value, target.snapshot)
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
            scopeId = scopeId,
            expectedTarget = target.snapshot,
        )
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("type", "workspace_artifact")
                    put("workspaceId", workspaceId)
                    scopeId?.let { put("scopeId", it) }
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
    scopeId: String?,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
    target: WorkspaceToolTarget,
) = Tool(
    name = WORKSPACE_SHELL_TOOL_NAME,
    workspaceTarget = target.snapshot,
    description = buildString {
        if (target.workspace.isRemote) {
            append("Run a command over SSH on ${target.remoteHostLabel ?: "the configured remote host"}, starting in remote directory '${target.root}'. ")
            append("Use cwd relative to that remote directory. The remote shell has no /workspace mount; use real remote paths or relative paths in command text. ")
            append("This directory and remote machine are shared according to the remote host's permissions. ")
        } else {
            append("Run a shell command in the assistant's bound Workspace Rootfs. Only this Assistant's file scope is mounted at /workspace. ")
            append("Its HOME (/root) and temporary directories are private to the scope. The Rootfs system environment (/bin, /usr, /etc and installed packages) is shared by every Assistant bound to this Workspace, so system-level changes affect all of them. ")
            append("Use cwd for a path relative to the workspace files root. ")
        }
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        if (target.workspace.isRemote) {
            append("Requires a trusted SSH host key and a previously successful connection test. Commands use the remote account's permissions. Timeout or cancellation does not guarantee a started remote process stopped; check state before retrying mutating commands.")
        } else {
            append("Requires Rootfs to be installed and ready. Commands share Miffan's app UID and permissions; PRoot is not a security sandbox.")
        }
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
                        if (target.workspace.isRemote) {
                            "Remote working directory relative to '${target.root}', a virtual /workspace path, or a real absolute path inside that directory. Defaults to '${defaultCwd ?: target.root}'."
                        } else if (!defaultCwd.isNullOrBlank()) {
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
    needsApproval = { needsApproval(WORKSPACE_SHELL_TOOL_NAME) },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val requestedCwd = params.string("cwd") ?: defaultCwd.orEmpty()
        val cwd = if (target.workspace.isRemote) requestedCwd else requestedCwd.toWorkspaceRelativeCwd()
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(
            workspaceId,
            command,
            cwd,
            timeoutMillis,
            scopeId = scopeId,
            expectedTarget = target.snapshot,
        )
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
    scopeId: String?,
    path: String,
    expectedTarget: WorkspaceToolTargetSnapshot,
): String = readRootfsBuffer(workspaceId, scopeId, path, expectedTarget).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    scopeId: String?,
    path: String,
    expectedTarget: WorkspaceToolTargetSnapshot,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path, scopeId, expectedTarget = expectedTarget)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Shell commands can inspect parts only when the path is mounted in workspace_shell."
    }
    return ByteArrayOutputStream(size.toInt()).also {
        exportRootfsFile(workspaceId, path, it, scopeId, expectedTarget = expectedTarget)
    }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    scopeId: String?,
    path: String,
    expectedTarget: WorkspaceToolTargetSnapshot,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, scopeId, path, expectedTarget).toByteArray()

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
    scopeId: String?,
    workspaceRepository: WorkspaceRepository,
    target: WorkspaceToolTarget,
) = Tool(
    name = "workspace_publish_files",
    workspaceTarget = target.snapshot,
    description = "Publish one or more existing user-facing files so the app can show them as " +
        "previewable artifacts below the response. Use this after workspace_shell creates files " +
        "such as reports, text/code, images, PDFs, documents, archives, audio, or video. " +
        if (target.workspace.isRemote) "Paths must be absolute /workspace paths or real paths under '${target.root}' on the remote host."
        else "Paths must be absolute Rootfs paths.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("paths", buildJsonObject {
                    put("type", "array")
                    put("description", if (target.workspace.isRemote) {
                        "Absolute /workspace paths or real paths below '${target.root}' on the remote host"
                    } else "Absolute Rootfs paths of user-facing output files")
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
            val size = workspaceRepository.rootfsFileSize(
                workspaceId, path.value, scopeId, expectedTarget = target.snapshot,
            )
            WorkspaceArtifact(
                workspaceId = workspaceId,
                scopeId = scopeId,
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

/** Converts only the real `/workspace` guest mount to a relative cwd; other absolutes fail later. */
internal fun String.toWorkspaceRelativeCwd(): String = when {
    this == "/workspace" -> ""
    startsWith("/workspace/") -> removePrefix("/workspace/")
    else -> this
}

private fun JsonObjectBuilder.putPathProperty(required: Boolean, target: WorkspaceToolTarget? = null) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (target?.workspace?.isRemote == true) {
                "Absolute virtual /workspace path mapped to remote directory '${target.root}', or an absolute real path within that directory."
            } else if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private const val MAX_PUBLISHED_ARTIFACTS = 20
