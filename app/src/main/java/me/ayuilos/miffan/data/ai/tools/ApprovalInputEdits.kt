package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Switches an approval card may set when the owner allows; every other argument stays as the partner sent it. */
private val CARD_SWITCHES = mapOf(
    SCHEDULE_TASK_TOOL to setOf("test_run"),
    AGENT_TOOLS_TOOL to setOf("update_tasks"),
)

/**
 * True when [edited] differs from the partner's [original] call only in the owner's switches for
 * [toolName], each a boolean, so allowing it runs exactly what the card showed.
 */
internal fun isCardSwitchEdit(toolName: String, original: JsonElement, edited: JsonObject): Boolean {
    val switches = CARD_SWITCHES[toolName] ?: return false
    val before = original as? JsonObject ?: return false
    return before - switches == edited - switches &&
        switches.all { key -> edited[key]?.let { (it as? JsonPrimitive)?.booleanOrNull != null } ?: true }
}
