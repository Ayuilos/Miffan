package me.ayuilos.miffan.utils

import androidx.annotation.StringRes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.tools.COMPUTER_TOOL_PREFIX

internal data class ComputerActionTitle(@param:StringRes val label: Int?, val detail: String? = null)

internal fun computerActionTitle(toolName: String, arguments: JsonElement): ComputerActionTitle {
    fun argument(vararg names: String) = names.firstNotNullOfOrNull { name ->
        ((arguments as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
    return when (toolName.removePrefix(COMPUTER_TOOL_PREFIX)) {
        "get_desktop_state" -> ComputerActionTitle(R.string.computer_use_view_screen)
        "get_window_state" -> ComputerActionTitle(R.string.computer_use_view_window)
        "list_windows" -> ComputerActionTitle(R.string.computer_use_list_windows)
        "list_apps" -> ComputerActionTitle(R.string.computer_use_list_apps)
        "click" -> ComputerActionTitle(R.string.computer_use_click)
        "double_click" -> ComputerActionTitle(R.string.computer_use_double_click)
        "right_click" -> ComputerActionTitle(R.string.computer_use_right_click)
        "drag" -> ComputerActionTitle(R.string.computer_use_drag)
        // set_value fills a text field in one go; to the user it is typing too.
        "type_text", "set_value" -> {
            val text = argument("text", "value")
            val detail = text?.let {
                val count = it.codePointCount(0, it.length)
                it.substring(0, it.offsetByCodePoints(0, minOf(count, 40)))
                    .replace('\n', ' ').replace('\r', ' ') + if (count > 40) "…" else ""
            }
            ComputerActionTitle(R.string.computer_use_type_text, detail)
        }
        "press_key", "hotkey" -> {
            val keys = (arguments as? JsonObject)?.get("keys") as? JsonArray
            val detail = argument("key", "keys") ?: keys?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }?.joinToString(" + ")?.takeIf { it.isNotBlank() }
            ComputerActionTitle(
                if (toolName == "${COMPUTER_TOOL_PREFIX}hotkey") R.string.computer_use_shortcut
                else R.string.computer_use_press_key,
                detail,
            )
        }
        "scroll" -> ComputerActionTitle(R.string.computer_use_scroll)
        "launch_app" -> ComputerActionTitle(R.string.computer_use_open_app, argument("bundle_id", "name", "app"))
        "kill_app" -> ComputerActionTitle(R.string.computer_use_close_app, argument("pid", "bundle_id", "name", "app"))
        "start" -> ComputerActionTitle(R.string.computer_use_start)
        "read_guide" -> ComputerActionTitle(R.string.computer_use_read_guide)
        "bring_to_front" -> ComputerActionTitle(R.string.computer_use_bring_to_front)
        "invoke_menu" -> ComputerActionTitle(R.string.computer_use_menu)
        "set_window_frame" -> ComputerActionTitle(R.string.computer_use_window_frame)
        "move_cursor" -> ComputerActionTitle(R.string.computer_use_move_cursor)
        "zoom" -> ComputerActionTitle(R.string.computer_use_zoom)
        "get_screen_size" -> ComputerActionTitle(R.string.computer_use_screen_size)
        "get_cursor_position" -> ComputerActionTitle(R.string.computer_use_cursor_position)
        "verify_state" -> ComputerActionTitle(R.string.computer_use_verify)
        else -> ComputerActionTitle(null)
    }
}

