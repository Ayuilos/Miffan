package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.OPENAI_CODEX_BASE_URL
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.OpenAICodexCredentials
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class OpenAIProviderModelsTest {
    private val magpieImageRow = """{
        "id":"codex/gpt-image-2","object":"model","type":"model","kind":"image","created":0,"owned_by":"codex",
        "display_name":"GPT Image","magpie_label":"GPT Image · ChatGPT",
        "modalities":{"input":["text","image"],"output":["image"]}
    }"""

    @Test
    fun `model list opts into Magpie drawers except for subscription auth`() = runBlocking {
        for (auth in OpenAIAuthType.entries) {
            var captured: okhttp3.Request? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                captured = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"data":[]}""".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val settings = ProviderSetting.OpenAI(
                authType = auth,
                apiKey = "test-key",
                baseUrl = if (auth == OpenAIAuthType.CHATGPT_SUBSCRIPTION) OPENAI_CODEX_BASE_URL else "http://localhost:3425/v1",
                codexCredentials = OpenAICodexCredentials(accessToken = "test-token", refreshToken = "test-refresh", accountId = "test-account"),
            )
            assertTrue(OpenAIProvider(client).listModels(settings).isEmpty())
            val request = requireNotNull(captured)
            assertEquals("GET", request.method)
            if (auth == OpenAIAuthType.CHATGPT_SUBSCRIPTION) assertNull(request.header("X-Magpie-Drawers"))
            else assertEquals("1", request.header("X-Magpie-Drawers"))
            assertNull(request.header("X-Magpie-Videomakers"))
            assertNull(request.header("X-Magpie-Deciders"))
        }
    }

    @Test
    fun `Magpie image model keeps type and modalities through model addition and persistence`() {
        val fetched = parseOpenAIModels("""{"data":[$magpieImageRow]}""").single()
        assertEquals(ModelType.IMAGE, fetched.type)
        assertEquals("GPT Image", fetched.displayName)
        val discovered = requireNotNull(fetched.discoveredCapabilities)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), discovered.inputModalities)
        assertEquals(listOf(Modality.IMAGE), discovered.outputModalities)
        // Mirrors the provider settings page: resolve metadata, then pass the model to addModel unchanged.
        val resolved = ModelRegistry.resolveCapabilities(fetched)
        val provider = ProviderSetting.OpenAI(models = emptyList()).addModel(resolved)
        val restored = Json.decodeFromString<ProviderSetting.OpenAI>(Json.encodeToString(provider as ProviderSetting.OpenAI))
        val stored = restored.models.single()
        assertEquals(fetched.id, stored.id)
        assertEquals(ModelType.IMAGE, stored.type)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), stored.inputModalities)
        assertEquals(listOf(Modality.IMAGE), stored.outputModalities)
        assertNull(stored.discoveredCapabilities)
    }

    @Test
    fun `Magpie video and decision rows are skipped while plain text rows are unchanged`() {
        val models = parseOpenAIModels("""{"data":[
            $magpieImageRow,
            {"id":"video-model","kind":"video","modalities":{"output":["video"]}},
            {"id":"decision-model","kind":"decision","modalities":{"output":["text"]}},
            {"id":"plain-text","object":"model"}
        ]}""")
        assertEquals(listOf("codex/gpt-image-2", "plain-text"), models.map { it.modelId })
        val text = models.last()
        assertEquals(ModelType.CHAT, text.type)
        assertEquals(listOf(Modality.TEXT), text.inputModalities)
        assertEquals(listOf(Modality.TEXT), text.outputModalities)
        assertNull(text.discoveredCapabilities)
    }

    @Test
    fun `Magpie modalities object is a fallback after architecture and top level fields`() {
        val models = parseOpenAIModels("""{"data":[
            {"id":"architecture-first","architecture":{"input_modalities":["text"],"output_modalities":["text"]},
             "input_modalities":["image"],"output_modalities":["image"],"modalities":{"input":["audio"],"output":["audio"]}},
            {"id":"top-level-first","input_modalities":["text"],"output_modalities":["text"],
             "modalities":{"input":["image"],"output":["image"]}},
            {"id":"object-only","modalities":{"input":["text","image"],"output":["text"]}}
        ]}""").map(ModelRegistry::resolveCapabilities)
        assertEquals(listOf(Modality.TEXT), models[0].inputModalities)
        assertEquals(listOf(Modality.TEXT), models[0].outputModalities)
        assertEquals(listOf(Modality.TEXT), models[1].inputModalities)
        assertEquals(listOf(Modality.TEXT), models[1].outputModalities)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), models[2].inputModalities)
        assertEquals(listOf(Modality.TEXT), models[2].outputModalities)
    }

    @Test
    fun `only explicit image kind uses Images API type and missing modalities still output image`() {
        val models = parseOpenAIModels("""{"data":[
            {"id":"image-without-modalities","kind":"image"},
            {"id":"google/gemini-2.5-flash-image","architecture":{"input_modalities":["text","image"],"output_modalities":["text","image"]}},
            {"id":"chat-with-object-modalities","modalities":{"input":["text","image"],"output":["image"]}}
        ]}""").map(ModelRegistry::resolveCapabilities)
        assertEquals(ModelType.IMAGE, models[0].type)
        assertEquals(listOf(Modality.IMAGE), models[0].outputModalities)
        assertEquals(ModelType.CHAT, models[1].type)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), models[1].outputModalities)
        assertEquals(ModelType.CHAT, models[2].type)
        assertEquals(listOf(Modality.IMAGE), models[2].outputModalities)
    }

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
