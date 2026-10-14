package me.ayuilos.miffan.data.ai.openrouter

import kotlinx.coroutines.test.runTest
import me.ayuilos.miffan.data.ai.tools.canOutputImage
import me.ayuilos.miffan.data.ai.tools.imageToolModelOrNull
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.findModelById
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelType
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class OpenRouterImageModelsTest {
    private val sample = """{"data":[
        {"id":"image/older","name":"Older Image","description":"draws pictures","created":10,
         "context_length":32768,"pricing":{"prompt":"0.000001","completion":"0.000002","image":"0.04"},
         "architecture":{"input_modalities":["text","image"],"output_modalities":["text","image"]}},
        {"id":"text/only","architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
        {"id":"image/newer","created":20,"pricing":{},
         "architecture":{"input_modalities":["text"],"output_modalities":["image"]}},
        {"id":"no/capabilities"}
    ]}"""

    @Test fun parsesOnlyImageOutputsWithPricingAndNewestFirst() {
        val models = parseOpenRouterImageModels(sample)
        assertEquals(listOf("image/newer", "image/older"), models.map { it.id })
        val older = models.last()
        assertEquals("Older Image", older.name)
        assertEquals("draws pictures", older.description)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), older.inputModalities)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), older.outputModalities)
        assertEquals(mapOf("prompt" to "0.000001", "completion" to "0.000002", "image" to "0.04"), older.pricing)
        assertEquals(32768, older.contextLength)
        assertEquals(10L, older.created)
        assertNull(models.first().description)
        assertNull(models.first().contextLength)
    }

    @Test fun addsAndSelectsImageChatModelAndReusesItsUuid() {
        val discovered = parseOpenRouterImageModels(sample).last()
        val settings = Settings().withOpenRouterConnection("test-key")
        val (added, uuid) = requireNotNull(settings.withOpenRouterImageModel(discovered))
        val stored = requireNotNull(added.findModelById(uuid))
        assertEquals(uuid, added.imageToolModelId)
        assertEquals(settings.imageGenerationModelId, added.imageGenerationModelId)
        assertEquals(settings.chatModelId, added.chatModelId)
        assertEquals(ModelType.CHAT, stored.type)
        assertEquals(discovered.inputModalities, stored.inputModalities)
        assertEquals(discovered.outputModalities, stored.outputModalities)
        assertTrue(stored.canOutputImage())
        assertEquals(stored, added.imageToolModelOrNull())
        assertNull(stored.discoveredCapabilities)
        val (reused, sameUuid) = requireNotNull(added.withOpenRouterImageModel(discovered))
        assertEquals(uuid, sameUuid)
        assertEquals(1, reused.openRouterProvider()!!.models.count { it.modelId == discovered.id })
    }

    @Test fun repairsExistingTextOnlyEntryWhilePreservingConfiguration() {
        val discovered = parseOpenRouterImageModels(sample).last()
        val connected = Settings().withOpenRouterConnection("test-key")
        val existing = me.rerere.ai.provider.Model(modelId = discovered.id, displayName = "My label")
        val provider = connected.openRouterProvider()!!.copy(models = listOf(existing))
        val (updated, uuid) = requireNotNull(connected.copy(providers = listOf(provider)).withOpenRouterImageModel(discovered))
        assertEquals(existing.id, uuid)
        assertEquals("My label", updated.imageToolModelOrNull()!!.displayName)
        assertTrue(updated.imageToolModelOrNull()!!.canOutputImage())
    }

    @Test fun requiresConnectedOpenRouterProvider() {
        val model = parseOpenRouterImageModels(sample).first()
        assertNull(Settings(providers = emptyList()).withOpenRouterImageModel(model))
        val connected = Settings().withOpenRouterConnection("test-key")
        assertNull(connected.copy(providers = listOf(connected.openRouterProvider()!!.copy(apiKey = "")))
            .withOpenRouterImageModel(model))
    }

    @Test fun catalogRequestsUseOptionalBearerCacheAndForceRefresh() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(sample.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val service = OpenRouterImageModelService(client)
        assertEquals(2, service.list(null).getOrThrow().size)
        assertEquals("https://openrouter.ai/api/v1/models?output_modalities=image", requests.single().url.toString())
        assertNull(requests.single().header("Authorization"))
        assertEquals(2, service.list("test-key").getOrThrow().size)
        assertEquals(1, requests.size)
        assertEquals(2, service.list(" test-key ", forceRefresh = true).getOrThrow().size)
        assertEquals(2, requests.size)
        assertEquals("Bearer test-key", requests.last().header("Authorization"))
    }

    @Test fun failedCatalogRefreshReturnsFailureAndRetainsLastSuccessfulCache() = runTest {
        var fail = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (fail) 500 else 200).message("Response")
                .body(sample.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val service = OpenRouterImageModelService(client)
        val cached = service.list(null).getOrThrow()
        fail = true
        assertTrue(service.list(null, forceRefresh = true).isFailure)
        assertEquals(cached, service.list(null).getOrThrow())
    }
}
