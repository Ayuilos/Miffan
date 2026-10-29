package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.ayuilos.miffan.data.ai.computer.CuaSpaceException
import me.ayuilos.miffan.data.ai.computer.PartnerSpaces
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot

const val SPACE_HANDOFF_TOOL = "give_to_owner"

/**
 * The path inside the Shared folder that [path] names: relative to it, or absolute under it (as the Space
 * sees it, with `~`, or through the file tools' `/workspace`). Null for anything outside it.
 */
internal fun spaceSharedPath(path: String): String? {
    val shared = PartnerSpaces.SHARED_DIR
    val trimmed = path.trim().trimEnd('/')
    val inside = when {
        trimmed == shared || trimmed == "~/Desktop/Shared" || trimmed == "/workspace/Desktop/Shared" -> return null
        trimmed.startsWith("$shared/") -> trimmed.removePrefix("$shared/")
        trimmed.startsWith("~/Desktop/Shared/") -> trimmed.removePrefix("~/Desktop/Shared/")
        trimmed.startsWith("/workspace/Desktop/Shared/") -> trimmed.removePrefix("/workspace/Desktop/Shared/")
        trimmed.startsWith("/") || trimmed.startsWith("~") -> return null
        else -> trimmed
    }
    val parts = inside.split('/')
    if (inside.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." }) return null
    if (inside.any { it == '\u0000' || it == '\n' || it == '\r' }) return null
    return inside
}

/**
 * Hands a file or folder from the partner's own computer to the owner: the helper copies it from the
 * Space's Shared folder into the Space's handoff folder on [computerName], the only place there the
 * partner can write to. Nothing is overwritten, so it runs without asking.
 */
fun createSpaceHandoffTool(
    workspaceId: String,
    computerName: String,
    handoffFolder: String,
    snapshot: WorkspaceToolTargetSnapshot?,
    spaces: PartnerSpaces,
): Tool = Tool(
    name = SPACE_HANDOFF_TOOL,
    description = "Give the owner a file or folder from your own computer: it is copied from your Shared folder " +
        "(`${PartnerSpaces.SHARED_DIR}`, on your desktop) into `~/$handoffFolder` on $computerName, where the owner " +
        "opens it. Move or copy the result into the Shared folder first. Files already there keep their name; " +
        "yours gets \" (2)\" added. Tell the owner where it landed.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "File or folder inside the Shared folder, e.g. `report.pdf` or " +
                        "`${PartnerSpaces.SHARED_DIR}/photos`.")
                }
            },
            required = listOf("path"),
        )
    },
    workspaceTarget = snapshot,
    execute = { input ->
        val raw = ((input as? JsonObject)?.get("path") as? JsonPrimitive)?.content.orEmpty()
        val path = spaceSharedPath(raw)
        val result = if (path == null) buildJsonObject {
            put("error", "Not inside the Shared folder. Copy it into ${PartnerSpaces.SHARED_DIR} first, then pass its name.")
        } else runCatching { spaces.handOut(workspaceId, path) }.fold(
            onSuccess = { copied ->
                buildJsonObject {
                    put("computer", computerName)
                    putJsonArray("copied") { copied.forEach { add(JsonPrimitive("~/$it")) } }
                }
            },
            onFailure = { error ->
                buildJsonObject {
                    put("error", when ((error as? CuaSpaceException)?.code) {
                        "not_found" -> "Nothing named `$path` in the Shared folder (symbolic links are not handed over)."
                        else -> error.message ?: "The copy failed"
                    })
                }
            },
        )
        listOf(UIMessagePart.Text(result.toString()))
    },
)
