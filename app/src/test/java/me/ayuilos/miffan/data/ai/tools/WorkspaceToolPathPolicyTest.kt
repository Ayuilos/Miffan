package me.ayuilos.miffan.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceToolPathPolicyTest {
    @Test
    fun `only canonical workspace and tmp paths skip mandatory approval`() {
        assertFalse(workspaceWriteRequiresApproval("/workspace/file.txt"))
        assertFalse(workspaceWriteRequiresApproval("/tmp/file.txt"))

        assertTrue(workspaceWriteRequiresApproval("/skills/file.txt"))
        assertTrue(workspaceWriteRequiresApproval("/upload/file.txt"))
        assertTrue(workspaceWriteRequiresApproval("/tool_outputs/file.txt"))
        assertTrue(workspaceWriteRequiresApproval("/etc/hosts"))
    }

    @Test
    fun `invalid aliases fail closed into approval`() {
        assertTrue(workspaceWriteRequiresApproval("/workspace/../skills/evil.txt"))
        assertTrue(workspaceWriteRequiresApproval("/workspace//evil.txt"))
        assertTrue(workspaceWriteRequiresApproval("/workspace\\evil.txt"))
        assertTrue(workspaceWriteRequiresApproval("/workspace/evil.txt\u0000tail"))
    }

    @Test
    fun `shell cwd strips only the exact workspace mount`() {
        assertEquals("", "/workspace".toWorkspaceRelativeCwd())
        assertEquals("project", "/workspace/project".toWorkspaceRelativeCwd())
        assertEquals("/workspaceevil", "/workspaceevil".toWorkspaceRelativeCwd())
        assertEquals("/tmp", "/tmp".toWorkspaceRelativeCwd())
        assertEquals("a/../b", "a/../b".toWorkspaceRelativeCwd())
    }
}
