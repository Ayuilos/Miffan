package me.ayuilos.miffan.utils

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.tools.COMPUTER_DRIVER_TOOL_NAMES
import me.ayuilos.miffan.data.ai.tools.COMPUTER_TOOL_PREFIX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerActionTitleTest {
    @Test fun everyDriverToolHasATitle() {
        val untitled = COMPUTER_DRIVER_TOOL_NAMES.filter {
            computerActionTitle(COMPUTER_TOOL_PREFIX + it, JsonObject(emptyMap())).label == null
        }
        assertTrue("Tools shown by their raw name: $untitled", untitled.isEmpty())
    }

    @Test fun settingAValueReadsAsTyping() {
        val title = computerActionTitle("${COMPUTER_TOOL_PREFIX}set_value", JsonObject(mapOf("value" to JsonPrimitive("周末愉快"))))
        assertEquals(ComputerActionTitle(R.string.computer_use_type_text, "周末愉快"), title)
    }
}
