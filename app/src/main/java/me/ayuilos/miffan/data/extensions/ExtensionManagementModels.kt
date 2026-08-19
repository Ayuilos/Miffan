package me.ayuilos.miffan.data.extensions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A credential-redacted snapshot that omits prompt bodies and connection details. */
@Serializable
data class ExtensionCatalog(
    val assistants: List<ExtensionAssistantCatalogEntry>,
    val quickMessages: List<QuickMessageCatalogEntry>,
    val modeInjections: List<ModeInjectionCatalogEntry>,
    val lorebooks: List<LorebookCatalogEntry>,
    val skills: List<SkillCatalogEntry>,
    val mcpServers: List<McpServerCatalogEntry>,
    val localTools: List<LocalToolCatalogEntry>,
    val workspaces: List<WorkspaceCatalogEntry>,
)

@Serializable
data class ExtensionAssistantCatalogEntry(
    val id: String,
    val name: String,
    val quickMessageIds: List<String>,
    val modeInjectionIds: List<String>,
    val lorebookIds: List<String>,
    val skillNames: List<String>,
    val mcpServerIds: List<String>,
    val localToolIds: List<String>,
    val externalWebSearchEnabled: Boolean,
    val workspaceId: String? = null,
)

@Serializable
data class QuickMessageCatalogEntry(
    val id: String,
    val title: String,
)

@Serializable
data class ModeInjectionCatalogEntry(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val position: String,
    val injectDepth: Int,
    val role: String,
)

@Serializable
data class LorebookCatalogEntry(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val entryCount: Int,
)

@Serializable
data class SkillCatalogEntry(
    val name: String,
    val compatibility: String? = null,
)

/**
 * Deliberately excludes URLs, header names/values and all OAuth fields. Even apparently harmless
 * connection fields can contain embedded credentials or query-string API keys.
 */
@Serializable
data class McpServerCatalogEntry(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val transport: String,
    val toolCount: Int,
    val hasCustomHeaders: Boolean,
    val oauthEnabled: Boolean,
    val oauthAuthorized: Boolean,
)

@Serializable
data class LocalToolCatalogEntry(
    val id: String,
    /** False for the extension-management capability itself, which must remain user-controlled. */
    val mutableByExtensionManagement: Boolean,
)

@Serializable
data class WorkspaceCatalogEntry(
    val id: String,
    val name: String,
    val shellStatus: String,
)

@Serializable
enum class ExtensionResourceType {
    @SerialName("quick_message")
    QUICK_MESSAGE,

    @SerialName("mode_injection")
    MODE_INJECTION,

    @SerialName("lorebook")
    LOREBOOK,

    @SerialName("skill")
    SKILL,

    @SerialName("mcp")
    MCP,
}

/** Narrow, typed settings operations accepted by the safe MVP. */
@Serializable
sealed class ExtensionChange {
    @Serializable
    @SerialName("upsert_quick_message")
    data class UpsertQuickMessage(
        /** Null creates a resource; a non-null id must identify an existing resource. */
        val id: String? = null,
        val title: String,
        val content: String,
        /** Set by preview normalization; callers normally leave this at its default. */
        val create: Boolean = id == null,
    ) : ExtensionChange()

    @Serializable
    @SerialName("upsert_mode_injection")
    data class UpsertModeInjection(
        /** Null creates a resource; a non-null id must identify an existing resource. */
        val id: String? = null,
        val name: String,
        val enabled: Boolean = true,
        val priority: Int = 0,
        /** Serialized InjectionPosition name, for example `after_system_prompt`. */
        val position: String = "after_system_prompt",
        val content: String,
        val injectDepth: Int = 4,
        /** Only `user` and `assistant` are valid for a mode injection. */
        val role: String = "user",
        /** Set by preview normalization; callers normally leave this at its default. */
        val create: Boolean = id == null,
    ) : ExtensionChange()

    @Serializable
    @SerialName("set_resource_binding")
    data class SetResourceBinding(
        val assistantId: String,
        val resourceType: ExtensionResourceType,
        /** UUID for settings resources; the stable skill name for a skill. */
        val resourceId: String,
        val enabled: Boolean,
    ) : ExtensionChange()

    @Serializable
    @SerialName("set_workspace")
    data class SetWorkspace(
        val assistantId: String,
        /** Null clears the current workspace. */
        val workspaceId: String? = null,
    ) : ExtensionChange()

    @Serializable
    @SerialName("set_local_tool")
    data class SetLocalTool(
        val assistantId: String,
        /** Stable snake_case local-tool identifier from [ExtensionCatalog.localTools]. */
        val localToolId: String,
        val enabled: Boolean,
    ) : ExtensionChange()

    @Serializable
    @SerialName("set_external_web_search")
    data class SetExternalWebSearch(
        val assistantId: String,
        val enabled: Boolean,
    ) : ExtensionChange()
}

@Serializable
data class ExtensionChangePreview(
    /** Opaque id issued by the service for a valid, normalized preview. */
    val previewId: String? = null,
    val valid: Boolean,
    /** Normalized operations, including generated ids for newly created resources. */
    val changes: List<ExtensionChange>,
    val summaries: List<String>,
    val errors: List<String> = emptyList(),
)

@Serializable
data class ExtensionApplyResult(
    val applied: Boolean,
    val operationCount: Int = 0,
    val summaries: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    /** Tool availability/configuration is snapshotted for a generation. */
    val takesEffectNextTurn: Boolean = false,
)

/**
 * Self-contained display data bound to a server-side, one-use preview capability.
 *
 * The complete serialized value is the cache key. Changing a summary therefore changes the key and
 * cannot authorize the cached preview, while approval UI can still render the authoritative summary
 * without trusting a second model-provided field.
 */
@Serializable
private data class ExtensionPreviewCapability(
    val nonce: String,
    val summaries: List<String>,
)

internal fun createExtensionPreviewId(nonce: String, summaries: List<String>): String =
    Json.encodeToString(ExtensionPreviewCapability(nonce = nonce, summaries = summaries))

fun decodeExtensionPreviewSummaries(previewId: String): List<String> = runCatching {
    Json.decodeFromString<ExtensionPreviewCapability>(previewId).summaries
}.getOrDefault(emptyList())
