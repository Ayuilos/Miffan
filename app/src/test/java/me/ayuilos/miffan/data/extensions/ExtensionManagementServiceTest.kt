package me.ayuilos.miffan.data.extensions

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.data.ai.mcp.McpCommonOptions
import me.ayuilos.miffan.data.ai.mcp.McpOAuthState
import me.ayuilos.miffan.data.ai.mcp.McpServerConfig
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Lorebook
import me.ayuilos.miffan.data.model.PromptInjection
import me.ayuilos.miffan.data.model.QuickMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ExtensionManagementServiceTest {
    @Test
    fun `preview capability binds canonical summaries to a unique id`() {
        val summaries = listOf("Create mode injection 'Study'", "Bind it to assistant 'Tutor'")
        val first = createExtensionPreviewId("nonce-1", summaries)
        val second = createExtensionPreviewId("nonce-2", summaries)

        assertEquals(summaries, decodeExtensionPreviewSummaries(first))
        assertNotEquals(first, second)
        assertTrue(decodeExtensionPreviewSummaries("not-a-preview").isEmpty())
    }

    @Test
    fun `catalog never serializes MCP connection secrets`() {
        val serverId = Uuid.random()
        val settings = Settings(
            assistants = listOf(Assistant(name = "Test")),
            mcpServers = listOf(
                McpServerConfig.StreamableHTTPServer(
                    id = serverId,
                    commonOptions = McpCommonOptions(
                        name = "Secret server",
                        headers = listOf(
                            "Authorization" to "Bearer top-secret-header",
                            "X-Api-Key" to "top-secret-api-key",
                        ),
                        oauth = McpOAuthState(
                            enabled = true,
                            clientId = "top-secret-client-id",
                            clientSecret = "top-secret-client-secret",
                            accessToken = "top-secret-access-token",
                            refreshToken = "top-secret-refresh-token",
                            authorizationEndpoint = "https://auth.example/secret",
                        ),
                    ),
                    url = "https://user:password@example.com/mcp?api_key=top-secret-query",
                )
            ),
        )

        val catalog = buildExtensionCatalog(settings, emptyList())
        val serialized = Json.encodeToString(catalog)

        assertEquals(serverId.toString(), catalog.mcpServers.single().id)
        assertTrue(catalog.mcpServers.single().hasCustomHeaders)
        assertTrue(catalog.mcpServers.single().oauthEnabled)
        assertTrue(catalog.mcpServers.single().oauthAuthorized)
        assertFalse(serialized.contains("Authorization"))
        assertFalse(serialized.contains("top-secret"))
        assertFalse(serialized.contains("auth.example"))
        assertFalse(serialized.contains("api_key"))
    }

    @Test
    fun `preview normalizes create id and can bind it in the same batch`() {
        val assistant = Assistant(name = "Study assistant")
        val settings = Settings(assistants = listOf(assistant), quickMessages = emptyList())
        val create = ExtensionChange.UpsertQuickMessage(
            title = "  Explain simply  ",
            content = "  Explain this in simple language.  ",
        )

        val createPreview = ExtensionChangeProcessor.process(
            settings = settings,
            changes = listOf(create),
            resources = emptyResources,
            allowGeneratedIds = true,
        )
        val normalizedCreate = createPreview.changes.single()
            as ExtensionChange.UpsertQuickMessage
        val createdId = assertNotNull(normalizedCreate.id).let { normalizedCreate.id!! }

        val fullPreview = ExtensionChangeProcessor.process(
            settings = settings,
            changes = listOf(
                normalizedCreate,
                ExtensionChange.SetResourceBinding(
                    assistantId = assistant.id.toString(),
                    resourceType = ExtensionResourceType.QUICK_MESSAGE,
                    resourceId = createdId,
                    enabled = true,
                ),
            ),
            resources = emptyResources,
            allowGeneratedIds = false,
        )

        assertTrue(fullPreview.valid)
        assertEquals("Explain simply", fullPreview.settings.quickMessages.single().title)
        assertTrue(
            Uuid.parse(createdId) in fullPreview.settings.assistants.single().quickMessageIds
        )
    }

    @Test
    fun `invalid batch returns original settings without partial mutation`() {
        val assistant = Assistant(name = "Test")
        val settings = Settings(assistants = listOf(assistant))

        val processed = ExtensionChangeProcessor.process(
            settings = settings,
            changes = listOf(
                ExtensionChange.SetExternalWebSearch(
                    assistantId = assistant.id.toString(),
                    enabled = true,
                ),
                ExtensionChange.SetWorkspace(
                    assistantId = assistant.id.toString(),
                    workspaceId = Uuid.random().toString(),
                ),
            ),
            resources = emptyResources,
            allowGeneratedIds = true,
        )

        assertFalse(processed.valid)
        assertEquals(settings, processed.settings)
        assertFalse(processed.settings.assistants.single().enableWebSearch)
        assertTrue(processed.errors.single().contains("Workspace not found"))
    }

    @Test
    fun `stale resource id and extension management self toggle are rejected`() {
        val assistant = Assistant(name = "Test")
        val settings = Settings(assistants = listOf(assistant), quickMessages = emptyList())

        val stale = ExtensionChangeProcessor.process(
            settings = settings,
            changes = listOf(
                ExtensionChange.UpsertQuickMessage(
                    id = Uuid.random().toString(),
                    title = "Missing",
                    content = "Missing",
                )
            ),
            resources = emptyResources,
            allowGeneratedIds = true,
        )
        val selfToggle = ExtensionChangeProcessor.process(
            settings = settings,
            changes = listOf(
                ExtensionChange.SetLocalTool(
                    assistantId = assistant.id.toString(),
                    localToolId = "extension_management",
                    enabled = true,
                )
            ),
            resources = emptyResources,
            allowGeneratedIds = true,
        )

        assertFalse(stale.valid)
        assertTrue(stale.errors.single().contains("not found"))
        assertFalse(selfToggle.valid)
        assertTrue(selfToggle.errors.single().contains("directly by the user"))
    }

    @Test
    fun `catalog includes non-secret workspace metadata`() {
        val workspaceId = Uuid.random().toString()
        val catalog = buildExtensionCatalog(
            settings = Settings(
                assistants = listOf(Assistant(name = "Test")),
                quickMessages = listOf(
                    QuickMessage(title = "Quick", content = "private-quick-message-body")
                ),
                modeInjections = listOf(
                    PromptInjection.ModeInjection(
                        name = "Mode",
                        content = "private-mode-injection-body",
                    )
                ),
                lorebooks = listOf(
                    Lorebook(name = "Book", description = "private-lorebook-description")
                ),
            ),
            workspaces = listOf(
                WorkspaceEntity(
                    id = workspaceId,
                    name = "Study",
                    root = "private-root-name",
                    createdAt = 1L,
                    updatedAt = 2L,
                    toolApprovals = "{\"secret-tool\":false}",
                )
            ),
        )
        val serialized = Json.encodeToString(catalog)

        assertEquals(workspaceId, catalog.workspaces.single().id)
        assertFalse(serialized.contains("private-root-name"))
        assertFalse(serialized.contains("secret-tool"))
        assertFalse(serialized.contains("private-quick-message-body"))
        assertFalse(serialized.contains("private-mode-injection-body"))
        assertFalse(serialized.contains("private-lorebook-description"))
    }

    private companion object {
        val emptyResources = ExternalResources(
            workspaceIds = emptySet(),
        )
    }
}
