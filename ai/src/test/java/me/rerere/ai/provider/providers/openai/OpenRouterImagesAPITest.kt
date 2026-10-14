package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.util.json
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenRouterImagesAPITest {
    @get:Rule val temporary = TemporaryFolder()
    private val provider = ProviderSetting.OpenAI(baseUrl = "https://openrouter.ai/api/v1/")
    private val flux = Model(modelId = "black-forest-labs/flux-3-image", outputModalities = listOf(Modality.IMAGE))

    @Test fun generationUsesDedicatedEndpointAndOnlyImageApiFields() {
        val request = openRouterImagesRequestBuilder(provider, flux, "Draw a bowl", 1, "auto").build()
        assertEquals("https://openrouter.ai/api/v1/images", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("application/json", request.header("Content-Type"))
        val body = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals(setOf("model", "prompt", "n", "size"), body.keys)
        assertEquals(flux.modelId, body["model"]!!.jsonPrimitive.content)
        assertEquals("Draw a bowl", body["prompt"]!!.jsonPrimitive.content)
        assertEquals("auto", body["size"]!!.jsonPrimitive.content)
    }

    @Test fun editsEncodeLocalFileAndPreserveHttpsAndDataReferences() {
        val file = temporary.newFile("reference with spaces.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val url = "https://images.example/reference.webp"
        val data = "data:image/png;base64,AQID"
        val request = openRouterImagesRequestBuilder(provider, flux, "Edit the bowl", 2, "1024x1024",
            images = listOf(file.toURI().toString(), url, data),
            customHeaders = listOf(CustomHeader("X-Test", "header")),
            customBody = listOf(CustomBody("seed", JsonPrimitive(42))),
        ).build()
        assertEquals("header", request.header("X-Test"))
        val body = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
        val references = body["input_references"]!!.jsonArray
        assertEquals(listOf("data:image/jpeg;base64,AQID", url, data), references.map {
            assertEquals("image_url", it.jsonObject["type"]!!.jsonPrimitive.content)
            it.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        })
        assertEquals(42, body["seed"]!!.jsonPrimitive.int)
        assertFalse("image" in body)
        assertFalse("images" in body)
        assertFalse("response_format" in body)
        assertFalse("partial_images" in body)
    }

    @Test fun imageOnlyRoutingIsLimitedToOpenRouterAndKeepsGeminiOnChatCompletions() {
        assertEquals(ModelType.CHAT, flux.type)
        assertTrue(flux.usesOpenRouterImagesApi(provider))
        assertFalse(flux.copy(outputModalities = listOf(Modality.TEXT, Modality.IMAGE)).usesOpenRouterImagesApi(provider))
        assertFalse(flux.copy(outputModalities = listOf(Modality.TEXT)).usesOpenRouterImagesApi(provider))
        assertFalse(flux.usesOpenRouterImagesApi(provider.copy(baseUrl = "https://gateway.example/v1")))
        assertFalse(flux.usesOpenRouterImagesApi(ProviderSetting.Google()))
    }

    @Test fun responsesReadBase64AndMediaTypeWithExistingMimeAndFormatFallbacks() {
        val root = json.parseToJsonElement("""{"data":[
            {"b64_json":"AQID","media_type":"image/webp"},
            {"b64_json":"BAUG","mime_type":"image/jpeg"},
            {"b64_json":"BwgJ","output_format":"webp"},
            {"b64_json":"Cg=="}
        ]}""").jsonObject
        val images = root["data"]!!.jsonArray.map { it.jsonObject.base64ImageOrNull()!! }
        assertEquals(listOf("image/webp", "image/jpeg", "image/webp", "image/png"), images.map { it.mimeType })
        assertEquals(listOf("AQID", "BAUG", "BwgJ", "Cg=="), images.map { it.data })
        assertNull(buildJsonObject { put("url", "https://images.example/a.png") }.base64ImageOrNull())
    }
}
