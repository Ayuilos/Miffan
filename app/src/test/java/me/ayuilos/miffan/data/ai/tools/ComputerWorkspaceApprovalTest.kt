package me.ayuilos.miffan.data.ai.tools

import me.ayuilos.miffan.data.model.ComputerUseMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputerWorkspaceApprovalTest {
    private val changes = listOf(WORKSPACE_SHELL_TOOL_NAME, "workspace_write_file", "workspace_edit_file")

    @Test
    fun askFirstAsksBeforeEveryChangeButNotBeforeReading() {
        changes.forEach { assertEquals(it, true, computerWorkspaceApproval(it, ComputerUseMode.ASK)) }
        assertEquals(false, computerWorkspaceApproval("workspace_read_file", ComputerUseMode.ASK))
    }

    @Test
    fun automaticNeverAsks() {
        (changes + "workspace_read_file").forEach { assertEquals(it, false, computerWorkspaceApproval(it, ComputerUseMode.AUTO)) }
    }

    @Test
    fun offHasNoToolsToGate() {
        assertNull(computerWorkspaceApproval(WORKSPACE_SHELL_TOOL_NAME, ComputerUseMode.OFF))
    }
}
