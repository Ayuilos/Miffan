package me.ayuilos.miffan.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UpdateApkDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val directory by lazy { temporary.newFolder("updates") }
    private val content = "test APK bytes"
    private val download = UpdateDownload("Miffan.apk", "https://first.example/app.apk", content.length.toLong(), listOf("https://second.example/app.apk"))

    @After fun cleanup() = runBlocking {
        scope.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
    }

    private fun engine(
        packageCheck: (File) -> Boolean = { true },
        respond: (Interceptor.Chain) -> Response = { response(it, 200, content) },
    ): UpdateApkDownloader = UpdateApkDownloader(
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(1, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                requests += chain.request().url.host
                assertTrue(chain.call().timeout().timeoutNanos() == 0L)
                assertEquals(15_000, chain.connectTimeoutMillis())
                assertEquals(30_000, chain.readTimeoutMillis())
                assertTrue(chain.request().header("User-Agent")!!.startsWith("Miffan-Android/"))
                respond(chain)
            }.build(),
        scope, directory, packageCheck,
    )

    private fun response(chain: Interceptor.Chain, code: Int, body: String): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("Test").body(body.toResponseBody()).build()

    private suspend fun finished(engine: UpdateApkDownloader): UpdateDownloadState = withTimeout(5_000) {
        engine.downloadState.first { it is UpdateDownloadState.Ready || it is UpdateDownloadState.Failed }
    }

    @Test fun `404 on first source uses second source and finalizes APK`() = runBlocking {
        val engine = engine { chain -> response(chain, if (chain.request().url.host == "first.example") 404 else 200, content) }
        engine.downloadUpdate(download)
        assertEquals(UpdateDownloadState.Ready(download), finished(engine))
        assertEquals(listOf("first.example", "second.example"), requests)
        assertEquals(content, File(directory, download.name).readText())
        assertFalse(File(directory, "${download.name}.part").exists())
    }

    @Test fun `SHA mismatch falls back and SHA is case insensitive`() = runBlocking {
        val expected = download.copy(sha256 = MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02X".format(it) })
        val engine = engine { chain -> response(chain, 200, if (chain.request().url.host == "first.example") "fake APK bytes" else content) }
        engine.downloadUpdate(expected)
        assertEquals(UpdateDownloadState.Ready(expected), finished(engine))
        assertEquals(listOf("first.example", "second.example"), requests)
    }

    @Test fun `all network sources fail and retry starts with first source`() = runBlocking {
        val engine = engine { throw IOException("offline") }
        repeat(2) {
            engine.downloadUpdate(download)
            assertEquals(UpdateDownloadState.Failed(download, UpdateDownloadError.Network), finished(engine))
        }
        assertEquals(listOf("first.example", "second.example", "first.example", "second.example"), requests)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `cancel interrupts a body read without trying a fallback`() = runBlocking {
        val reading = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val engine = engine { chain ->
            val body = object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = -1L
                override fun source() = object : Source {
                    override fun timeout() = Timeout.NONE
                    override fun close() { cancelled.countDown() }
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        reading.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                        while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
                        assertTrue("HTTP call was not cancelled promptly", chain.call().isCanceled())
                        throw IOException("cancelled")
                    }
                }.buffer()
            }
            response(chain, 200, "").newBuilder().body(body).build()
        }
        engine.downloadUpdate(download)
        assertTrue(reading.await(3, TimeUnit.SECONDS))
        engine.downloadUpdate(download)
        val progress = engine.downloadState.value as UpdateDownloadState.Downloading
        assertEquals("first.example", progress.sourceHost)
        assertEquals(download.sizeBytes, progress.totalBytes)
        engine.cancelUpdateDownload()
        assertTrue(cancelled.await(3, TimeUnit.SECONDS))
        assertEquals(UpdateDownloadState.Idle, engine.downloadState.value)
        assertEquals(listOf("first.example"), requests)
    }

    @Test fun `existing verified file is Ready with no requests and other cache files are deleted`() = runBlocking {
        File(directory, download.name).writeText(content)
        File(directory, "old.apk").writeText("old")
        File(directory, "${download.name}.part").writeText("partial")
        val engine = engine()
        val verified = download.copy(sha256 = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray()).joinToString("") { "%02x".format(it) })
        engine.downloadUpdate(verified)
        finished(engine)
        engine.downloadUpdate(verified)
        assertEquals(UpdateDownloadState.Ready(verified), finished(engine))
        assertTrue(requests.isEmpty())
        assertEquals(listOf(download.name), directory.listFiles()!!.map { it.name })
    }

    @Test fun `bad cached APK is removed before downloading`() = runBlocking {
        File(directory, download.name).writeText("bad")
        val engine = engine()
        engine.downloadUpdate(download)
        assertEquals(UpdateDownloadState.Ready(download), finished(engine))
        assertEquals(listOf("first.example"), requests)
    }

    @Test fun `size mismatch and package mismatch are verification failures and delete files`() = runBlocking {
        for (badSize in listOf(true, false)) {
            val candidate = download.copy(sizeBytes = if (badSize) 1 else content.length.toLong())
            val engine = engine(packageCheck = { badSize })
            engine.downloadUpdate(candidate)
            assertEquals(UpdateDownloadState.Failed(candidate, UpdateDownloadError.Verification), finished(engine))
            assertTrue(directory.listFiles()!!.isEmpty())
        }
        assertEquals(listOf("first.example", "second.example", "first.example", "second.example"), requests)
    }

    @Test fun `failure reports the last source error`() = runBlocking {
        val engine = engine { chain -> response(chain, if (chain.request().url.host == "first.example") 503 else 200, "wrong") }
        engine.downloadUpdate(download)
        assertEquals(UpdateDownloadState.Failed(download, UpdateDownloadError.Verification), finished(engine))
    }

    @Test fun `unwritable directory reports Storage without making requests`() = runBlocking {
        val blocked = temporary.newFile("blocked")
        val engine = UpdateApkDownloader(OkHttpClient(), scope, blocked, { true })
        engine.downloadUpdate(download)
        assertEquals(UpdateDownloadState.Failed(download, UpdateDownloadError.Storage), finished(engine))
    }

    @Test fun `file write failures report Storage rather than Network`() = runBlocking {
        val engine = engine { chain ->
            assertTrue(File(directory, "${download.name}.part").mkdir())
            response(chain, 200, content)
        }
        engine.downloadUpdate(download)
        assertEquals(UpdateDownloadState.Failed(download, UpdateDownloadError.Storage), finished(engine))
        assertEquals(listOf("first.example", "second.example"), requests)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `same download is ignored while verifying and a different download replaces it`() = runBlocking {
        val verifying = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacement = download.copy(name = "new.apk", url = "https://new.example/app.apk")
        val engine = engine(packageCheck = { file ->
            if (file.name.startsWith(download.name)) {
                verifying.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
            }
            true
        })
        engine.downloadUpdate(download)
        assertTrue(verifying.await(3, TimeUnit.SECONDS))
        engine.downloadUpdate(download)
        assertEquals(UpdateDownloadState.Verifying(download), engine.downloadState.value)
        engine.downloadUpdate(replacement)
        release.countDown()
        assertEquals(UpdateDownloadState.Ready(replacement), finished(engine))
        assertEquals(listOf("first.example", "new.example"), requests)
        assertEquals(listOf(replacement.name), directory.listFiles()!!.map { it.name })
    }

    @Test fun `app scope cancellation during verification returns to Idle without fallback`() = runBlocking {
        val verifying = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = engine(packageCheck = {
            verifying.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
            true
        })
        engine.downloadUpdate(download)
        assertTrue(verifying.await(3, TimeUnit.SECONDS))
        scope.cancel()
        release.countDown()
        withTimeout(5_000) { engine.downloadState.first { it == UpdateDownloadState.Idle } }
        assertEquals(listOf("first.example"), requests)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `rapid replacements using the same file cannot race cleanup or emit stale completion`() = runBlocking {
        val verifying = CountDownLatch(1)
        val release = CountDownLatch(1)
        var checks = 0
        val completions = Collections.synchronizedList(mutableListOf<UpdateDownloadState>())
        val client = UpdateSourceTest.client { url -> requests += url; 200 to content }
        val engine = UpdateApkDownloader(client, scope, directory, {
            if (checks++ == 0) {
                verifying.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
            }
            true
        }, onTerminal = { completions += it })
        engine.downloadUpdate(download)
        assertTrue(verifying.await(3, TimeUnit.SECONDS))
        var latest = download
        repeat(10) {
            latest = download.copy(url = "https://replacement.example/$it.apk")
            engine.downloadUpdate(latest)
        }
        release.countDown()
        assertEquals(UpdateDownloadState.Ready(latest), finished(engine))
        // Terminal side effects finish under the engine lock before a subsequent no-op returns.
        engine.downloadUpdate(latest)
        assertEquals(listOf(UpdateDownloadState.Ready(latest)), completions)
        assertEquals(listOf(download.url, latest.url), requests)
        assertEquals(content, File(directory, download.name).readText())
    }

    @Test fun `unsafe file names never escape the updates directory`() = runBlocking {
        val engine = engine()
        val candidate = download.copy(name = "../escape.apk")
        engine.downloadUpdate(candidate)
        assertEquals(UpdateDownloadState.Failed(candidate, UpdateDownloadError.Storage), finished(engine))
        assertTrue(requests.isEmpty())
        assertFalse(File(directory.parentFile, "escape.apk").exists())
    }

    /** A body that sends [sent] and then drops the connection. */
    private fun dropping(chain: Interceptor.Chain, sent: String): Response {
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = content.length.toLong()
            override fun source() = object : Source {
                var done = false
                override fun timeout() = Timeout.NONE
                override fun close() = Unit
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (done) throw IOException("connection reset")
                    done = true
                    sink.writeUtf8(sent)
                    return sent.length.toLong()
                }
            }.buffer()
        }
        return response(chain, 200, "").newBuilder().body(body).build()
    }

    @Test fun `a dropped download keeps its bytes and continues from them`() = runBlocking {
        val half = content.length / 2
        var online = false
        val ranges = Collections.synchronizedList(mutableListOf<String?>())
        val engine = engine { chain ->
            ranges += chain.request().header("Range")
            if (!online) dropping(chain, content.substring(0, half))
            else response(chain, 206, content.substring(half)).newBuilder()
                .header("Content-Range", "bytes $half-${content.length - 1}/${content.length}").build()
        }
        engine.downloadUpdate(download)
        assertEquals(UpdateDownloadState.Failed(download, UpdateDownloadError.Network), finished(engine))
        assertEquals(content.substring(0, half), File(directory, "${download.name}.part").readText())

        online = true
        engine.downloadUpdate(download)
        assertEquals(UpdateDownloadState.Ready(download), finished(engine))
        assertEquals(content, File(directory, download.name).readText())
        assertEquals("bytes=$half-", ranges.last())
    }

    @Test fun `a server that ignores the range sends the whole file again`() = runBlocking {
        val half = content.length / 2
        var online = false
        val engine = engine { chain -> if (!online) dropping(chain, content.substring(0, half)) else response(chain, 200, content) }
        engine.downloadUpdate(download)
        assertEquals(UpdateDownloadState.Failed(download, UpdateDownloadError.Network), finished(engine))
        online = true
        engine.downloadUpdate(download)
        assertEquals(UpdateDownloadState.Ready(download), finished(engine))
        assertEquals(content, File(directory, download.name).readText())
    }

    @Test fun `a quiet download reports only success`() = runBlocking {
        val completions = Collections.synchronizedList(mutableListOf<UpdateDownloadState>())
        var started = 0
        var online = false
        val client = UpdateSourceTest.client { url -> requests += url; if (online) 200 to content else 503 to "" }
        val engine = UpdateApkDownloader(client, scope, directory, { true }, onStart = { started++ }, onTerminal = { completions += it })
        engine.downloadUpdate(download, quiet = true)
        assertEquals(UpdateDownloadState.Failed(download, UpdateDownloadError.Network), finished(engine))
        assertTrue(completions.isEmpty())
        online = true
        engine.downloadUpdate(download, quiet = true)
        assertEquals(UpdateDownloadState.Ready(download), finished(engine))
        assertEquals(listOf(UpdateDownloadState.Ready(download)), completions)
        assertEquals(0, started)
    }

    @Test fun `an APK from an earlier run is restored as Ready silently`() = runBlocking {
        File(directory, download.name).writeText(content)
        val completions = Collections.synchronizedList(mutableListOf<UpdateDownloadState>())
        val engine = UpdateApkDownloader(OkHttpClient(), scope, directory, { true }, onTerminal = { completions += it })
        engine.restoreReady(download)
        assertEquals(UpdateDownloadState.Ready(download), finished(engine))
        assertTrue(completions.isEmpty())
        assertTrue(requests.isEmpty())
    }
}
