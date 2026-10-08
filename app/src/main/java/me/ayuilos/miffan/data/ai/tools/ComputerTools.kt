package me.ayuilos.miffan.data.ai.tools

import me.rerere.ai.ui.ToolDecisionVia
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool as McpTool
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.ai.computer.RemoteComputerControl
import me.ayuilos.miffan.data.ai.computer.RemoteComputerRegistry
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.data.files.saveUploadFromBytes
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot

private const val TAG = "ComputerTools"
const val COMPUTER_TOOL_PREFIX = "computer_"
private const val SCREENSHOT_MAX_DIMENSION = 1280

/** Read-only cua-driver tools: never need approval and do not take the desktop from the user. */
private val OBSERVE_TOOLS = setOf(
    "list_apps", "list_windows", "get_window_state", "get_desktop_state", "get_screen_size",
    "get_cursor_position", "zoom", "verify_state",
)

/** Tools that change the desktop; approval follows the assistant setting. */
private val ACTION_TOOLS = setOf(
    "launch_app", "kill_app", "click", "double_click", "right_click", "drag", "type_text", "press_key",
    "hotkey", "scroll", "set_value", "invoke_menu", "set_window_frame", "move_cursor",
)

/** Raising windows takes over what the user sees; always asks, like delivery_mode=foreground. */
private val FOREGROUND_TOOLS = setOf("bring_to_front")

/** Every cua-driver tool the partner gets; each needs a readable title in the chat. */
internal val COMPUTER_DRIVER_TOOL_NAMES = OBSERVE_TOOLS + ACTION_TOOLS + FOREGROUND_TOOLS

internal fun computerAutoApprovedBy(name: String, args: JsonElement, approvalRequired: Boolean): ToolDecisionVia? =
    if (name in ACTION_TOOLS && !approvalRequired &&
        (args as? JsonObject)?.get("delivery_mode")?.jsonPrimitive?.contentOrNull != "foreground"
    ) ToolDecisionVia.NO_ASK_SETTING else null

/** Arguments the app supplies or that would make the driver write files on the remote side. */
private fun isHiddenArgument(name: String) =
    name == "session" || name.endsWith("_out") || name.endsWith("out_file") || name == "debug_image_out"

/** Structured fields worth showing the model next to the text (element tokens are built from snapshot_id). */
private val DROPPED_STRUCTURED_FIELDS = setOf("tree_markdown", "elements", "_note")

const val COMPUTER_START_TOOL = COMPUTER_TOOL_PREFIX + "start"

/** True once a conversation has touched the computer, so its later steps need the full tools. */
fun List<UIMessage>.usesComputer(): Boolean =
    any { message -> message.parts.any { it is UIMessagePart.Tool && it.toolName.startsWith(COMPUTER_TOOL_PREFIX) } }

/**
 * The partner always knows it can operate the bound computer, but the cua-driver tools (dozens of
 * schemas and an SSH round trip) load only when it calls [COMPUTER_START_TOOL]. Ordinary chats pay
 * for one small tool; the loaded tools join the generation from its next step.
 */
class ComputerToolbox(
    private val snapshot: WorkspaceToolTargetSnapshot,
    private val approvalRequired: Boolean,
    private val load: suspend () -> List<Tool>,
) {
    private val target = snapshot.remoteHostName ?: snapshot.remoteHostLabel ?: "the remote computer"
    @Volatile private var loaded: List<Tool>? = null

    fun tools(): List<Tool> = listOf(startTool) + loaded.orEmpty()

    /** Loads the tools up front; failures are left for [COMPUTER_START_TOOL] to report. */
    suspend fun preload() {
        if (loaded == null) runCatching { loaded = load() }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "preload: computer tools unavailable", it)
        }
    }

    private val startTool = Tool(
        name = COMPUTER_START_TOOL,
        description = "Start operating the desktop of $target. Loads the computer_* tools for observing and " +
            "controlling it; they become available on your next step.",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        workspaceTarget = snapshot,
        systemPrompt = { _, _ ->
            if (loaded != null) "" else buildString {
                appendLine("## Computer")
                appendLine("You can see and operate the desktop of $target: open apps, click, type and read what is on screen.")
                appendLine("- When a request needs that desktop, call $COMPUTER_START_TOOL first; do not ask the user to enable anything.")
                if (approvalRequired) {
                    appendLine("- Each action that changes the desktop asks the user for approval in the chat, so you need not ask for permission in text beforehand.")
                }
                appendLine("- The user can watch the same screen and take over at any time.")
                appendLine("- For anything on the screen, use these tools rather than shell screenshot or input commands.")
            }
        },
        execute = {
            if (loaded == null) loaded = try {
                load()
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) null else throw error
            } catch (error: Exception) {
                Log.w(TAG, "start: computer tools unavailable", error)
                return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("status", "error")
                    put("reason", "Could not reach cua-driver on $target: ${error.message}")
                    put("next", "Tell the user. They can recheck the computer from your profile (Connect a computer).")
                }.toString()))
            }
            val tools = loaded ?: return@Tool listOf(UIMessagePart.Text(
                """{"status":"error","reason":"$target did not answer in time. Tell the user and stop."}""",
            ))
            listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "ready")
                put("tools", tools.joinToString(", ") { it.name })
                put("next", "Observe first, e.g. computer_get_desktop_state.")
            }.toString()))
        },
    )
}

/**
 * `computer_*` tools for an assistant bound to a remote workspace, backed by cua-driver over SSH.
 * Schemas come from the remote driver so they follow its version; the app injects the session
 * label, caps screenshot size, gates actions on approval and on who holds the desktop.
 */
suspend fun createComputerTools(
    snapshot: WorkspaceToolTargetSnapshot,
    approvalRequired: Boolean,
    registry: RemoteComputerRegistry,
    control: RemoteComputerControl,
    filesManager: FilesManager,
): List<Tool> {
    val workspaceId = snapshot.workspaceId
    val capabilities = registry.capabilities(workspaceId)
    val hostId = capabilities.hostId
    val target = snapshot.remoteHostName ?: snapshot.remoteHostLabel ?: "the remote computer"
    val tools = capabilities.tools.filter { it.name in OBSERVE_TOOLS || it.name in ACTION_TOOLS || it.name in FOREGROUND_TOOLS }

    fun requiresApproval(name: String, args: JsonElement): Boolean = when {
        name in FOREGROUND_TOOLS -> true
        (args as? JsonObject)?.get("delivery_mode")?.jsonPrimitive?.contentOrNull == "foreground" -> true
        name in OBSERVE_TOOLS -> false
        else -> approvalRequired
    }

    return buildList {
        tools.forEach { tool ->
            add(Tool(
                name = COMPUTER_TOOL_PREFIX + tool.name,
                description = tool.description.orEmpty(),
                parameters = { tool.toInputSchema() },
                workspaceTarget = snapshot,
                needsApproval = { args -> requiresApproval(tool.name, args) },
                autoApprovedBy = { args ->
                    computerAutoApprovedBy(tool.name, args, approvalRequired)
                },
                execute = { args ->
                    val arguments = withScreenshotCap(tool, args.jsonObject)
                    if (tool.name in OBSERVE_TOOLS) {
                        convert(registry.call(workspaceId, tool.name, arguments), filesManager)
                    } else {
                        control.partnerActs(hostId) { convert(registry.call(workspaceId, tool.name, arguments), filesManager) }
                            ?: listOf(UIMessagePart.Text(
                                """{"status":"refused","reason":"The user has taken control of the remote screen. """ +
                                    """Stop acting and wait until the user hands control back."}""",
                            ))
                    }
                },
            ))
        }
        add(createReadGuideTool(workspaceId, target, capabilities, registry))
    }
}

private fun createReadGuideTool(
    workspaceId: String,
    target: String,
    capabilities: RemoteComputerRegistry.Capabilities,
    registry: RemoteComputerRegistry,
) = Tool(
    name = COMPUTER_TOOL_PREFIX + "read_guide",
    description = "Read one of cua-driver's guides for operating the remote computer. Available: " +
        capabilities.resources.joinToString(", ").ifEmpty { "none" },
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("uri", buildJsonObject {
                put("type", "string")
                put("description", "One of the listed guide URIs, e.g. skill://cua-driver/WORKFLOW.md")
            })
        }, required = listOf("uri"))
    },
    systemPrompt = { _, _ ->
        buildString {
            appendLine("## Operating $target")
            appendLine("You can see and operate the desktop of $target through the computer_* tools, which call cua-driver on that machine.")
            appendLine("- The user can watch the same screen and take over at any time. If a tool reports that the user has taken control, stop and wait for them.")
            appendLine("- Observe before every action and verify after it (computer_get_window_state or computer_get_desktop_state).")
            appendLine("- launch_app only opens GUI apps; never use it to run commands such as kill. To close an app or window use kill_app (or the app's own close shortcut via hotkey). For other commands use the shell tool if you have one.")
            appendLine("- An element_token is `<snapshot_id>:<element index>`, using snapshot_id from the latest state of that window; the tree shows indices as [n].")
            appendLine("- Prefer background actions. delivery_mode \"foreground\" and computer_bring_to_front interrupt the user and always ask for their approval.")
            appendLine("- Never type passwords or other secrets. Ask the user to enter them on their screen themselves.")
            appendLine("- Screenshots are resized for you; pixel coordinates refer to the screenshot you were given, together with its capture_id.")
            capabilities.guide?.let {
                appendLine()
                appendLine("### cua-driver guide (from the remote driver)")
                appendLine(it)
            }
        }
    },
    execute = { args ->
        val uri = args.jsonObject["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(uri in capabilities.resources) { "Unknown guide: $uri" }
        listOf(UIMessagePart.Text(registry.readResource(workspaceId, uri)))
    },
)

private fun McpTool.toInputSchema(): InputSchema.Obj {
    val properties = inputSchema.properties ?: JsonObject(emptyMap())
    return InputSchema.Obj(
        properties = JsonObject(properties.filterKeys { !isHiddenArgument(it) }),
        required = inputSchema.required?.filterNot(::isHiddenArgument),
    )
}

/** Caps screenshots at [SCREENSHOT_MAX_DIMENSION] when the driver supports the argument. */
private fun withScreenshotCap(tool: McpTool, args: JsonObject): JsonObject {
    val supports = inputSchemaHas(tool, "max_image_dimension")
    val visible = args.filterKeys { !isHiddenArgument(it) }
    return if (supports && "max_image_dimension" !in visible) {
        JsonObject(visible + ("max_image_dimension" to JsonPrimitive(SCREENSHOT_MAX_DIMENSION)))
    } else JsonObject(visible)
}

private fun inputSchemaHas(tool: McpTool, name: String) = tool.inputSchema.properties?.containsKey(name) == true

internal suspend fun convert(result: CallToolResult, filesManager: FilesManager): List<UIMessagePart> {
    val parts = mutableListOf<UIMessagePart>()
    result.content.forEach { content ->
        when (content) {
            is TextContent -> parts += UIMessagePart.Text(content.text)
            is ImageContent -> runCatching { saveScreenshot(content, filesManager) }
                .onSuccess { parts += it }
                .onFailure { Log.w(TAG, "screenshot conversion failed", it) }
            else -> Unit
        }
    }
    result.structuredContent?.let { structured ->
        val compact = JsonObject(structured.filter { (key, value) ->
            key !in DROPPED_STRUCTURED_FIELDS && (value !is JsonPrimitive || value.toString().length <= 200)
        })
        val text = compact.toString()
        parts += UIMessagePart.Text(if (text.length <= 2_000) "structured: $text" else "structured: " + JsonObject(
            compact.filterValues { it is JsonPrimitive }
        ).toString().take(2_000))
    }
    if (result.isError == true) {
        val code = (result.structuredContent?.get("code") as? JsonPrimitive)?.contentOrNull
        val effect = (result.structuredContent?.get("effect") as? JsonPrimitive)?.contentOrNull
        val unconfirmed = code == "launch_handoff_timeout" ||
            (code?.endsWith("_timeout") == true && effect != "failed")
        parts.add(0, UIMessagePart.Text(
            if (unconfirmed) """{"status":"unconfirmed"}""" else """{"status":"error"}""",
        ))
    }
    return parts
}

/** Re-encodes the driver's PNG as JPEG: screenshots stay in chat history and go to the model. */
private suspend fun saveScreenshot(image: ImageContent, filesManager: FilesManager): UIMessagePart.Image {
    val png = Base64.decode(image.data, Base64.DEFAULT)
    val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size) ?: error("Unreadable screenshot")
    val jpeg = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bitmap.recycle()
        out.toByteArray()
    }
    val entity = filesManager.saveUploadFromBytes(bytes = jpeg, displayName = "screen.jpg", mimeType = "image/jpeg")
    return UIMessagePart.Image(url = filesManager.getFile(entity).toUri().toString())
}
