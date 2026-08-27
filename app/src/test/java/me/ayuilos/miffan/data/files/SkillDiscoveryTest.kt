package me.ayuilos.miffan.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillDiscoveryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `workspace discovery preserves scope and infers environment requirement`() {
        val root = tempFolder.newFolder("skills")
        val skillDir = root.resolve("project-review").apply { mkdirs() }
        skillDir.resolve("SKILL.md").writeText(
            """
                ---
                name: project-review
                description: Review files in this project
                allowed-tools: Read Grep Bash(git:*)
                ---
                Review instructions
            """.trimIndent()
        )

        val skill = discoverSkills(
            skillsDir = root,
            scope = SkillScope.WORKSPACE,
            workspaceId = "workspace-id",
            workspaceScopeId = "assistant-a",
        ).single()

        assertEquals(SkillScope.WORKSPACE, skill.scope)
        assertEquals("workspace-id", skill.workspaceId)
        assertEquals("assistant-a", skill.workspaceScopeId)
        assertTrue(skill.requiresWorkspace)
        assertEquals(listOf("Read", "Grep", "Bash(git:*)"), skill.allowedTools)
    }

    @Test
    fun `discovery ignores directories without a regular skill file`() {
        val root = tempFolder.newFolder("invalid-skills")
        root.resolve("missing").mkdirs()
        root.resolve("directory-file/SKILL.md").mkdirs()

        assertTrue(discoverSkills(root, SkillScope.WORKSPACE, "workspace-id").isEmpty())
    }
}
