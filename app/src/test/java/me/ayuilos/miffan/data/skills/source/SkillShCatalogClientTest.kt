package me.ayuilos.miffan.data.skills.source

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillShCatalogClientTest {
    @Test
    fun `search accepts bare and stable ids while filtering invalid entries`() = runBlocking {
        val client = SkillShCatalogClient(
            fakeClient { request ->
                assertEquals("skills.sh", request.url.host)
                assertEquals("/api/search", request.url.encodedPath)
                assertEquals("pdf tools", request.url.queryParameter("q"))
                jsonResponse(
                    request,
                    """
                        {
                          "skills": [
                            {"id":"reader","name":"PDF Reader","installs":20,"source":"acme/skills"},
                            {"id":"acme/skills/writer","name":"PDF Writer","installs":10,"source":"acme/skills"},
                            {"id":"other/repo/injected","name":"Wrong source","installs":99,"source":"acme/skills"},
                            {"id":"../bad","name":"Bad","installs":99,"source":"acme/skills"}
                          ],
                          "ignored": "field"
                        }
                    """.trimIndent(),
                )
            },
        )

        val result = client.search(" pdf tools ")

        assertTrue(result.available)
        assertEquals("unstable", result.stability)
        assertEquals(listOf("acme/skills/reader", "acme/skills/writer"), result.entries.map { it.catalogId })
        assertEquals("https://skills.sh/acme/skills/writer", result.entries.last().pageUrl)
    }

    @Test
    fun `search degrades to unavailable for transport and query failures`() = runBlocking {
        val transportFailure = SkillShCatalogClient(
            fakeClient { throw IOException("offline") },
        ).search("reader")
        val invalidQuery = SkillShCatalogClient(
            fakeClient { error("network must not be used") },
        ).search("x")

        assertFalse(transportFailure.available)
        assertTrue(transportFailure.entries.isEmpty())
        assertFalse(invalidQuery.available)
        assertTrue(invalidQuery.entries.isEmpty())
    }

    @Test
    fun `cancelling search cancels the active okhttp call`() = runBlocking {
        val activeCall = AtomicReference<Call?>()
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    activeCall.set(call)
                }
            })
            .addInterceptor(Interceptor { chain ->
                while (!chain.call().isCanceled()) {
                    Thread.sleep(5)
                }
                throw IOException("cancelled")
            })
            .build()
        val job = async(Dispatchers.IO) {
            SkillShCatalogClient(client).search("reader")
        }

        val call = withTimeout(1_000) {
            while (activeCall.get() == null) kotlinx.coroutines.yield()
            activeCall.get()!!
        }
        job.cancelAndJoin()

        assertTrue(call.isCanceled())
    }

    private fun fakeClient(handler: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> handler(chain.request()) })
        .build()

    private fun jsonResponse(request: Request, body: String, code: Int = 200): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
}
