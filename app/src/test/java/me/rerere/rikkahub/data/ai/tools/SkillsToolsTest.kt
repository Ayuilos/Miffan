package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillsToolsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `use_skill reads metadata directory when display name differs`() = runBlocking {
        val skillDir = tempFolder.newFolder("directory-name")
        skillDir.resolve("SKILL.md").writeText(
            """
                ---
                name: Display Name
                description: Test skill
                ---
                Skill instructions
            """.trimIndent()
        )
        val tool = createSkillTools(
            enabledSkills = setOf("Display Name"),
            allSkills = listOf(
                SkillMetadata(
                    name = "Display Name",
                    description = "Test skill",
                    skillDir = skillDir,
                )
            ),
        ).single()

        val result = tool.execute(
            buildJsonObject {
                put("name", "Display Name")
            }
        )

        assertEquals("Skill instructions", (result.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `use_skill reads built-in body and bundled reference without user files`() = runBlocking {
        val tool = createSkillTools(
            enabledSkills = emptySet(),
            allSkills = emptyList(),
            builtInSkills = listOf(
                BuiltInSkillDefinition(
                    name = "built-in",
                    description = "Bundled test skill",
                    body = "Bundled instructions",
                    bundledFiles = mapOf("references/details.md" to "Bundled details"),
                )
            ),
        ).single()

        val body = tool.execute(buildJsonObject { put("name", "built-in") })
        val reference = tool.execute(
            buildJsonObject {
                put("name", "built-in")
                put("path", "references/details.md")
            }
        )

        assertEquals("Bundled instructions", (body.single() as UIMessagePart.Text).text)
        assertEquals("Bundled details", (reference.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `built-in skill wins over enabled user skill with the same name`() = runBlocking {
        val skillDir = tempFolder.newFolder("colliding-user-skill")
        skillDir.resolve("SKILL.md").writeText("User-controlled instructions")
        val tool = createSkillTools(
            enabledSkills = setOf("trusted-skill"),
            allSkills = listOf(
                SkillMetadata(
                    name = "trusted-skill",
                    description = "User skill",
                    skillDir = skillDir,
                )
            ),
            builtInSkills = listOf(
                BuiltInSkillDefinition(
                    name = "trusted-skill",
                    description = "Built-in skill",
                    body = "Trusted bundled instructions",
                )
            ),
        ).single()

        val result = tool.execute(buildJsonObject { put("name", "trusted-skill") })

        assertEquals("Trusted bundled instructions", (result.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `built-in skill rejects unsafe bundled paths`() {
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInSkillDefinition(
                name = "built-in",
                description = "Bundled test skill",
                body = "Instructions",
                bundledFiles = mapOf("../secret.md" to "secret"),
            )
        }
    }

    @Test
    fun `extension management skill documents guarded mvp workflow`() {
        assertTrue(extensionManagementBuiltInSkill.body.contains("`extensions_catalog`"))
        assertTrue(extensionManagementBuiltInSkill.body.contains("`extensions_preview_changes`"))
        assertTrue(extensionManagementBuiltInSkill.body.contains("`extensions_apply_changes`"))
        assertTrue(extensionManagementBuiltInSkill.body.contains("opaque `previewId`"))
        assertTrue(extensionManagementBuiltInSkill.body.contains("explicit approval"))
        assertTrue(extensionManagementBuiltInSkill.body.contains("redacted"))
        assertTrue(extensionManagementBuiltInSkill.body.contains("next conversation turn"))

        val operations = extensionManagementBuiltInSkill
            .bundledFiles
            .getValue("references/mvp-operations.md")
        assertTrue(operations.contains("Creating or updating quick messages"))
        assertTrue(operations.contains("Creating or updating mode prompt injections"))
        assertTrue(operations.contains("Binding or unbinding"))
        assertTrue(operations.contains("Setting or clearing an assistant workspace"))
        assertTrue(operations.contains("external web search"))
        assertTrue(operations.contains("does not support"))
    }
}
