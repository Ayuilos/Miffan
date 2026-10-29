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
import me.ayuilos.miffan.data.ai.computer.ComputerHolder
import me.ayuilos.miffan.data.ai.computer.PartnerActResult
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
    "hotkey", "scroll", "set_value", "invoke_menu", "set_window_frame", "move_cursor", "clipboard_write",
)

/** Raising windows takes over what the user sees; always asks, like delivery_mode=foreground. */
private val FOREGROUND_TOOLS = setOf("bring_to_front")

/** Every cua-driver tool the partner gets; each needs a readable title in the chat. */
internal val COMPUTER_DRIVER_TOOL_NAMES = OBSERVE_TOOLS + ACTION_TOOLS + FOREGROUND_TOOLS

internal fun computerAutoApprovedBy(name: String, args: JsonElement, approvalRequired: Boolean, space: Boolean = false): ToolDecisionVia? =
    if ((name in ACTION_TOOLS || space && name in FOREGROUND_TOOLS) && !approvalRequired &&
        (space || (args as? JsonObject)?.get("delivery_mode")?.jsonPrimitive?.contentOrNull != "foreground")
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
    /** Starts the driver session (again, if the driver ended it) and returns its label. */
    private val ensureSession: suspend () -> String,
    private val load: suspend () -> List<Tool>,
) {
    private val target = computerTarget(snapshot)
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
        description = "Start operating the desktop of $target, or reconnect after a computer_* tool reports that its " +
            "session ended. Loads the computer_* tools and checks the session can take calls; the tools become " +
            "available on your next step.",
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
            // Loaded tools are not proof the driver will act: it may have ended the session meanwhile.
            val session = try {
                ensureSession()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "start: session unavailable", error)
                return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("status", "error")
                    put("reason", "cua-driver on $target is loaded but its session could not start: ${error.message}")
                    put("next", "Tell the user and stop.")
                }.toString()))
            }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "ready")
                put("session", session)
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
    /** The partner's display name, shown to other partners refused while this one holds the desktop. */
    partnerName: String = "",
): List<Tool> {
    val workspaceId = snapshot.workspaceId
    val holder = ComputerHolder(snapshot.assistantId, snapshot.conversationId.orEmpty(), partnerName)
    val capabilities = registry.capabilities(workspaceId)
    val controlKey = capabilities.controlKey
    val target = computerTarget(snapshot)
    val tools = capabilities.tools.filter { it.name in OBSERVE_TOOLS || it.name in ACTION_TOOLS || it.name in FOREGROUND_TOOLS }
    val clickTool = tools.firstOrNull { it.name == "click" }

    // Raising windows only interrupts the owner on their own desktop; a partner's own computer is not that.
    val space = snapshot.kind == me.ayuilos.miffan.data.db.entity.WorkspaceEntity.KIND_SPACE
    fun requiresApproval(name: String, args: JsonElement): Boolean = when {
        name in FOREGROUND_TOOLS && !space -> true
        !space && (args as? JsonObject)?.get("delivery_mode")?.jsonPrimitive?.contentOrNull == "foreground" -> true
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
                    computerAutoApprovedBy(tool.name, args, approvalRequired, space)
                },
                execute = { args ->
                    val arguments = withScreenshotCap(tool, args.jsonObject)
                    val focusClick = desktopTypeFocusClick(tool, arguments, clickTool)
                    if (tool.name in OBSERVE_TOOLS) {
                        convert(registry.call(workspaceId, tool.name, arguments), filesManager)
                    } else {
                        when (val result = control.partnerActs(controlKey, holder) {
                            if (focusClick != null) typeAfterFocusClick(workspaceId, registry, focusClick, arguments, filesManager)
                            else convert(registry.call(workspaceId, tool.name, arguments), filesManager)
                        }) {
                            is PartnerActResult.Done -> result.value
                            PartnerActResult.UserHolds -> listOf(UIMessagePart.Text(
                                """{"status":"refused","reason":"The user has taken control of the remote screen. """ +
                                    """Stop acting and wait until the user hands control back."}""",
                            ))
                            is PartnerActResult.Busy -> listOf(UIMessagePart.Text(busyRefusal(result.holder, holder, target)))
                            PartnerActResult.Updating -> listOf(UIMessagePart.Text(updatingRefusal(target)))
                        }
                    }
                },
            ))
        }
        add(createReadGuideTool(workspaceId, target, capabilities, registry, space))
    }
}

/** Why another turn holds the desktop and what to do instead of retrying the action. */
/** How the tools name the machine they operate: a partner's own computer is not its host. */
internal fun computerTarget(snapshot: WorkspaceToolTargetSnapshot): String =
    if (snapshot.kind == me.ayuilos.miffan.data.db.entity.WorkspaceEntity.KIND_SPACE) "your mini box (your own Linux desktop)"
    else snapshot.remoteHostName ?: snapshot.remoteHostLabel ?: "the remote computer"

internal fun busyRefusal(holder: ComputerHolder, me: ComputerHolder, target: String): String = buildJsonObject {
    put("status", "busy")
    put("reason", when {
        holder.assistantId == me.assistantId -> "You are already operating $target in another conversation."
        holder.name.isNotBlank() -> "${holder.name} is operating $target right now."
        else -> "Another partner is operating $target right now."
    })
    put("next", "Do not retry the action now. Continue with work that does not need the desktop, or tell the user " +
        "you are waiting for the computer to be free. Only one partner can operate it at a time.")
}.toString()

/** The computer's cua-driver is being updated; the partner waits instead of retrying. */
internal fun updatingRefusal(target: String): String = buildJsonObject {
    put("status", "refused")
    put("reason", "The computer-use driver on $target is being updated, which takes about a minute.")
    put("next", "Do not retry right away. Continue with work that does not need the desktop, then try again in a minute.")
}.toString()

private val DESKTOP_TYPE_POSITION = setOf("x", "y")

/**
 * type_text's x,y form pixel-clicks the field before typing, but the driver honours it only for a
 * window; on the desktop it types into whatever has focus and drops the position. The app keeps
 * the documented meaning by clicking there first. Returns the click's arguments, or null when the
 * call needs no such help.
 */
internal fun desktopTypeFocusClick(tool: McpTool, arguments: JsonObject, click: McpTool?): JsonObject? {
    if (tool.name != "type_text" || click == null || !DESKTOP_TYPE_POSITION.all { it in arguments }) return null
    if ("element_token" in arguments || "pid" in arguments || "window_id" in arguments) return null
    val scope = (arguments["scope"] as? JsonPrimitive)?.contentOrNull
    val targetKind = ((arguments["target"] as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull
    if (scope != "desktop" && targetKind != "desktop") return null
    val accepted = click.inputSchema.properties?.keys.orEmpty()
    if (!DESKTOP_TYPE_POSITION.all { it in accepted }) return null
    return JsonObject(arguments.filterKeys { it in accepted && it != "text" && it != "delay_ms" })
}

private suspend fun typeAfterFocusClick(
    workspaceId: String,
    registry: RemoteComputerRegistry,
    click: JsonObject,
    arguments: JsonObject,
    filesManager: FilesManager,
): List<UIMessagePart> {
    val clicked = registry.call(workspaceId, "click", click)
    if (clicked.isError == true) return listOf(UIMessagePart.Text(
        """{"status":"error","reason":"Clicking the field at x,y to focus it failed, so nothing was typed."}""",
    )) + convert(clicked, filesManager)
    val typed = registry.call(workspaceId, "type_text", JsonObject(arguments.filterKeys { it !in DESKTOP_TYPE_POSITION }))
    return listOf(UIMessagePart.Text(
        """{"note":"Clicked x,y to focus the field, then typed into the focused application. Check the screen to confirm."}""",
    )) + convert(typed, filesManager)
}

/**
 * How to work on a partner's own computer: its desktop is the partner's alone, and its browser is
 * driven like a person drives it (cua-driver's typed browser tools need a Chromium it cannot reach there).
 */
private val SPACE_COMPUTER_GUIDANCE = """
- This desktop is yours alone; nobody else is using it. When a background action does not take effect, call computer_bring_to_front on that window, then repeat the action with delivery_mode "foreground"; neither asks anyone.
- Start every task with computer_list_windows: windows and processes from an earlier task may be gone (pid, window_id and capture_id go stale), so never reuse them.
- The browser is Firefox. Use it the way a person does: launch or bring it to the front, press ctrl+l (computer_hotkey) to select the address bar, type the address with computer_type_text, press Return, then confirm on a fresh observation that the page loaded before reading it.
- When you only need a page's text or a file, `curl` in the shell is faster than the browser; use the browser when the task is about using the site itself.
- Report only what a fresh observation shows. If the page did not load (an error page, a blank tab), say so and say what you saw; never answer from memory or from another tool as if you had seen it in the browser.
""".trimEnd()

private fun createReadGuideTool(
    workspaceId: String,
    target: String,
    capabilities: RemoteComputerRegistry.Capabilities,
    registry: RemoteComputerRegistry,
    /** A partner's own computer: nobody else works on that desktop. */
    space: Boolean = false,
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
            appendLine("- Make one computer_* call at a time when it depends on an earlier one: never put an action and the observation that checks it in the same step. Use only snapshot_id, capture_id and element tokens that a tool actually returned; never predict them.")
            appendLine("- A reply of \"typed\" or effect \"unverifiable\" means the input was sent, not that it landed; confirm it on a fresh observation.")
            appendLine("- If a tool says the session ended or is not ready, call $COMPUTER_START_TOOL once to reconnect, then observe again. Do not repeat the failing call or look for the driver over the shell.")
            appendLine("- launch_app only opens GUI apps; never use it to run commands such as kill. To close an app or window use kill_app (or the app's own close shortcut via hotkey). For other commands use the shell tool if you have one.")
            appendLine("- An element_token is `<snapshot_id>:<element index>`, using snapshot_id from the latest state of that window; the tree shows indices as [n].")
            if (space) appendLine(SPACE_COMPUTER_GUIDANCE)
            else appendLine("- Prefer background actions. delivery_mode \"foreground\" and computer_bring_to_front interrupt the user and always ask for their approval.")
            appendLine("- Never type passwords or other secrets. Ask the user to enter them on their screen themselves.")
            appendLine("- Screenshots are resized for you; pixel coordinates refer to the screenshot you were given, together with its capture_id.")
            if (capabilities.windows) appendLine(WINDOWS_COMPUTER_GUIDANCE)
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

internal const val WINDOWS_COMPUTER_GUIDANCE = """
- On Windows, pasting with Ctrl+V requires delivery_mode=foreground and approval; background Ctrl+V may only insert the letter v. Prefer type_text for Chinese text.
- Opening an application does not guarantee a blank window: Windows 11 Notepad can restore historical documents. Open a new task-owned file or use File > New window; read only window content needed for the user's task.
- Lock screens and UAC secure desktops cannot be read or operated. Stop when encountered and tell the user to unlock the computer or handle the UAC prompt themselves; observe again after they finish.
"""

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
