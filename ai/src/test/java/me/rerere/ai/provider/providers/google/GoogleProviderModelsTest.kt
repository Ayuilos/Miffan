package me.rerere.ai.provider.providers.google

import me.rerere.ai.provider.ModelType
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleProviderModelsTest {
    @Test
    fun `parseGoogleModels handles valid models and non-primitive optional fields`() {
        val models = parseGoogleModels(
            """
            {
              "models": [
                {
                  "name": "models/gemini-2.5-pro",
                  "displayName": "Gemini 2.5 Pro",
                  "supportedGenerationMethods": ["generateContent", {"other": true}]
                },
                {
                  "name": "models/text-embedding-004",
                  "displayName": {"en": "Text Embedding 004"},
                  "supportedGenerationMethods": ["embedContent"]
                },
                {
                  "name": "models/ignored-model",
                  "displayName": "Ignored",
                  "supportedGenerationMethods": ["countTokens"]
                },
                "invalid-entry",
                {
                  "name": {"raw": "models/bad"},
                  "supportedGenerationMethods": ["generateContent"]
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("gemini-2.5-pro", "text-embedding-004"), models.map { it.modelId })
        assertEquals(listOf("Gemini 2.5 Pro", "text-embedding-004"), models.map { it.displayName })
        assertEquals(listOf(ModelType.CHAT, ModelType.EMBEDDING), models.map { it.type })
    }
}
