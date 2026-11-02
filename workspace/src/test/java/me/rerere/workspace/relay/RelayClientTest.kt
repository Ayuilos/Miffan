package me.rerere.workspace.relay

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RelayClientTest {
    private val server = MockWebServer()
    private val info = """{"v":1,"impl":"worker","edges":["10.0.0.1","10.0.0.2"]}"""

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private val endpoint get() = RelayEndpoint(server.url("/").toString().trimEnd('/'))

    private class MemoryStore : RelayInfoStore {
        val values = ConcurrentHashMap<String, StoredRelayInfo>()
        override fun read(baseUrl: String) = values[baseUrl]
        override fun write(baseUrl: String, info: StoredRelayInfo) { values[baseUrl] = info }
    }

    @Test fun dnsIsAlwaysTheLastAttempt() {
        assertEquals(listOf("a", "b", null), RelayClient.entryAttempts(listOf("a", "b", "c", null)))
        assertEquals(listOf("a", null), RelayClient.entryAttempts(listOf("a", null, "b")))
        assertEquals(listOf(null), RelayClient.entryAttempts(listOf(null)))
    }

    @Test fun aSlowEntryDoesNotHoldUpTheChoice() {
        server.enqueue(MockResponse.Builder().body(info).build())
        val client = RelayClient(edgeProbe = { _, _, ip ->
            when (ip) {
                "10.0.0.1" -> { Thread.sleep(20); 20_000_000L }
                "10.0.0.2" -> { Thread.sleep(2_000); 1_000_000L } // slow to answer, but fastest handshake
                else -> { Thread.sleep(40); 40_000_000L }
            }
        })
        val started = System.nanoTime()
        val order = client.edges(endpoint, "wifi:1")
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $millis ms", millis < 1_000)
        assertEquals(listOf("10.0.0.1", null, "10.0.0.2"), order)
        // Once the slow probe finishes in the background, the remembered order uses its time.
        Thread.sleep(2_500)
        assertEquals(listOf("10.0.0.2", "10.0.0.1", null), client.edges(endpoint, "wifi:1"))
    }

    @Test fun aLoneCandidateIsNotProbed() {
        server.enqueue(MockResponse.Builder().body("""{"v":1,"edges":[]}""").build())
        val probed = CopyOnWriteArrayList<String?>()
        val client = RelayClient(edgeProbe = { _, _, ip -> probed += ip; 1L })
        assertEquals(listOf(null), client.edges(endpoint, "cell:2"))
        assertTrue(probed.isEmpty())
    }

    @Test fun storedInfoAnswersWithoutARequest() {
        val store = MemoryStore()
        store.write(endpoint.baseUrl, StoredRelayInfo(System.currentTimeMillis(), info))
        val client = RelayClient(infoStore = store)
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), client.info(endpoint).edges)
        assertEquals(0, server.requestCount)
    }

    @Test fun staleStoredInfoIsUsedAtOnceAndRefreshedInTheBackground() {
        val store = MemoryStore()
        store.write(endpoint.baseUrl, StoredRelayInfo(System.currentTimeMillis() - 7 * 60 * 60 * 1000L,
            """{"v":1,"edges":["10.0.0.9"]}"""))
        server.enqueue(MockResponse.Builder().body(info).headersDelay(500, TimeUnit.MILLISECONDS).build())
        val client = RelayClient(infoStore = store, edgeProbe = { _, _, ip -> if (ip == null) 1L else 2L })
        val started = System.nanoTime()
        assertEquals(listOf(null, "10.0.0.9"), client.edges(endpoint, "wifi:3"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 400)
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val until = System.nanoTime() + 3_000_000_000L
        while (!store.values[endpoint.baseUrl]!!.json.contains("10.0.0.2") && System.nanoTime() < until) Thread.sleep(50)
        assertTrue(store.values[endpoint.baseUrl]!!.json.contains("10.0.0.2"))
    }

    @Test fun fetchedInfoIsStored() {
        val store = MemoryStore()
        server.enqueue(MockResponse.Builder().body(info).build())
        RelayClient(infoStore = store).info(endpoint, refresh = true)
        assertEquals(info, store.values[endpoint.baseUrl]?.json)
    }
}
