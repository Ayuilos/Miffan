package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.OPENAI_CODEX_BASE_URL
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAIProviderModelsTest {
    @Test
    fun `Codex subscription model URL includes client version`() {
        val setting = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            baseUrl = OPENAI_CODEX_BASE_URL,
        )

        val url = openAIModelsUrl(setting)

        assertEquals("$OPENAI_CODEX_BASE_URL/models", url.toString().substringBefore('?'))
        assertEquals("0.148.0", url.queryParameter("client_version"))
    }

    @Test
    fun `API key model URL keeps standard shape`() {
        val setting = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.API_KEY,
            baseUrl = "https://api.openai.com/v1",
        )

        val url = openAIModelsUrl(setting)

        assertEquals("https://api.openai.com/v1/models", url.toString())
        assertNull(url.queryParameter("client_version"))
    }

    @Test
    fun `Codex model response uses visible API supported slugs`() {
        val models = parseOpenAIModels(
            """
            {
              "models": [
                {
                  "slug": "gpt-5.6-sol",
                  "display_name": "GPT-5.6 Sol",
                  "supported_in_api": true,
                  "visibility": "list"
                },
                {
                  "slug": "hidden-model",
                  "supported_in_api": true,
                  "visibility": "hide"
                },
                {
                  "slug": "unsupported-model",
                  "supported_in_api": false,
                  "visibility": "list"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, models.size)
        assertEquals("gpt-5.6-sol", models.single().modelId)
        assertEquals("GPT-5.6 Sol", models.single().displayName)
    }

    @Test
    fun `OpenRouter model response preserves discovered capabilities`() {
        val model = parseOpenAIModels(
            """
            {
              "data": [
                {
                  "id": "vendor/new-multimodal-model",
                  "name": "Vendor: New Multimodal Model",
                  "architecture": {
                    "input_modalities": ["text", "image", "audio", "video", "file"],
                    "output_modalities": ["text"]
                  },
                  "reasoning": {
                    "supported_efforts": ["high", "medium", "low"],
                    "default_effort": "medium",
                    "default_enabled": true,
                    "supports_max_tokens": false,
                    "mandatory": true
                  },
                  "supported_parameters": ["temperature", "tools", "reasoning"]
                }
              ]
            }
            """.trimIndent()
        ).single().let(ModelRegistry::resolveCapabilities)

        assertEquals("Vendor: New Multimodal Model", model.displayName)
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO, Modality.VIDEO, Modality.FILE),
            model.inputModalities,
        )
        assertEquals(listOf(Modality.TEXT), model.outputModalities)
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), model.abilities)
        assertEquals(listOf("high", "medium", "low"), model.reasoningCapabilities?.supportedEfforts)
        assertEquals("medium", model.reasoningCapabilities?.defaultEffort)
        assertEquals(true, model.reasoningCapabilities?.defaultEnabled)
        assertEquals(false, model.reasoningCapabilities?.supportsMaxTokens)
        assertEquals(true, model.reasoningCapabilities?.mandatory)
    }

    @Test
    fun `Reasoning metadata implies reasoning ability without supported parameters`() {
        val model = parseOpenAIModels(
            """
            {
              "data": [
                {
                  "id": "vendor/mandatory-reasoner",
                  "reasoning": {"mandatory": true}
                }
              ]
            }
            """.trimIndent()
        ).single().let(ModelRegistry::resolveCapabilities)

        assertEquals(listOf(ModelAbility.REASONING), model.abilities)
        assertEquals(true, model.reasoningCapabilities?.mandatory)
    }

    @Test
    fun `Reasoning-only metadata keeps registry abilities as fallback`() {
        val model = parseOpenAIModels(
            """
            {
              "data": [
                {
                  "id": "gpt-4o",
                  "reasoning": {"mandatory": true}
                }
              ]
            }
            """.trimIndent()
        ).single().let(ModelRegistry::resolveCapabilities)

        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), model.abilities)
    }

    @Test
    fun `Provider capabilities take priority over registry inference`() {
        val model = parseOpenAIModels(
            """
            {
              "data": [
                {
                  "id": "gpt-4o",
                  "architecture": {
                    "input_modalities": ["text"],
                    "output_modalities": ["text"]
                  },
                  "supported_parameters": []
                }
              ]
            }
            """.trimIndent()
        ).single().let(ModelRegistry::resolveCapabilities)

        assertEquals(listOf(Modality.TEXT), model.inputModalities)
        assertEquals(listOf(Modality.TEXT), model.outputModalities)
        assertEquals(emptyList<ModelAbility>(), model.abilities)
    }

    @Test
    fun `Standard model response falls back to registry capabilities`() {
        val model = parseOpenAIModels(
            """
            {
              "data": [
                {"id": "gpt-4o", "object": "model", "owned_by": "openai"}
              ]
            }
            """.trimIndent()
        ).single().let(ModelRegistry::resolveCapabilities)

        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), model.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL), model.abilities)
    }

    @Test
    fun `Missing and unknown capability fields fall back independently`() {
        val model = parseOpenAIModels(
            """
            {
              "data": [
                {
                  "id": "gpt-4o",
                  "architecture": {
                    "input_modalities": ["text"],
                    "output_modalities": ["future-modality"]
                  }
                }
              ]
            }
            """.trimIndent()
        ).single().let(ModelRegistry::resolveCapabilities)

        assertEquals(listOf(Modality.TEXT), model.inputModalities)
        assertEquals(listOf(Modality.TEXT), model.outputModalities)
        assertEquals(listOf(ModelAbility.TOOL), model.abilities)
    }
}
