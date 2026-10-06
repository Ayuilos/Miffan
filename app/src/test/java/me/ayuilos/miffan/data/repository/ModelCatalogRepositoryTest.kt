package me.ayuilos.miffan.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.registry.ModelCatalog
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class ModelCatalogRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = mutableListOf<OkHttpClient>()
    private val failures = CopyOnWriteArrayList<Throwable>()
    private val requests = CopyOnWriteArrayList<Request>()
    private var time = 1_800_000_000_000L
    private val raw = """{"lab/new-model":{"tool_call":true,"reasoning":true,"modalities":{"input":["text","image","pdf"],"output":["text"]}}}"""
    private val snapshot = """{"models":{"lab/new-model":{"tool_call":false,"reasoning":false,"modalities":{"input":["text"],"output":["text"]}}},"aliases":{"gateway":"lab/new-model"}}"""

    @After
    fun tearDown() {
        scope.cancel()
        clients.forEach { client ->
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun repository(
        file: File,
        asset: () -> String = { snapshot },
        status: Int = 200,
        body: String = raw,
        failure: IOException? = null,
    ): ModelCatalogRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            failure?.let { throw it }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Catalog response")
                .header("ETag", "\"updated\"")
                .body(body.toResponseBody())
                .build()
        }.build()
        clients += client
        return ModelCatalogRepository(file, asset, client, scope, now = { time }, logFailure = { failures += it })
    }

    @Test
    fun `parseable cache wins over asset and loading happens off caller thread`() = runBlocking {
        val cache = temporaryFolder.newFile("model-catalog.json").apply { writeText(snapshot) }
        var assetRead = false
        val repository = repository(cache, asset = { assetRead = true; error("Asset should not load") })
        repository.awaitLoaded()
        assertEquals(emptyList<ModelAbility>(), repository.catalog.value.lookup("gateway")?.abilities)
        assertFalse(assetRead)

        val callerThread = Thread.currentThread()
        var assetThread: Thread? = null
        val fallback = repository(temporaryFolder.newFile("bad.json"), asset = {
            assetThread = Thread.currentThread()
            snapshot
        })
        fallback.awaitLoaded()
        assertTrue(assetThread !== callerThread)
        assertEquals(emptyList<ModelAbility>(), fallback.catalog.value.lookup("gateway")?.abilities)
    }

    @Test
    fun `corrupt or absent cache falls back to asset then empty on parse or read failure`() = runBlocking {
        listOf("garbage", "{}", "[]").forEachIndexed { index, invalid ->
            val cache = temporaryFolder.newFile("bad-$index.json").apply { writeText(invalid) }
            val fallback = repository(cache)
            fallback.awaitLoaded()
            assertEquals(emptyList<ModelAbility>(), fallback.catalog.value.lookup("new-model")?.abilities)
            val empty = repository(cache, asset = { invalid })
            empty.awaitLoaded()
            assertSame(ModelCatalog.EMPTY, empty.catalog.value)
        }
        val missing = repository(File(temporaryFolder.root, "absent.json"), asset = { throw IOException("No asset") })
        missing.awaitLoaded()
        assertSame(ModelCatalog.EMPTY, missing.catalog.value)
    }

    @Test
    fun `200 preserves aliases writes snapshot and persists throttle and etag`() = runBlocking {
        val cache = File(temporaryFolder.root, "model-catalog.json")
        val repository = repository(cache)
        repository.refreshIfStale()
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), repository.catalog.value.lookup("gateway")?.abilities)
        assertEquals(repository.catalog.value.lookup("gateway"), ModelCatalog.parseSnapshot(cache.readText()).lookup("gateway"))
        assertTrue(cache.readText().contains("\"fetchedAt\""))
        assertFalse(File(temporaryFolder.root, "model-catalog.json.tmp").exists())
        assertEquals("GET", requests.single().method)
        assertEquals("https://models.dev/models.json", requests.single().url.toString())
        assertNull(requests.single().header("Authorization"))
        assertNull(requests.single().header("If-None-Match"))
        val restarted = repository(cache)
        restarted.refreshIfStale()
        assertEquals(1, requests.size)
        time += 24 * 60 * 60 * 1000L
        restarted.refreshIfStale()
        assertEquals(2, requests.size)
        assertEquals("\"updated\"", requests.last().header("If-None-Match"))
    }

    @Test
    fun `304 retains catalog and cache while bumping persisted timestamp`() = runBlocking {
        val cache = temporaryFolder.newFile("model-catalog.json").apply { writeText(snapshot) }
        val metadata = File(temporaryFolder.root, "model-catalog.json.metadata")
        metadata.writeText("""{"lastSuccess":0,"etag":"original"}""")
        val repository = repository(cache, status = 304, body = "")
        repository.awaitLoaded()
        val before = repository.catalog.value
        repository.refreshIfStale()
        assertSame(before, repository.catalog.value)
        assertEquals(snapshot, cache.readText())
        assertEquals("original", requests.single().header("If-None-Match"))
        assertTrue(metadata.readText().contains(time.toString()))
        repository(cache).refreshIfStale()
        assertEquals(1, requests.size)
    }

    @Test
    fun `corrupt cache does not send its old validator or throttle fallback`() = runBlocking {
        val cache = temporaryFolder.newFile("model-catalog.json").apply { writeText("[]") }
        File(temporaryFolder.root, "model-catalog.json.metadata").writeText("""{"lastSuccess":$time,"etag":"old"}""")
        repository(cache).refreshIfStale()
        assertEquals(1, requests.size)
        assertNull(requests.single().header("If-None-Match"))
    }

    @Test
    fun `HTTP parse and network failures retain current catalog and cache`() = runBlocking {
        val cache = temporaryFolder.newFile("model-catalog.json").apply { writeText(snapshot) }
        val repositories = listOf(
            repository(cache, status = 503),
            repository(cache, body = "[]"),
            repository(cache, failure = IOException("Offline")),
        )
        repositories.forEach { repository ->
            repository.awaitLoaded()
            val before = repository.catalog.value
            repository.refreshIfStale()
            assertSame(before, repository.catalog.value)
            assertEquals(snapshot, cache.readText())
        }
        assertEquals(3, failures.size)
        assertFalse(File(temporaryFolder.root, "model-catalog.json.metadata").exists())
    }

    @Test
    fun `concurrent refreshes issue one request per successful interval`() = runBlocking {
        val repository = repository(File(temporaryFolder.root, "model-catalog.json"))
        coroutineScope {
            repeat(3) { launch { repository.refreshIfStale() } }
        }
        assertEquals(1, requests.size)
    }
}
