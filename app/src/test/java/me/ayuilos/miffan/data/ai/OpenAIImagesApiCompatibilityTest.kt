package me.ayuilos.miffan.data.ai

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class OpenAIImagesApiCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val model = Model(modelId = "codex/gpt-image-2", type = ModelType.IMAGE)
    private val setting = ProviderSetting.OpenAI(baseUrl = "http://localhost:3425/v1", apiKey = "magpie")
    private val bytes = byteArrayOf(1, 2, 3, 4)

    @Before fun stubAndroidLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
    }
    @After fun restoreAndroidLog() = unmockkStatic(Log::class)

    @Test fun generationHandlesBothBase64AndDownloadedUrlImages() = runTest {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val download = request.url.encodedPath == "/generated.webp"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(if (download) bytes.toResponseBody("image/webp".toMediaType()) else
                    """{"data":[{"b64_json":"AQIDBA==","mime_type":"image/jpeg"},{"url":"https://images.example/generated.webp"}]}"""
                        .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val images = OpenAIProvider(client).generateImage(setting, ImageGenerationParams(model, "Draw a bowl")).toList()
        assertEquals(2, images.size)
        assertEquals(listOf("image/jpeg", "image/webp"), images.map { it.mimeType })
        images.forEach { assertArrayEquals(bytes, Base64.getDecoder().decode(it.data)) }
        assertEquals("/v1/images/generations", requests.first().url.encodedPath)
        assertEquals("POST", requests.first().method)
        val body = Json.parseToJsonElement(Buffer().also { requests.first().body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals(model.modelId, body["model"]!!.jsonPrimitive.content)
        assertEquals("Draw a bowl", body["prompt"]!!.jsonPrimitive.content)
        assertEquals("GET", requests.last().method)
    }

    @Test fun editsSendMultipartFilesWithMagpieAcceptedFieldNames() = runTest {
        for (count in listOf(1, 2)) {
            var captured: Request? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                captured = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"data":[{"b64_json":"AQIDBA=="}]}""".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val files = List(count) { temporary.newFile("reference-$count-$it.png").apply { writeBytes(bytes) } }
            val images = OpenAIProvider(client).editImage(setting, ImageEditParams(
                model = model, prompt = "Edit this bowl", images = files.map { it.absolutePath },
            )).toList()
            assertArrayEquals(bytes, Base64.getDecoder().decode(images.single().data))
            val request = requireNotNull(captured)
            assertEquals("/v1/images/edits", request.url.encodedPath)
            val body = request.body as MultipartBody
            assertEquals("multipart/form-data", body.type.toString())
            val fileParts = body.parts.filter { it.headers!!["Content-Disposition"]!!.contains("filename=") }
            assertEquals(count, fileParts.size)
            fileParts.forEach { part ->
                val field = if (count == 1) "image" else "image[]"
                assertTrue(part.headers!!["Content-Disposition"]!!.contains("name=\"$field\""))
                assertEquals("image/png", part.body.contentType().toString())
                assertArrayEquals(bytes, Buffer().also { part.body.writeTo(it) }.readByteArray())
            }
        }
    }

    @Test fun openRouterGenerationAndEditsUseAuthenticatedJsonImagesEndpointAndMediaType() = runTest {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":[{"b64_json":"AQIDBA==","media_type":"image/webp"}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val provider = OpenAIProvider(client)
        val setting = setting.copy(baseUrl = "https://openrouter.ai/api/v1", apiKey = "test-key")
        val model = model.copy(modelId = "black-forest-labs/flux-3-image")
        val generated = provider.generateImage(setting, ImageGenerationParams(model, "Draw a bowl")).toList()
        assertEquals("image/webp", generated.single().mimeType)
        assertArrayEquals(bytes, Base64.getDecoder().decode(generated.single().data))
        val file = temporary.newFile("reference.png").apply { writeBytes(bytes) }
        val url = "https://images.example/reference.png"
        val edited = provider.editImage(setting, ImageEditParams(model, "Edit the bowl", listOf(file.absolutePath, url))).toList()
        assertEquals("image/webp", edited.single().mimeType)
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals("https://openrouter.ai/api/v1/images", request.url.toString())
            assertEquals("POST", request.method)
            assertEquals("Bearer test-key", request.header("Authorization"))
            assertEquals("application/json", request.header("Content-Type"))
            assertFalse(request.body is MultipartBody)
        }
        val editBody = Json.parseToJsonElement(Buffer().also { requests.last().body!!.writeTo(it) }.readUtf8()).jsonObject
        val references = editBody["input_references"]!!.jsonArray.map { it.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content }
        assertEquals(listOf("data:image/png;base64,AQIDBA==", url), references)
    }
}
