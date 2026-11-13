package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import me.ayuilos.miffan.data.ai.tools.local.validCalendarCreateInput
import me.ayuilos.miffan.data.appsettings.SECRET_CARD_TOOLS
import me.rerere.ai.ui.UIMessagePart

/**
 * Primitive kinds supported for inline parameter editing on tool approval cards.
 */
enum class EditableParamKind {
    TEXT,
    MULTILINE_TEXT,
    BOOLEAN,
    INTEGER,
    NUMBER,
    DATETIME,
    WORKSPACE_PATH,
}

/**
 * Declarative specification of a single tool parameter that the owner may tweak inline
 * on an approval card before approving the tool call.
 */
data class EditableParamSpec(
    val key: String,
    val kind: EditableParamKind,
    val required: Boolean = true,
)

private val NON_EDITABLE_APPROVAL_TOOLS = setOf(
    WORKSPACE_SHELL_TOOL_NAME,
    WORKSPACE_TERMINAL_TOOL_NAME,
    REQUEST_ABILITY,
    "request_web_search",
    "extensions_apply_changes",
    "workspace_edit_file",
    "workspace_read_file",
    "ask_user",
)

private val LOCKED_MCP_PARAM_KEYS = setOf(
    "id",
    "assistantid",
    "assistant_id",
    "chat_id",
    "conversation_id",
    "partner_id",
    "provider_id",
    "service_id",
    "workspace_id",
    "resource_id",
    "preview_id",
    "previewid",
    "url",
    "uri",
    "host",
    "endpoint",
    "token",
    "key",
    "api_key",
    "secret",
    "password",
    "action",
    "type",
    "method",
)

/**
 * Returns the list of parameters that can be safely edited inline on the approval card
 * for [toolName] given its [originalInput].
 *
 * Security-sensitive tools (secret cards, shell commands, computer use, ability/permission
 * grants, and diff-based file edits) return an empty list or keep their target/host fields locked.
 */
fun editableApprovalFields(toolName: String, originalInput: JsonObject): List<EditableParamSpec> {
    if (toolName in SECRET_CARD_TOOLS ||
        toolName in NON_EDITABLE_APPROVAL_TOOLS ||
        toolName.startsWith(COMPUTER_TOOL_PREFIX)
    ) {
        return emptyList()
    }

    return when (toolName) {
        "calendar_create" -> listOf(
            EditableParamSpec("title", EditableParamKind.TEXT, required = true),
            EditableParamSpec("all_day", EditableParamKind.BOOLEAN, required = false),
            EditableParamSpec("start", EditableParamKind.DATETIME, required = true),
            EditableParamSpec("end", EditableParamKind.DATETIME, required = false),
            EditableParamSpec("location", EditableParamKind.TEXT, required = false),
            EditableParamSpec("description", EditableParamKind.MULTILINE_TEXT, required = false),
        )

        "workspace_write_file" -> listOf(
            EditableParamSpec("path", EditableParamKind.WORKSPACE_PATH, required = true),
            EditableParamSpec("text", EditableParamKind.MULTILINE_TEXT, required = false),
        )

        "workspace_fetch_url" -> buildList {
            // Lock `url` so the approved source host cannot be changed; allow tweaking destination_path.
            if ("destination_path" in originalInput) {
                add(EditableParamSpec("destination_path", EditableParamKind.WORKSPACE_PATH, required = true))
            }
        }

        else -> if (toolName.startsWith("mcp__")) {
            originalInput.entries
                .mapNotNull { (key, value) ->
                    val primitive = value as? JsonPrimitive ?: return@mapNotNull null
                    if (isLockedMcpKey(key)) return@mapNotNull null
                    val kind = inferEditableKind(primitive) ?: return@mapNotNull null
                    EditableParamSpec(key = key, kind = kind, required = false)
                }
                .take(6)
        } else {
            emptyList()
        }
    }
}

private fun isLockedMcpKey(key: String): Boolean {
    val lower = key.lowercase()
    return lower in LOCKED_MCP_PARAM_KEYS ||
        lower.endsWith("_id") ||
        lower.endsWith("id") && lower != "valid"
}

private fun inferEditableKind(primitive: JsonPrimitive): EditableParamKind? {
    if (!primitive.isString) {
        if (primitive.booleanOrNull != null) return EditableParamKind.BOOLEAN
        if (primitive.longOrNull != null) return EditableParamKind.INTEGER
        if (primitive.doubleOrNull != null) return EditableParamKind.NUMBER
        return null
    }
    val text = primitive.contentOrNull ?: return null
    return if (text.contains('\n') || text.length > 80) {
        EditableParamKind.MULTILINE_TEXT
    } else {
        EditableParamKind.TEXT
    }
}

/**
 * Validates whether [edited] is a safe, schema-compliant modification of [original] for [toolName].
 */
fun isValidEditedToolInput(
    toolName: String,
    original: JsonElement,
    edited: JsonObject,
): Boolean {
    if (toolName == "calendar_create") {
        return validCalendarCreateInput(edited)
    }
    if (isCardSwitchEdit(toolName, original, edited)) {
        return true
    }
    val originalObj = original as? JsonObject ?: return false
    val specs = editableApprovalFields(toolName, originalObj)
    if (specs.isEmpty()) return false

    val specByKey = specs.associateBy { it.key }
    val allKeys = originalObj.keys + edited.keys

    for (key in allKeys) {
        val spec = specByKey[key]
        if (spec == null) {
            // Non-editable keys must remain strictly identical to the original tool call input.
            if (originalObj[key] != edited[key]) return false
        } else {
            val newVal = edited[key]
            if (newVal == null) {
                if (spec.required) return false
                continue
            }
            val primitive = newVal as? JsonPrimitive ?: return false
            if (!isValidPrimitiveForSpec(primitive, spec)) return false
        }
    }
    return true
}

private fun isValidPrimitiveForSpec(primitive: JsonPrimitive, spec: EditableParamSpec): Boolean {
    return when (spec.kind) {
        EditableParamKind.TEXT -> {
            val str = primitive.contentOrNull ?: return false
            !spec.required || str.isNotBlank()
        }
        EditableParamKind.MULTILINE_TEXT -> {
            val str = primitive.contentOrNull ?: return false
            !spec.required || str.isNotBlank()
        }
        EditableParamKind.BOOLEAN -> {
            !primitive.isString && primitive.booleanOrNull != null
        }
        EditableParamKind.INTEGER -> {
            !primitive.isString && primitive.longOrNull != null
        }
        EditableParamKind.NUMBER -> {
            !primitive.isString && (primitive.doubleOrNull?.isFinite() == true)
        }
        EditableParamKind.DATETIME -> {
            val str = primitive.contentOrNull ?: return false
            !spec.required || str.isNotBlank()
        }
        EditableParamKind.WORKSPACE_PATH -> {
            val str = primitive.contentOrNull?.trim() ?: return false
            isSafeWorkspacePath(str)
        }
    }
}

internal fun isSafeWorkspacePath(path: String): Boolean {
    if (path.isBlank() || path.contains('\u0000')) return false
    val segments = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return false
    return segments.none { it == ".." }
}

const val CARD_EDITED_NOTE_METADATA_KEY = "miffan_card_edited_note"

/** True when the user changed this call's arguments on its approval card before allowing it. */
fun UIMessagePart.Tool.isEditedOnCard(): Boolean =
    (metadata?.get(CARD_EDITED_NOTE_METADATA_KEY) as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true

/**
 * Builds an explicit attribution note when the user edits tool arguments on an approval card
 * before approving, so the AI knows the user intentionally modified those parameters and
 * never mistakes the difference for its own error.
 */
fun buildCardEditAttributionNote(
    original: JsonElement,
    edited: JsonObject,
): String? {
    val originalObj = original as? JsonObject ?: return null
    if (originalObj == edited) return null

    val allKeys = (originalObj.keys + edited.keys).distinct()
    val changes = allKeys.mapNotNull { key ->
        val oldVal = originalObj[key]
        val newVal = edited[key]
        if (oldVal == newVal) return@mapNotNull null
        val oldPreview = formatArgPreview(oldVal)
        val newPreview = formatArgPreview(newVal)
        "changed \"$key\" from $oldPreview to $newPreview"
    }
    if (changes.isEmpty()) return null

    return "Before approving this tool call on the card, the user manually edited its parameters: " +
        "${changes.joinToString(", ")}. " +
        "Treat these updated values as the user's latest intent; do not treat the difference from earlier messages as an error or revert it."
}

private fun formatArgPreview(element: JsonElement?): String {
    if (element == null) return "<omitted>"
    val raw = (element as? JsonPrimitive)?.contentOrNull ?: element.toString()
    val compact = raw.replace("\n", "\\n")
    val truncated = if (compact.length > 100) compact.take(97) + "..." else compact
    return "\"$truncated\""
}

/**
 * Replaces the tool call's [UIMessagePart.Tool.input] with [editedInput] and records a
 * card-edit attribution note in [UIMessagePart.Tool.metadata] (preserving any existing provider metadata).
 */
fun UIMessagePart.Tool.withEditedCardInput(editedInput: JsonObject): UIMessagePart.Tool {
    val note = buildCardEditAttributionNote(inputAsJson(), editedInput)
    val updatedMetadata = if (note != null) {
        val base = metadata?.toMutableMap() ?: mutableMapOf()
        base[CARD_EDITED_NOTE_METADATA_KEY] = JsonPrimitive(note)
        JsonObject(base)
    } else {
        metadata
    }
    return copy(
        input = editedInput.toString(),
        metadata = updatedMetadata,
    )
}

/**
 * Attaches the card-edit attribution note (if present in [tool]'s metadata) to the tool execution [output]
 * so the model receives explicit confirmation that the user edited the card parameters before approving.
 */
fun appendCardEditNoteToToolOutput(
    tool: UIMessagePart.Tool,
    output: List<UIMessagePart>,
): List<UIMessagePart> {
    val note = (tool.metadata?.get(CARD_EDITED_NOTE_METADATA_KEY) as? JsonPrimitive)
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: return output

    val firstTextIdx = output.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIdx < 0) {
        return output + UIMessagePart.Text("[Approval Card Note] $note")
    }

    val firstTextPart = output[firstTextIdx] as UIMessagePart.Text
    val parsedJsonObj = runCatching {
        Json.parseToJsonElement(firstTextPart.text) as? JsonObject
    }.getOrNull()

    val updatedTextPart = if (parsedJsonObj != null) {
        val merged = parsedJsonObj.toMutableMap()
        merged["user_modified_on_card"] = JsonPrimitive(note)
        firstTextPart.copy(text = JsonObject(merged).toString())
    } else {
        firstTextPart.copy(text = "${firstTextPart.text}\n\n[Approval Card Note] $note")
    }

    return output.mapIndexed { idx, part ->
        if (idx == firstTextIdx) updatedTextPart else part
    }
}
