package me.ayuilos.miffan.data.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.ayuilos.miffan.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantWorkspaceScopeTest {
    @Test
    fun `old serialized assistant remains in legacy whole-workspace mode`() {
        val workspaceId = Uuid.random()
        val assistantId = Uuid.random()
        val encoded = JsonInstant.encodeToString(
            Assistant(
                id = assistantId,
                workspaceId = workspaceId,
                workspaceScopeId = assistantId,
                workspaceShellApprovalRequired = false,
            )
        )
        val oldJson = JsonObject(
            JsonInstant.parseToJsonElement(encoded).jsonObject.filterKeys {
                it != "workspaceScopeId" && it != "workspaceShellApprovalRequired"
            }
        ).toString()

        val restored = JsonInstant.decodeFromString<Assistant>(oldJson)

        assertEquals(workspaceId, restored.workspaceId)
        assertNull(restored.workspaceScopeId)
        assertTrue(restored.workspaceScope().isLegacyWholeWorkspace)
        assertTrue(restored.workspaceShellApprovalRequired)
    }

    @Test
    fun `two assistants bound to one workspace receive stable distinct scopes`() {
        val workspaceId = Uuid.random()
        val first = Assistant().withWorkspaceBinding(workspaceId)
        val second = Assistant().withWorkspaceBinding(workspaceId)

        assertEquals(first.id, first.workspaceScopeId)
        assertEquals(second.id, second.workspaceScopeId)
        assertFalse(first.workspaceScopeId == second.workspaceScopeId)
        assertEquals(first, first.withWorkspaceBinding(workspaceId))
    }

    @Test
    fun `reselecting an existing legacy binding never hides historical files`() {
        val workspaceId = Uuid.random()
        val legacy = Assistant(workspaceId = workspaceId, workspaceScopeId = null)

        val rebound = legacy.withWorkspaceBinding(workspaceId)

        assertNull(rebound.workspaceScopeId)
        assertTrue(rebound.workspaceScope().isLegacyWholeWorkspace)
    }

    @Test
    fun `changing a binding resets shell approval and creates assistant scope`() {
        val assistant = Assistant(
            workspaceId = Uuid.random(),
            workspaceScopeId = null,
            workspaceShellApprovalRequired = false,
        )

        val rebound = assistant.withWorkspaceBinding(Uuid.random())

        assertEquals(assistant.id, rebound.workspaceScopeId)
        assertTrue(rebound.workspaceShellApprovalRequired)
    }

    @Test
    fun `private scope identity cannot diverge from assistant identity`() {
        val id = Uuid.random()
        val differentId = generateSequence { Uuid.random() }.first { it != id }

        val error = assertThrows(IllegalArgumentException::class.java) {
            Assistant(id = id, workspaceId = Uuid.random(), workspaceScopeId = differentId)
        }

        assertTrue(error.message.orEmpty().contains("stable Assistant id"))
    }
}
