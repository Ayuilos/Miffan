package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.extensions.ExtensionChange
import me.rerere.rikkahub.data.extensions.ExtensionManagementService
import me.rerere.rikkahub.utils.JsonInstant

fun createExtensionManagementTools(
    service: ExtensionManagementService,
    json: Json = JsonInstant,
): List<Tool> = listOf(
    Tool(
        name = "extensions_catalog",
        description = """
            Return a credential-redacted catalog of assistants and extension resources that can be managed.
            Prompt bodies and MCP connection fields are omitted.
            Inspect this catalog before proposing changes so you use stable resource identifiers.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {})
        },
        execute = {
            val catalog = service.catalog()
            listOf(UIMessagePart.Text(json.encodeToString(catalog)))
        },
    ),
    Tool(
        name = "extensions_preview_changes",
        description = """
            Validate and normalize proposed extension configuration changes without writing anything.
            Always call extensions_catalog first. Show the returned summaries to the user before asking
            to apply a valid preview.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("changes", buildJsonObject {
                        put("type", "array")
                        put("description", "Ordered extension changes to validate. See the extension-management skill for operation fields.")
                        put("items", extensionChangeSchema())
                    })
                },
                required = listOf("changes"),
            )
        },
        execute = { input ->
            val changesElement = input.jsonObject["changes"] ?: error("changes is required")
            require(changesElement is JsonArray) { "changes must be an array" }
            val changes = json.decodeFromJsonElement(ListSerializer(ExtensionChange.serializer()), changesElement)
            val preview = service.preview(changes)
            listOf(UIMessagePart.Text(json.encodeToString(preview)))
        },
    ),
    Tool(
        name = "extensions_apply_changes",
        description = """
            Apply a valid server-issued preview by its opaque previewId. The canonical preview is
            revalidated against current settings before one atomic settings update. This operation
            always requires explicit user approval. Never invent or alter a previewId.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("previewId", stringSchema("Opaque previewId returned by extensions_preview_changes."))
                },
                required = listOf("previewId"),
            )
        },
        needsApproval = { true },
        execute = { input ->
            val previewId = input.jsonObject["previewId"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?: error("previewId is required")
            val result = service.apply(previewId)
            listOf(UIMessagePart.Text(json.encodeToString(result)))
        },
    ),
)

private fun extensionChangeSchema() = buildJsonObject {
    put("type", "object")
    put(
        "description",
        "A typed ExtensionChange. Fields not used by the selected type must be omitted. " +
            "Required fields by type: upsert_quick_message = title, content; " +
            "upsert_mode_injection = name, content; set_resource_binding = assistantId, " +
            "resourceType, resourceId, enabled; set_workspace = assistantId (omit workspaceId " +
            "to clear); set_local_tool = assistantId, localToolId, enabled; " +
            "set_external_web_search = assistantId, enabled."
    )
    put("properties", buildJsonObject {
        put("type", enumStringSchema("Operation type",
            "upsert_quick_message",
            "upsert_mode_injection",
            "set_resource_binding",
            "set_workspace",
            "set_local_tool",
            "set_external_web_search",
        ))
        put("id", stringSchema("Existing resource UUID; omit to create."))
        put("title", stringSchema("Quick-message title."))
        put("content", stringSchema("Quick-message or mode-injection content."))
        put("name", stringSchema("Mode-injection name."))
        put("enabled", buildJsonObject { put("type", "boolean") })
        put("priority", buildJsonObject { put("type", "integer") })
        put("position", enumStringSchema("Mode-injection position",
            "before_system_prompt",
            "after_system_prompt",
            "top_of_chat",
            "bottom_of_chat",
            "at_depth",
        ))
        put("injectDepth", buildJsonObject { put("type", "integer") })
        put("role", enumStringSchema("Injected message role", "user", "assistant"))
        put("assistantId", stringSchema("Target assistant UUID."))
        put("resourceType", enumStringSchema(
            "Resource type",
            "quick_message",
            "mode_injection",
            "lorebook",
            "skill",
            "mcp",
        ))
        put("resourceId", stringSchema("Resource UUID, or stable skill name for a skill."))
        put("workspaceId", stringSchema("Workspace UUID. Omit this optional field to clear the binding."))
        put("localToolId", stringSchema("Stable local-tool id from extensions_catalog."))
    })
    put("required", buildJsonArray { add("type") })
}

private fun stringSchema(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun enumStringSchema(description: String, vararg values: String) = buildJsonObject {
    put("type", "string")
    put("description", "$description. Allowed values: ${values.joinToString()}.")
    put("enum", buildJsonArray { values.forEach { add(it) } })
}
