package me.rerere.cuaspace

import cua.env.v1.EntryInfo
import cua.env.v1.FileType
import cua.env.v1.HealthResponse
import cua.env.v1.HealthStatus
import cua.env.v1.ListDirResponse
import cua.env.v1.ProcessData
import cua.env.v1.ProcessEnd
import cua.env.v1.ProcessEvent
import cua.env.v1.ProcessStart
import cua.env.v1.StartProcessRequest
import cua.env.v1.StartProcessResponse
import com.squareup.wire.Message
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.Protocol
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SpaceClientTest {
    private val server = MockWebServer()
    private val opened = mutableListOf<Int>()

    /** Opens a real TCP socket to the mock server, standing in for an SSH direct-tcpip channel. */
    private val opener = SpaceStreamOpener { port, _ ->
        opened += port
        val socket = Socket("127.0.0.1", port)
        object : SpaceStream {
            override val input: InputStream = socket.getInputStream()
            override val output: OutputStream = socket.getOutputStream()
            override val isConnected: Boolean get() = !socket.isClosed
            override fun close() = socket.close()
        }
    }

    @Before fun start() {
        server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        server.start()
    }

    @After fun stop() = server.close()

    private fun client() = SpaceClient(opener, server.port, "secret-token")

    private fun grpcOk(vararg messages: Message<*, *>) = MockResponse.Builder()
        .addHeader("content-type", "application/grpc")
        .body(Buffer().apply { messages.forEach { SpaceGrpc.writeFrame(this, it.encode()) } })
        .trailers(headersOf("grpc-status", "0"))
        .build()

    @Test fun unaryCallGoesThroughTheOpenerWithTheToken() = runBlocking {
        server.enqueue(grpcOk(HealthResponse(status = HealthStatus.HEALTH_STATUS_SERVING)))
        val health = client().health()
        assertEquals(HealthStatus.HEALTH_STATUS_SERVING, health.status)
        val request = server.takeRequest()
        assertEquals("/cua.env.v1.SystemService/Health", request.url.encodedPath)
        assertEquals("Bearer secret-token", request.headers["authorization"])
        assertEquals("application/grpc", request.headers["content-type"]?.substringBefore(';'))
        assertEquals(listOf(server.port), opened)
    }

    @Test fun trailerStatusBecomesAnException() = runBlocking {
        server.enqueue(MockResponse.Builder()
            .addHeader("content-type", "application/grpc")
            .body(Buffer())
            .trailers(headersOf("grpc-status", "5", "grpc-message", "no%20such%20file"))
            .build())
        assertEquals(null, client().stat("/home/cua/missing"))

        server.enqueue(MockResponse.Builder()
            .addHeader("content-type", "application/grpc")
            .body(Buffer())
            .trailers(headersOf("grpc-status", "7", "grpc-message", "denied"))
            .build())
        try {
            client().list("/root")
            fail("expected PERMISSION_DENIED")
        } catch (error: SpaceGrpcException) {
            assertEquals(SpaceGrpcException.PERMISSION_DENIED, error.status)
            assertEquals("denied", error.message)
        }
    }

    @Test fun trailersOnlyErrorIsReported() = runBlocking {
        server.enqueue(MockResponse.Builder()
            .addHeader("content-type", "application/grpc")
            .addHeader("grpc-status", "16")
            .addHeader("grpc-message", "bad token")
            .build())
        try {
            client().health()
            fail("expected UNAUTHENTICATED")
        } catch (error: SpaceGrpcException) {
            assertEquals(SpaceGrpcException.UNAUTHENTICATED, error.status)
        }
    }

    @Test fun listFollowsPagesAndMapsEntries() = runBlocking {
        server.enqueue(grpcOk(ListDirResponse(
            entries = listOf(EntryInfo(name = "a.txt", path = "/home/cua/a.txt", type = FileType.FILE_TYPE_FILE, size = 3)),
            next_page_token = "p2",
        )))
        server.enqueue(grpcOk(ListDirResponse(
            entries = listOf(EntryInfo(name = "交接", path = "/home/cua/交接", type = FileType.FILE_TYPE_DIRECTORY)),
        )))
        val entries = client().list("/home/cua")
        assertEquals(listOf("a.txt", "交接"), entries.map { it.name })
        assertTrue(entries[1].isDirectory)
        assertEquals(3L, entries[0].sizeBytes)
    }

    @Test fun runCollectsOutputAndExitCode() = runBlocking {
        fun event(e: ProcessEvent) = StartProcessResponse(event = e)
        server.enqueue(grpcOk(
            event(ProcessEvent(start = ProcessStart(pid = 7))),
            event(ProcessEvent(data_ = ProcessData(stdout = "hello ".encodeUtf8()))),
            event(ProcessEvent(data_ = ProcessData(stderr = "warn".encodeUtf8()))),
            event(ProcessEvent(data_ = ProcessData(stdout = "world".encodeUtf8()))),
            event(ProcessEvent(end = ProcessEnd(exit_code = 3))),
        ))
        val result = client().run("echo hi", "/home/cua", timeoutMillis = 5_000, maxOutputBytes = 8)
        assertEquals(3, result.exitCode)
        // The 8-byte budget is shared: "hello " (6) then "wa" of stderr, nothing of "world".
        assertEquals("hello ", result.stdout)
        assertEquals("wa", result.stderr)
        assertTrue(result.truncated)
        assertFalse(result.timedOut)
        val body = server.takeRequest().body!!.toByteArray()
        val sent = StartProcessRequest.ADAPTER.decode(body.copyOfRange(5, body.size))
        assertEquals("/bin/sh", sent.config?.command)
        assertEquals(listOf("-c", "echo hi"), sent.config?.args)
        assertTrue(sent.kill_on_disconnect)
    }

    @Test fun timedOutRunReportsIt() = runBlocking {
        server.enqueue(grpcOk(
            StartProcessResponse(event = ProcessEvent(start = ProcessStart(pid = 8))),
            StartProcessResponse(event = ProcessEvent(end = ProcessEnd(timed_out = true, signal_number = 15))),
        ))
        val result = client().run("sleep 100", "/home/cua", timeoutMillis = 1_000)
        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
    }
}
