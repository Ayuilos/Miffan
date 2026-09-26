package me.ayuilos.miffan.data.ai.transformers

import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class WorkspaceReminderTransformerTest {
    private val conversationId = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val scopeId = "22222222-2222-4222-8222-222222222222"
    private val artifactDirectory = "/workspace/conversations/$conversationId"
    private val workspace = WorkspaceEntity(
        id = "workspace-id",
        name = "Test workspace",
        root = "workspace-root",
        shellStatus = WorkspaceShellStatus.READY.name,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `conversation artifacts use the full stable conversation id`() {
        val prompt = prompt()

        assertTrue(prompt.contains("Conversation artifact directory: `$artifactDirectory/`"))
        assertTrue(prompt.contains("mkdir -p $artifactDirectory"))
        assertTrue(prompt.contains("Reuse this exact directory for all later turns, regenerations"))
        assertTrue(prompt.contains("do not create a new directory per turn"))
        assertEquals(prompt, prompt())
    }

    @Test
    fun `different conversations under the same assistant get different directories`() {
        val otherConversationId = Uuid.parse("11111111-1111-4111-8111-111111111112")
        val firstPrompt = prompt()
        val secondPrompt = prompt(conversationId = otherConversationId)

        assertNotEquals(firstPrompt, secondPrompt)
        assertTrue(firstPrompt.contains("$artifactDirectory/"))
        assertFalse(secondPrompt.contains(artifactDirectory))
        assertTrue(secondPrompt.contains("/workspace/conversations/$otherConversationId/"))
    }

    @Test
    fun `changing cwd or display names does not change the artifact directory`() {
        listOf("/workspace/projects/example", artifactDirectory, "$artifactDirectory/drafts").forEach { cwd ->
            val prompt = prompt(
                workspace = workspace.copy(name = "Renamed workspace"),
                assistantName = "Renamed assistant",
                cwd = cwd,
            )

            assertTrue(prompt.contains("Conversation artifact directory: `$artifactDirectory/`"))
            assertTrue(prompt.contains("Current working directory: `$cwd`"))
            assertTrue(prompt.contains("does not override the conversation artifact directory"))
            assertTrue(prompt.contains("mkdir -p $artifactDirectory"))
            assertFalse(prompt.contains("mkdir -p /workspace/projects/example"))
        }
    }

    @Test
    fun `legacy workspace scope still receives a conversation artifact directory`() {
        val prompt = prompt(scopeId = null)

        assertTrue(prompt.contains("legacy whole-workspace compatibility mode"))
        assertTrue(prompt.contains("Conversation artifact directory: `$artifactDirectory/`"))
        assertTrue(prompt.contains("mkdir -p $artifactDirectory"))
    }

    @Test
    fun `prompt groups related files while retaining explicit user overrides and publishing`() {
        val prompt = prompt()

        assertTrue(prompt.contains("Save all newly created artifacts and related task files inside this conversation directory"))
        assertTrue(prompt.contains("downloaded or copied inputs, and intermediate files"))
        assertTrue(prompt.contains("Do not place them directly in `/workspace`"))
        assertTrue(prompt.contains("edit existing project files in place only when the user explicitly requests it"))
        assertTrue(prompt.contains("Do not move or overwrite existing files from other conversations"))
        assertTrue(prompt.contains("not an additional filesystem access restriction"))
        assertTrue(prompt.contains("create a separate copy under `$artifactDirectory`"))
        assertTrue(prompt.contains("always call `workspace_publish_files` with their absolute paths"))
    }

    @Test
    fun `generation without a conversation does not invent a shared artifact directory`() {
        val prompt = prompt(conversationId = null, cwd = "/workspace/project")

        assertFalse(prompt.contains("Conversation artifact directory:"))
        assertFalse(prompt.contains("/workspace/conversations/"))
        assertFalse(prompt.contains("mkdir -p"))
        assertTrue(prompt.contains("Current working directory: `/workspace/project`"))
        assertTrue(prompt.contains("create a separate copy under `/workspace`"))
    }

    @Test
    fun `remote workspace prompt identifies remote host paths and retry semantics`() {
        val remote = workspace.copy(
            kind = WorkspaceEntity.KIND_REMOTE,
            remoteHostId = "host-id",
            remotePath = "/home/dev/project",
        )
        val prompt = buildWorkspacePrompt(
            workspace = remote,
            cwd = "/workspace/src",
            scopeId = scopeId,
            assistantName = "Test assistant",
            conversationId = conversationId,
            remoteHostLabel = "dev@100.64.0.2:22",
        )

        assertTrue(prompt.contains("dev@100.64.0.2:22"))
        assertTrue(prompt.contains("starting in `/home/dev/project`"))
        assertTrue(prompt.contains("`/workspace`"))
        assertTrue(prompt.contains("`/workspace/.miffan/conversations/$conversationId/`"))
        assertTrue(prompt.contains("`/home/dev/project/.miffan/conversations/$conversationId/`"))
        assertTrue(prompt.contains("Files previously saved under `/home/dev/project/conversations/$conversationId/` remain there"))
        assertTrue(prompt.contains("does not guarantee that an already started remote process has stopped"))
        assertTrue(prompt.contains("do not automatically repeat"))
        assertFalse(prompt.contains("PRoot inside the Miffan Android application"))
        assertFalse(prompt.contains("Only this scope is mounted"))
    }

    @Test
    fun `disabled shell prompt retains remote file identity without advertising commands`() {
        val prompt = buildWorkspacePrompt(
            workspace = workspace.copy(kind = WorkspaceEntity.KIND_REMOTE, remoteHostId = "host", remotePath = "/srv/project"),
            scopeId = scopeId,
            assistantName = "Assistant",
            conversationId = conversationId,
            remoteHostLabel = "dev@server:22",
            shellEnabled = false,
        )
        assertTrue(prompt.contains("AI Shell execution is disabled"))
        assertTrue(prompt.contains("dev@server:22"))
        assertTrue(prompt.contains("/srv/project"))
        assertTrue(prompt.contains("/workspace/.miffan/conversations/$conversationId/"))
        assertTrue(prompt.contains("Previously saved files under `/workspace/conversations/$conversationId/` remain available"))
        assertFalse(prompt.contains("`workspace_shell`"))
        assertFalse(prompt.contains("mkdir -p"))
    }

    @Test
    fun `remote workspace rooted at slash uses absolute artifact paths`() {
        val prompt = buildWorkspacePrompt(
            workspace = workspace.copy(kind = WorkspaceEntity.KIND_REMOTE, remoteHostId = "host", remotePath = "/"),
            scopeId = scopeId,
            assistantName = "Assistant",
            conversationId = conversationId,
        )

        assertTrue(prompt.contains("corresponding to `/.miffan/conversations/$conversationId/`"))
        assertTrue(prompt.contains("Files previously saved under `/conversations/$conversationId/`"))
        assertFalse(prompt.contains("//conversations/"))
    }

    private fun prompt(
        conversationId: Uuid? = this.conversationId,
        scopeId: String? = this.scopeId,
        cwd: String? = null,
        workspace: WorkspaceEntity = this.workspace,
        assistantName: String = "Test assistant",
    ): String = buildWorkspacePrompt(
        workspace = workspace,
        cwd = cwd,
        scopeId = scopeId,
        assistantName = assistantName,
        conversationId = conversationId,
    )
}
