package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ImageOutputRequestTest {
    @Test fun imageModalitiesAreSentToEveryChatCompletionsGatewayButSessionIdIsOpenRouterOnly() {
        val api = ChatCompletionsAPI(OkHttpClient(), me.rerere.ai.util.KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest", List::class.java, TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        for (host in listOf("https://openrouter.ai/api/v1", "https://gateway.example/v1")) {
            val params = TextGenerationParams(model = Model(modelId = "image-model", outputModalities = listOf(Modality.TEXT, Modality.IMAGE)), sessionId = "session")
            val body = method.invoke(api, listOf(UIMessage.user("Draw a bowl")), params, ProviderSetting.OpenAI(baseUrl = host), false) as JsonObject
            assertEquals(listOf("image", "text"), body["modalities"]!!.jsonArray.map { it.jsonPrimitive.content })
            val imageOnlyBody = method.invoke(api, listOf(UIMessage.user("Draw a bowl")),
                params.copy(model = Model(modelId = "flux", outputModalities = listOf(Modality.IMAGE))),
                ProviderSetting.OpenAI(baseUrl = host), false) as JsonObject
            assertEquals(listOf("image"), imageOnlyBody["modalities"]!!.jsonArray.map { it.jsonPrimitive.content })
            if (host.contains("openrouter.ai")) assertEquals("session", body["session_id"]!!.jsonPrimitive.content)
            else assertNull(body["session_id"])
            val textBody = method.invoke(api, listOf(UIMessage.user("Hello")), params.copy(model = Model(modelId = "text-model")),
                ProviderSetting.OpenAI(baseUrl = host), false) as JsonObject
            assertNull(textBody["modalities"])
        }
    }

    @Test fun responsesOptionsBelongToImageToolAndUserToolConfigurationWins() {
        val api = ResponseAPI(OkHttpClient())
        val params = TextGenerationParams(
            model = Model(modelId = "driver", tools = setOf(BuiltInTools.ImageGeneration)),
            imageGenerationOptions = listOf(CustomBody("size", JsonPrimitive("1536x1024")),
                CustomBody("quality", JsonPrimitive("high")), CustomBody("output_format", JsonNull)),
        )
        val body = api.buildRequestBody(ProviderSetting.OpenAI(), listOf(UIMessage.user("Draw")), params, false)
        assertFalse("size" in body)
        assertFalse("quality" in body)
        val imageTool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("image_generation", imageTool["type"]!!.jsonPrimitive.content)
        assertEquals("1536x1024", imageTool["size"]!!.jsonPrimitive.content)
        assertEquals("high", imageTool["quality"]!!.jsonPrimitive.content)
        assertFalse("output_format" in imageTool)
        val defaults = api.buildRequestBody(ProviderSetting.OpenAI(), emptyList(), params.copy(imageGenerationOptions = emptyList()), false)
        assertEquals(setOf("type", "model"), defaults["tools"]!!.jsonArray.single().jsonObject.keys)
        val userTools = buildJsonArray { add(buildJsonObject { put("type", "image_generation"); put("quality", "low") }) }
        val overridden = api.buildRequestBody(ProviderSetting.OpenAI(), emptyList(), params.copy(customBody = listOf(CustomBody("tools", userTools))), false)
        assertEquals(userTools, overridden["tools"])
    }

    @Test fun chatImageOptionsMergeIntoRequestRoot() {
        val api = ChatCompletionsAPI(OkHttpClient(), me.rerere.ai.util.KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest", List::class.java, TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val params = TextGenerationParams(Model(modelId = "image", outputModalities = listOf(Modality.IMAGE)),
            customBody = listOf(CustomBody("image_config", buildJsonObject { put("aspect_ratio", "16:9") }), CustomBody("modalities", JsonNull)))
        val body = method.invoke(api, listOf(UIMessage.user("Draw")), params, ProviderSetting.OpenAI(), false) as JsonObject
        assertEquals("16:9", body["image_config"]!!.jsonObject["aspect_ratio"]!!.jsonPrimitive.content)
        assertFalse("modalities" in body)
    }

    @Test fun nonStreamingResponsesImageGenerationCallsProduceImagesWithCorrectMime() {
        val api = ResponseAPI(OkHttpClient())
        val result = api.parseResponseOutput(json.parseToJsonElement("""{
            "id":"resp_image","model":"image-model","status":"completed","output":[
                {"type":"image_generation_call","id":"image_1","status":"completed","result":"cG5n"},
                {"type":"image_generation_call","id":"image_2","status":"completed","output_format":"jpeg","result":"anBlZw=="},
                {"type":"message","content":[{"type":"output_text","text":"Made two images."}]}
            ]
        }""").jsonObject)
        assertEquals(listOf("data:image/png;base64,cG5n", "data:image/jpeg;base64,anBlZw=="),
            result.message.parts.filterIsInstance<UIMessagePart.Image>().map { it.url })
        assertEquals("Made two images.", result.message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test fun refusalsWithoutImageDataRemainReadable() {
        val api = ResponseAPI(OkHttpClient())
        val result = api.parseResponseOutput(json.parseToJsonElement("""{"output":[
            {"type":"image_generation_call","id":"failed_image","status":"failed","result":null},
            {"type":"message","content":[{"type":"refusal","refusal":"Cannot draw this."}]}
        ]}""").jsonObject)
        assertTrue(result.message.parts.filterIsInstance<UIMessagePart.Image>().isEmpty())
        assertEquals("Cannot draw this.", result.message.toText())
    }

    @Test fun subscriptionStreamingPathKeepsFinalImageInsteadOfPartialPreview() = runBlocking {
        val decoder = ResponseApiStreamDecoder()
        val chunks = listOf(
            """{"type":"response.output_item.added","item":{"id":"image_1","type":"image_generation_call","status":"in_progress"}}""",
            """{"type":"response.image_generation_call.partial_image","item_id":"image_1","partial_image_b64":"cHJldmlldw=="}""",
            """{"type":"response.output_item.done","item":{"id":"image_1","type":"image_generation_call","status":"completed","result":"ZmluYWw="}}""",
            """{"type":"response.completed","response":{"id":"resp_image","model":"image-model","status":"completed"}}""",
        ).flatMap { decoder.accept(SseEvent(data = it)).chunks }
        val result = collectStreamingTextGeneration(Model(modelId = "image-model"), kotlinx.coroutines.flow.flowOf(*chunks.toTypedArray()))
        assertEquals("data:image/png;base64,ZmluYWw=", result.message.parts.filterIsInstance<UIMessagePart.Image>().single().url)
    }
}
