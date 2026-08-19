package me.ayuilos.miffan.data.extensions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import me.rerere.ai.core.MessageRole
import me.ayuilos.miffan.data.ai.mcp.McpServerConfig
import me.ayuilos.miffan.data.ai.tools.local.LocalToolOption
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.files.SkillManager
import me.ayuilos.miffan.data.files.SkillMetadata
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.InjectionPosition
import me.ayuilos.miffan.data.model.PromptInjection
import me.ayuilos.miffan.data.model.QuickMessage
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

/**
 * Safe domain boundary shared by AI tools and extension-management UI.
 *
 * It intentionally exposes no generic "replace settings" operation. Applying a preview revalidates
 * every reference against current state, then performs all settings changes in one [SettingsStore]
 * update so invalid or stale input cannot leave a partially changed configuration.
 */
class ExtensionManagementService(
    private val settingsStore: SettingsStore,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    private val pendingPreviews = ConcurrentHashMap<String, PendingExtensionPreview>()

    suspend fun catalog(): ExtensionCatalog {
        val settings = settingsStore.settingsFlow.value
        val skills = skillManager.listSkills()
        val workspaces = workspaceRepository.listFlow().first()
        return buildExtensionCatalog(settings, skills, workspaces)
    }

    suspend fun preview(changes: List<ExtensionChange>): ExtensionChangePreview {
        pruneExpiredPreviews()
        val settings = settingsStore.settingsFlow.value
        val resources = currentExternalResources()
        val processed = ExtensionChangeProcessor.process(
            settings = settings,
            changes = changes,
            resources = resources,
            allowGeneratedIds = true,
        )
        val previewId = if (processed.valid) {
            createExtensionPreviewId(
                nonce = Uuid.random().toString(),
                summaries = processed.summaries,
            )
        } else {
            null
        }
        val preview = processed.toPreview(previewId)
        if (previewId != null) {
            pendingPreviews[previewId] = PendingExtensionPreview(
                preview = preview,
                settings = settings,
                resources = resources,
                createdAtMillis = System.currentTimeMillis(),
            )
        }
        return preview
    }

    suspend fun apply(previewId: String): ExtensionApplyResult {
        pruneExpiredPreviews()
        val pending = pendingPreviews.remove(previewId)
            ?: return ExtensionApplyResult(
                applied = false,
                errors = listOf("Preview not found or expired; create a new preview"),
            )
        val preview = pending.preview
        if (!preview.valid || preview.errors.isNotEmpty()) {
            return ExtensionApplyResult(
                applied = false,
                errors = listOf("Cannot apply an invalid preview") + preview.errors,
            )
        }
        if (preview.changes.isEmpty()) {
            return ExtensionApplyResult(
                applied = false,
                errors = listOf("Cannot apply an empty preview"),
            )
        }

        val resources = currentExternalResources()
        if (resources != pending.resources || settingsStore.settingsFlow.value != pending.settings) {
            return ExtensionApplyResult(
                applied = false,
                errors = listOf("Extension settings changed after preview; create a new preview"),
            )
        }
        val initialValidation = ExtensionChangeProcessor.process(
            settings = pending.settings,
            changes = preview.changes,
            resources = resources,
            allowGeneratedIds = false,
        )
        if (!initialValidation.valid) {
            return ExtensionApplyResult(applied = false, errors = initialValidation.errors)
        }

        return try {
            val updated = settingsStore.updateIfCurrent(pending.settings) { currentSettings ->
                val currentValidation = ExtensionChangeProcessor.process(
                    settings = currentSettings,
                    changes = preview.changes,
                    resources = resources,
                    allowGeneratedIds = false,
                )
                if (!currentValidation.valid) {
                    throw ExtensionValidationException(currentValidation.errors)
                }
                currentValidation.settings
            }
            if (!updated) {
                return ExtensionApplyResult(
                    applied = false,
                    errors = listOf("Extension settings changed after preview; create a new preview"),
                )
            }
            ExtensionApplyResult(
                applied = true,
                operationCount = initialValidation.changes.size,
                summaries = initialValidation.summaries,
                takesEffectNextTurn = true,
            )
        } catch (error: ExtensionValidationException) {
            ExtensionApplyResult(applied = false, errors = error.validationErrors)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ExtensionApplyResult(
                applied = false,
                errors = listOf(error.message ?: "Failed to save extension settings"),
            )
        }
    }

    private suspend fun currentExternalResources(): ExternalResources = ExternalResources(
        skillNames = skillManager.listSkills().mapTo(hashSetOf()) { it.name },
        workspaceIds = workspaceRepository.listFlow().first().mapTo(hashSetOf()) { it.id },
    )

    private fun pruneExpiredPreviews() {
        val cutoff = System.currentTimeMillis() - PREVIEW_TTL_MILLIS
        pendingPreviews.entries.removeIf { it.value.createdAtMillis < cutoff }
        if (pendingPreviews.size > MAX_PENDING_PREVIEWS) {
            pendingPreviews.entries
                .sortedBy { it.value.createdAtMillis }
                .take(pendingPreviews.size - MAX_PENDING_PREVIEWS)
                .forEach { pendingPreviews.remove(it.key, it.value) }
        }
    }

    private companion object {
        const val PREVIEW_TTL_MILLIS = 10 * 60 * 1_000L
        const val MAX_PENDING_PREVIEWS = 32
    }
}

private data class PendingExtensionPreview(
    val preview: ExtensionChangePreview,
    val settings: Settings,
    val resources: ExternalResources,
    val createdAtMillis: Long,
)

private class ExtensionValidationException(
    val validationErrors: List<String>,
) : IllegalArgumentException(validationErrors.joinToString("; "))

internal data class ExternalResources(
    val skillNames: Set<String>,
    val workspaceIds: Set<String>,
)

internal data class ProcessedExtensionChanges(
    val settings: Settings,
    val changes: List<ExtensionChange>,
    val summaries: List<String>,
    val errors: List<String>,
) {
    val valid: Boolean get() = errors.isEmpty() && changes.isNotEmpty()

    fun toPreview(previewId: String? = null) = ExtensionChangePreview(
        previewId = previewId,
        valid = valid,
        changes = changes,
        summaries = summaries,
        errors = errors.ifEmpty {
            if (changes.isEmpty()) listOf("At least one change is required") else emptyList()
        },
    )
}

/** Pure validation/mutation engine, kept separate so the all-or-nothing rules are unit-testable. */
internal object ExtensionChangeProcessor {
    fun process(
        settings: Settings,
        changes: List<ExtensionChange>,
        resources: ExternalResources,
        allowGeneratedIds: Boolean,
    ): ProcessedExtensionChanges {
        if (changes.isEmpty()) {
            return ProcessedExtensionChanges(
                settings = settings,
                changes = emptyList(),
                summaries = emptyList(),
                errors = listOf("At least one change is required"),
            )
        }

        var candidate = settings
        val normalized = mutableListOf<ExtensionChange>()
        val summaries = mutableListOf<String>()
        val errors = mutableListOf<String>()

        changes.forEachIndexed { index, change ->
            val result = runCatching {
                applyOne(candidate, change, resources, allowGeneratedIds)
            }
            result.onSuccess { operation ->
                candidate = operation.settings
                normalized += operation.change
                summaries += operation.summary
            }.onFailure { error ->
                errors += "Change ${index + 1}: ${error.message ?: "invalid change"}"
            }
        }

        return ProcessedExtensionChanges(
            settings = if (errors.isEmpty()) candidate else settings,
            changes = normalized,
            summaries = summaries,
            errors = errors,
        )
    }

    private fun applyOne(
        settings: Settings,
        change: ExtensionChange,
        resources: ExternalResources,
        allowGeneratedIds: Boolean,
    ): AppliedOperation = when (change) {
        is ExtensionChange.UpsertQuickMessage -> {
            val title = change.title.trim().requireNotBlank("Quick message title")
            val content = change.content.trim().requireNotBlank("Quick message content")
            val id = resolveUpsertId(
                rawId = change.id,
                create = change.create,
                existingIds = settings.quickMessages.mapTo(hashSetOf()) { it.id },
                resourceLabel = "quick message",
                allowGeneratedIds = allowGeneratedIds,
            )
            val exists = settings.quickMessages.any { it.id == id }
            val item = QuickMessage(id = id, title = title, content = content)
            val updatedItems = if (exists) {
                settings.quickMessages.map { if (it.id == id) item else it }
            } else {
                settings.quickMessages + item
            }
            AppliedOperation(
                settings = settings.copy(quickMessages = updatedItems),
                change = change.copy(
                    id = id.toString(),
                    title = title,
                    content = content,
                    create = !exists,
                ),
                summary = "${if (exists) "Update" else "Create"} quick message '$title' (${id}); " +
                    "content ${content.previewExcerpt()}",
            )
        }

        is ExtensionChange.UpsertModeInjection -> {
            val name = change.name.trim().requireNotBlank("Mode injection name")
            val content = change.content.trim().requireNotBlank("Mode injection content")
            require(change.injectDepth >= 0) { "Mode injection depth must be zero or greater" }
            val position = parseInjectionPosition(change.position)
            val role = parseInjectionRole(change.role)
            val id = resolveUpsertId(
                rawId = change.id,
                create = change.create,
                existingIds = settings.modeInjections.mapTo(hashSetOf()) { it.id },
                resourceLabel = "mode injection",
                allowGeneratedIds = allowGeneratedIds,
            )
            val exists = settings.modeInjections.any { it.id == id }
            val item = PromptInjection.ModeInjection(
                id = id,
                name = name,
                enabled = change.enabled,
                priority = change.priority,
                position = position,
                content = content,
                injectDepth = change.injectDepth,
                role = role,
            )
            val updatedItems = if (exists) {
                settings.modeInjections.map { if (it.id == id) item else it }
            } else {
                settings.modeInjections + item
            }
            AppliedOperation(
                settings = settings.copy(modeInjections = updatedItems),
                change = change.copy(
                    id = id.toString(),
                    name = name,
                    position = position.catalogId(),
                    content = content,
                    role = role.catalogId(),
                    create = !exists,
                ),
                summary = "${if (exists) "Update" else "Create"} mode injection '$name' (${id}); " +
                    "content ${content.previewExcerpt()}",
            )
        }

        is ExtensionChange.SetResourceBinding -> {
            val assistantId = parseUuid(change.assistantId, "assistant id")
            val assistant = settings.requireAssistant(assistantId)
            val resourceId = change.resourceId.trim().requireNotBlank("Resource id")
            val normalizedResourceId = validateResourceReference(
                settings = settings,
                type = change.resourceType,
                rawId = resourceId,
                resources = resources,
            )
            val updatedAssistant = assistant.withResourceBinding(
                type = change.resourceType,
                resourceId = normalizedResourceId,
                enabled = change.enabled,
            )
            AppliedOperation(
                settings = settings.replaceAssistant(updatedAssistant),
                change = change.copy(
                    assistantId = assistantId.toString(),
                    resourceId = normalizedResourceId,
                ),
                summary = "${if (change.enabled) "Bind" else "Unbind"} " +
                    "${change.resourceType.catalogId()} '$normalizedResourceId' " +
                    "${if (change.enabled) "to" else "from"} assistant '${assistant.displayName()}'",
            )
        }

        is ExtensionChange.SetWorkspace -> {
            val assistantId = parseUuid(change.assistantId, "assistant id")
            val assistant = settings.requireAssistant(assistantId)
            val workspaceId = change.workspaceId?.let { rawId ->
                val parsed = parseUuid(rawId, "workspace id").toString()
                require(parsed in resources.workspaceIds) { "Workspace not found: $parsed" }
                parsed
            }
            val updatedAssistant = assistant.copy(workspaceId = workspaceId?.let(Uuid::parse))
            AppliedOperation(
                settings = settings.replaceAssistant(updatedAssistant),
                change = change.copy(
                    assistantId = assistantId.toString(),
                    workspaceId = workspaceId,
                ),
                summary = if (workspaceId == null) {
                    "Clear workspace from assistant '${assistant.displayName()}'"
                } else {
                    "Set workspace '$workspaceId' on assistant '${assistant.displayName()}'"
                },
            )
        }

        is ExtensionChange.SetLocalTool -> {
            val assistantId = parseUuid(change.assistantId, "assistant id")
            val assistant = settings.requireAssistant(assistantId)
            val definition = localToolDefinitions.firstOrNull {
                it.id == change.localToolId.trim().lowercase()
            } ?: throw IllegalArgumentException("Unknown local tool: ${change.localToolId}")
            require(definition.mutable) {
                "Local tool '${definition.id}' can only be changed directly by the user"
            }
            val updatedTools = if (change.enabled) {
                if (definition.option in assistant.localTools) assistant.localTools
                else assistant.localTools + definition.option
            } else {
                assistant.localTools.filterNot { it == definition.option }
            }
            AppliedOperation(
                settings = settings.replaceAssistant(assistant.copy(localTools = updatedTools)),
                change = change.copy(
                    assistantId = assistantId.toString(),
                    localToolId = definition.id,
                ),
                summary = "${if (change.enabled) "Enable" else "Disable"} local tool " +
                    "'${definition.id}' for assistant '${assistant.displayName()}'",
            )
        }

        is ExtensionChange.SetExternalWebSearch -> {
            val assistantId = parseUuid(change.assistantId, "assistant id")
            val assistant = settings.requireAssistant(assistantId)
            AppliedOperation(
                settings = settings.replaceAssistant(
                    assistant.copy(enableWebSearch = change.enabled)
                ),
                change = change.copy(assistantId = assistantId.toString()),
                summary = "${if (change.enabled) "Enable" else "Disable"} external web search " +
                    "for assistant '${assistant.displayName()}'",
            )
        }
    }

    private fun validateResourceReference(
        settings: Settings,
        type: ExtensionResourceType,
        rawId: String,
        resources: ExternalResources,
    ): String = when (type) {
        ExtensionResourceType.SKILL -> rawId.also {
            require(it in resources.skillNames) { "Skill not found: $it" }
        }

        ExtensionResourceType.QUICK_MESSAGE -> parseUuid(rawId, "quick message id").also { id ->
            require(settings.quickMessages.any { it.id == id }) { "Quick message not found: $id" }
        }.toString()

        ExtensionResourceType.MODE_INJECTION -> parseUuid(rawId, "mode injection id").also { id ->
            require(settings.modeInjections.any { it.id == id }) { "Mode injection not found: $id" }
        }.toString()

        ExtensionResourceType.LOREBOOK -> parseUuid(rawId, "lorebook id").also { id ->
            require(settings.lorebooks.any { it.id == id }) { "Lorebook not found: $id" }
        }.toString()

        ExtensionResourceType.MCP -> parseUuid(rawId, "MCP server id").also { id ->
            require(settings.mcpServers.any { it.id == id }) { "MCP server not found: $id" }
        }.toString()
    }

    private fun Assistant.withResourceBinding(
        type: ExtensionResourceType,
        resourceId: String,
        enabled: Boolean,
    ): Assistant = when (type) {
        ExtensionResourceType.SKILL -> copy(
            enabledSkills = enabledSkills.withMembership(resourceId, enabled)
        )

        ExtensionResourceType.QUICK_MESSAGE -> copy(
            quickMessageIds = quickMessageIds.withMembership(Uuid.parse(resourceId), enabled)
        )

        ExtensionResourceType.MODE_INJECTION -> copy(
            modeInjectionIds = modeInjectionIds.withMembership(Uuid.parse(resourceId), enabled)
        )

        ExtensionResourceType.LOREBOOK -> copy(
            lorebookIds = lorebookIds.withMembership(Uuid.parse(resourceId), enabled)
        )

        ExtensionResourceType.MCP -> copy(
            mcpServers = mcpServers.withMembership(Uuid.parse(resourceId), enabled)
        )
    }
}

private data class AppliedOperation(
    val settings: Settings,
    val change: ExtensionChange,
    val summary: String,
)

internal fun buildExtensionCatalog(
    settings: Settings,
    skills: List<SkillMetadata>,
    workspaces: List<WorkspaceEntity>,
): ExtensionCatalog = ExtensionCatalog(
    assistants = settings.assistants.map { assistant ->
        ExtensionAssistantCatalogEntry(
            id = assistant.id.toString(),
            name = assistant.name,
            quickMessageIds = assistant.quickMessageIds.map { it.toString() }.sorted(),
            modeInjectionIds = assistant.modeInjectionIds.map { it.toString() }.sorted(),
            lorebookIds = assistant.lorebookIds.map { it.toString() }.sorted(),
            skillNames = assistant.enabledSkills.sorted(),
            mcpServerIds = assistant.mcpServers.map { it.toString() }.sorted(),
            localToolIds = assistant.localTools.mapNotNull(::localToolId).sorted(),
            externalWebSearchEnabled = assistant.enableWebSearch,
            workspaceId = assistant.workspaceId?.toString(),
        )
    },
    quickMessages = settings.quickMessages.map { item ->
        QuickMessageCatalogEntry(
            id = item.id.toString(),
            title = item.title,
        )
    },
    modeInjections = settings.modeInjections.map { item ->
        ModeInjectionCatalogEntry(
            id = item.id.toString(),
            name = item.name,
            enabled = item.enabled,
            priority = item.priority,
            position = item.position.catalogId(),
            injectDepth = item.injectDepth,
            role = item.role.catalogId(),
        )
    },
    lorebooks = settings.lorebooks.map { item ->
        LorebookCatalogEntry(
            id = item.id.toString(),
            name = item.name,
            enabled = item.enabled,
            entryCount = item.entries.size,
        )
    },
    skills = skills.sortedBy { it.name }.map { skill ->
        SkillCatalogEntry(
            name = skill.name,
            compatibility = skill.compatibility,
        )
    },
    mcpServers = settings.mcpServers.map { server ->
        val options = server.commonOptions
        McpServerCatalogEntry(
            id = server.id.toString(),
            name = options.name,
            enabled = options.enable,
            transport = when (server) {
                is McpServerConfig.SseTransportServer -> "sse"
                is McpServerConfig.StreamableHTTPServer -> "streamable_http"
            },
            toolCount = options.tools.size,
            hasCustomHeaders = options.headers.isNotEmpty(),
            oauthEnabled = options.oauth?.enabled == true,
            oauthAuthorized = options.oauth?.isAuthorized == true,
        )
    },
    localTools = localToolDefinitions.map { definition ->
        LocalToolCatalogEntry(
            id = definition.id,
            mutableByExtensionManagement = definition.mutable,
        )
    },
    workspaces = workspaces.map { workspace ->
        WorkspaceCatalogEntry(
            id = workspace.id,
            name = workspace.name,
            shellStatus = workspace.shellStatus,
        )
    },
)

private data class LocalToolDefinition(
    val id: String,
    val option: LocalToolOption,
    val mutable: Boolean = true,
)

private val localToolDefinitions = listOf(
    LocalToolDefinition("javascript_engine", LocalToolOption.JavascriptEngine),
    LocalToolDefinition("time_info", LocalToolOption.TimeInfo),
    LocalToolDefinition("clipboard", LocalToolOption.Clipboard),
    LocalToolDefinition("tts", LocalToolOption.Tts),
    LocalToolDefinition("ask_user", LocalToolOption.AskUser),
    LocalToolDefinition("screen_time", LocalToolOption.ScreenTime),
    LocalToolDefinition("calendar", LocalToolOption.Calendar),
    LocalToolDefinition(
        id = "extension_management",
        option = LocalToolOption.ExtensionManagement,
        mutable = false,
    ),
)

private fun localToolId(option: LocalToolOption): String? =
    localToolDefinitions.firstOrNull { it.option == option }?.id

private fun resolveUpsertId(
    rawId: String?,
    create: Boolean,
    existingIds: Set<Uuid>,
    resourceLabel: String,
    allowGeneratedIds: Boolean,
): Uuid {
    if (rawId == null) {
        require(allowGeneratedIds) { "New $resourceLabel must be previewed before it is applied" }
        require(create) { "New $resourceLabel must use create=true" }
        return Uuid.random()
    }
    val id = parseUuid(rawId, "$resourceLabel id")
    if (create) {
        require(!allowGeneratedIds) {
            "New $resourceLabel must omit id when requesting a preview"
        }
        require(id !in existingIds) {
            "${resourceLabel.replaceFirstChar { it.uppercase() }} already exists: $id"
        }
    } else {
        require(id in existingIds) {
            "${resourceLabel.replaceFirstChar { it.uppercase() }} not found: $id"
        }
    }
    return id
}

private fun parseUuid(value: String, label: String): Uuid = runCatching {
    Uuid.parse(value.trim())
}.getOrElse {
    throw IllegalArgumentException("Invalid $label: $value")
}

private fun parseInjectionPosition(value: String): InjectionPosition {
    val normalized = value.trim().lowercase()
    return InjectionPosition.entries.firstOrNull {
        it.catalogId() == normalized || it.name.lowercase() == normalized
    } ?: throw IllegalArgumentException(
        "Unknown injection position '$value'; expected " +
            InjectionPosition.entries.joinToString { it.catalogId() }
    )
}

private fun parseInjectionRole(value: String): MessageRole = when (value.trim().lowercase()) {
    "user" -> MessageRole.USER
    "assistant" -> MessageRole.ASSISTANT
    else -> throw IllegalArgumentException(
        "Unknown injection role '$value'; expected user or assistant"
    )
}

private fun InjectionPosition.catalogId(): String = when (this) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> "before_system_prompt"
    InjectionPosition.AFTER_SYSTEM_PROMPT -> "after_system_prompt"
    InjectionPosition.TOP_OF_CHAT -> "top_of_chat"
    InjectionPosition.BOTTOM_OF_CHAT -> "bottom_of_chat"
    InjectionPosition.AT_DEPTH -> "at_depth"
}

private fun MessageRole.catalogId(): String = name.lowercase()

private fun ExtensionResourceType.catalogId(): String = when (this) {
    ExtensionResourceType.QUICK_MESSAGE -> "quick_message"
    ExtensionResourceType.MODE_INJECTION -> "mode_injection"
    ExtensionResourceType.LOREBOOK -> "lorebook"
    ExtensionResourceType.SKILL -> "skill"
    ExtensionResourceType.MCP -> "mcp"
}

private fun Settings.requireAssistant(id: Uuid): Assistant =
    assistants.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Assistant not found: $id")

private fun Settings.replaceAssistant(updated: Assistant): Settings = copy(
    assistants = assistants.map { assistant ->
        if (assistant.id == updated.id) updated else assistant
    }
)

private fun Assistant.displayName(): String = name.ifBlank { id.toString() }

private fun String.requireNotBlank(label: String): String = also {
    require(it.isNotBlank()) { "$label must not be blank" }
}

private fun String.previewExcerpt(maxLength: Int = 160): String {
    val singleLine = replace(Regex("\\s+"), " ")
    return if (singleLine.length <= maxLength) {
        "\"$singleLine\""
    } else {
        "\"${singleLine.take(maxLength)}…\""
    }
}

private fun <T> Set<T>.withMembership(value: T, enabled: Boolean): Set<T> =
    if (enabled) this + value else this - value
