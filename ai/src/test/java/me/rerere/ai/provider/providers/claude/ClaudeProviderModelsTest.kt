package me.rerere.ai.provider.providers.claude

import org.junit.Assert.assertEquals
import org.junit.Test

class ClaudeProviderModelsTest {
    @Test
    fun `parseClaudeModels handles valid models and ignores non-primitive display names`() {
        val models = parseClaudeModels(
            """
            {
              "data": [
                {
                  "id": "claude-sonnet-4-5",
                  "display_name": "Claude Sonnet 4.5"
                },
                {
                  "id": "claude-haiku-4-5",
                  "display_name": {"en": "Claude Haiku 4.5"}
                },
                "invalid-entry",
                {
                  "id": {"value": "bad-id"},
                  "display_name": "Ignored"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("claude-sonnet-4-5", "claude-haiku-4-5"), models.map { it.modelId })
        assertEquals(listOf("Claude Sonnet 4.5", "claude-haiku-4-5"), models.map { it.displayName })
    }
}
