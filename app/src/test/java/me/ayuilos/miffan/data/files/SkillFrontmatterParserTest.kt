package me.ayuilos.miffan.data.files

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillFrontmatterParserTest {
    @Test
    fun `boolean reads yaml booleans and strict string values`() {
        val yamlBoolean = SkillFrontmatterParser.parse(
            """
                ---
                requires-workspace: true
                ---
                Instructions
            """.trimIndent()
        )
        val stringBoolean = SkillFrontmatterParser.parse(
            """
                ---
                requires-workspace: "false"
                ---
                Instructions
            """.trimIndent()
        )

        assertEquals(true, yamlBoolean.boolean("requires-workspace"))
        assertEquals(false, stringBoolean.boolean("requires-workspace"))
    }
}
