package me.ayuilos.miffan.data.ai.tools

import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

class ReferenceImageDownloaderTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
    private fun address(ip: String) = InetAddress.getByName(ip)
    private fun publicDns() = Dns { listOf(address("8.8.8.8")) }
    private fun response(request: Request, body: ResponseBody = png.toResponseBody("image/png".toMediaType()),
        code: Int = 200, location: String? = null) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("Response").body(body)
        .apply { location?.let { header("Location", it) } }.build()

    @Test fun privateLoopbackCgnatAndOtherNonPublicAddressesAreRejected() {
        val rejected = listOf("0.0.0.0", "0.1.2.3", "127.0.0.1", "127.25.1.1", "10.1.2.3",
            "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.1.1", "169.254.169.254",
            "100.64.0.1", "100.127.255.255", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fdff::1", "fe80::1", "febf::1", "ff02::1")
        rejected.forEach { ip -> assertFalse(ip, isPublicReferenceImageAddress(address(ip))) }
        listOf("8.8.8.8", "1.1.1.1", "100.63.255.255", "100.128.0.1", "172.15.255.255",
            "172.32.0.1", "2606:4700:4700::1111").forEach { ip ->
            assertTrue(ip, isPublicReferenceImageAddress(address(ip)))
        }
    }

    @Test fun mappedIpv4IsClassifiedByTheEmbeddedAddress() {
        fun mapped(ip: String): Inet6Address {
            val bytes = ByteArray(16)
            bytes[10] = 0xff.toByte()
            bytes[11] = 0xff.toByte()
            address(ip).address.copyInto(bytes, 12)
            return Inet6Address.getByAddress(null, bytes, -1)
        }
        listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1", "100.64.0.1",
            "169.254.169.254", "0.0.0.0", "224.0.0.1").forEach { ip ->
            assertFalse(ip, isPublicReferenceImageAddress(mapped(ip)))
        }
        assertTrue(isPublicReferenceImageAddress(mapped("8.8.8.8")))
    }

    @Test fun rejectsEntireDnsAnswerWhenAnyAddressIsPrivateAndDoesNotFetch() = runTest {
        var fetched = false
        val client = OkHttpClient.Builder().dns(Dns { listOf(address("8.8.8.8"), address("192.168.1.1")) })
            .addInterceptor { fetched = true; response(it.request()) }.build()
        try {
            ReferenceImageDownloader(client).download("https://public.test/a.png")
            fail("Expected rejection")
        } catch (e: IOException) {
            assertTrue(e.message!!.startsWith(REFERENCE_IMAGE_URL_ERROR))
        }
        assertFalse(fetched)
        // Numeric hosts must not let a fake/custom DNS override the literal private destination.
        val publicResolver = publicDns()
        assertThrows(IOException::class.java) { vettedReferenceImageDns("127.0.0.1", publicResolver) }
        assertThrows(IOException::class.java) { vettedReferenceImageDns("::ffff:127.0.0.1", publicResolver) }
    }

    @Test fun publicDnsResultsArePinnedWithoutAnotherResolverLookup() {
        var calls = 0
        val resolver = Dns { calls++; listOf(address(if (calls == 1) "8.8.8.8" else "127.0.0.1")) }
        val pinned = vettedReferenceImageDns("public.test", resolver)
        repeat(3) { assertEquals(listOf(address("8.8.8.8")), pinned.lookup("public.test")) }
        assertEquals(1, calls)
        assertThrows(IOException::class.java) { pinned.lookup("other.test") }
    }

    @Test fun publicImageIsAcceptedWithFakeDns() = runTest {
        var calls = 0
        val client = OkHttpClient.Builder().dns(Dns { calls++; listOf(address("8.8.8.8")) })
            .addInterceptor { response(it.request()) }.build()
        assertArrayEquals(png, ReferenceImageDownloader(client).download("https://public.test/a.png"))
        assertEquals(1, calls)
    }

    @Test fun nonImageBodyIsRejectedEvenWhenContentTypeClaimsImage() = runTest {
        val client = OkHttpClient.Builder().dns(publicDns()).addInterceptor {
            response(it.request(), "<html>private content</html>".toResponseBody("image/png".toMediaType()))
        }.build()
        try {
            ReferenceImageDownloader(client).download("https://public.test/a.png")
            fail("Expected image validation")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("not a PNG"))
        }
    }

    @Test fun redirectToPrivateAddressIsRejectedBeforeTheSecondRequest() = runTest {
        val lookups = mutableListOf<String>()
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().dns(Dns { host ->
            lookups.add(host)
            listOf(address(if (host == "private.test") "100.64.1.1" else "8.8.8.8"))
        }).addInterceptor {
            requests.incrementAndGet()
            response(it.request(), code = 302, location = "http://private.test/image.png")
        }.build()
        try {
            ReferenceImageDownloader(client).download("https://public.test/a.png")
            fail("Expected private redirect rejection")
        } catch (e: IOException) {
            assertTrue(e.message!!.startsWith(REFERENCE_IMAGE_URL_ERROR))
        }
        assertEquals(listOf("public.test", "private.test"), lookups)
        assertEquals(1, requests.get())
    }

    @Test fun followsAtMostFiveRedirectsAndChecksEveryHop() = runTest {
        for (redirects in listOf(5, 6)) {
            val requests = AtomicInteger()
            val lookups = AtomicInteger()
            val client = OkHttpClient.Builder().dns(Dns { lookups.incrementAndGet(); listOf(address("8.8.8.8")) })
                .addInterceptor {
                    val index = requests.incrementAndGet()
                    if (index <= redirects) response(it.request(), code = 307, location = "/image-$index.png")
                    else response(it.request())
                }.build()
            try {
                val bytes = ReferenceImageDownloader(client).download("https://public.test/a.png")
                assertEquals(5, redirects)
                assertArrayEquals(png, bytes)
            } catch (e: IOException) {
                assertEquals(6, redirects)
                assertTrue(e.message!!.contains("too many redirects"))
            }
            assertEquals(6, requests.get())
            assertEquals(6, lookups.get())
        }
    }

    @Test fun capsBodiesWithDeclaredAndUnknownLengthsAtTwentyMegabytes() = runTest {
        for (declared in listOf(true, false)) {
            val oversized = ByteArray(MAX_REFERENCE_IMAGE_BYTES + 1).apply { png.copyInto(this) }
            val body = if (declared) oversized.toResponseBody("image/png".toMediaType()) else object : ResponseBody() {
                private val buffer = Buffer().write(oversized)
                override fun contentType() = "image/png".toMediaType()
                override fun contentLength() = -1L
                override fun source(): BufferedSource = buffer
            }
            val client = OkHttpClient.Builder().dns(publicDns()).addInterceptor { response(it.request(), body) }.build()
            try {
                ReferenceImageDownloader(client).download("https://public.test/a.png")
                fail("Expected body size cap")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("20 MB"))
            }
        }
    }

    @Test fun sniffsSupportedMagicAndRejectsEmptyOrTruncatedHeaders() {
        assertEquals("image/png", sniffImageMimeType(png))
        assertEquals("image/jpeg", sniffImageMimeType(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        assertEquals("image/gif", sniffImageMimeType("GIF89a".toByteArray()))
        assertEquals("image/webp", sniffImageMimeType("RIFF0000WEBP".toByteArray()))
        assertNull(sniffImageMimeType(ByteArray(0)))
        assertNull(sniffImageMimeType("PNG".toByteArray()))
        assertNull(sniffImageMimeType("GIF8".toByteArray()))
    }

    @Test fun actualHttpConnectionUsesCheckedAddressAndBypassesProxyDns() = runTest {
        var dnsCalls = 0
        var connected: InetSocketAddress? = null
        val input = "HTTP/1.1 200 OK\r\nContent-Length: ${png.size}\r\nConnection: close\r\n\r\n".toByteArray() + png
        val socketFactory = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) { connected = endpoint as InetSocketAddress }
                override fun getInputStream() = ByteArrayInputStream(input)
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun isConnected() = connected != null
            }
            override fun createSocket(host: String, port: Int): Socket = error("Unexpected socket overload")
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected socket overload")
            override fun createSocket(host: InetAddress, port: Int): Socket = error("Unexpected socket overload")
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("Unexpected socket overload")
        }
        val client = OkHttpClient.Builder().socketFactory(socketFactory)
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1080)))
            .dns(Dns { dnsCalls++; listOf(address(if (dnsCalls == 1) "8.8.8.8" else "127.0.0.1")) }).build()
        assertArrayEquals(png, ReferenceImageDownloader(client).download("http://public.test/a.png"))
        assertEquals(1, dnsCalls)
        assertEquals(address("8.8.8.8"), connected!!.address)
        assertFalse(connected!!.isUnresolved)
        assertEquals(80, connected!!.port)
    }
}
