package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UIMessageSerializationTest {

    @Test
    fun `synthetic marker is not serialized`() {
        val message = UIMessage.user("internal").copy(isSynthetic = true)

        val encoded = Json.encodeToString(message)
        val decoded = Json.decodeFromString<UIMessage>(encoded)

        assertTrue(message.isSynthetic)
        assertFalse(encoded.contains("isSynthetic"))
        assertFalse(decoded.isSynthetic)
    }

    @Test
    fun `workspace tool target survives conversation serialization and old calls remain targetless`() {
        val target = WorkspaceToolTargetSnapshot(
            assistantId = "assistant",
            workspacePermissionRevision = "permission-1",
            workspaceId = "workspace",
            scopeId = "assistant",
            kind = "REMOTE",
            remoteHostId = "host",
            remoteRoot = "/srv/project",
            hostConnectionRevision = "host-1",
            workspaceName = "Project",
            remoteHostName = "Server",
        )
        val message = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("call", "workspace_shell", "{}", workspaceTarget = target)
        ))

        val encoded = Json.encodeToString(message)
        val restored = Json.decodeFromString<UIMessage>(encoded)
        assertEquals(target, (restored.parts.single() as UIMessagePart.Tool).workspaceTarget)

        val legacyMessage = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("old-call", "workspace_shell", "{}")
        ))
        val legacy = Json.decodeFromString<UIMessage>(Json.encodeToString(legacyMessage))
        assertNull((legacy.parts.single() as UIMessagePart.Tool).workspaceTarget)
    }
}
