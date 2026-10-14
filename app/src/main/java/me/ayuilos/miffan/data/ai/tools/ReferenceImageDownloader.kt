package me.ayuilos.miffan.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.common.http.await
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy

internal const val REFERENCE_IMAGE_URL_ERROR = "Reference image URL must be a public image"
internal const val MAX_REFERENCE_IMAGE_BYTES = 20 * 1024 * 1024
private const val MAX_REFERENCE_IMAGE_REDIRECTS = 5
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

/** Only for URLs explicitly supplied by an agent, not URLs returned by an image provider. */
internal class ReferenceImageDownloader(private val httpClient: OkHttpClient) {
    suspend fun download(source: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            var url = source.toHttpUrlOrNull() ?: throw rejectReferenceImage("invalid URL")
            for (hop in 0..MAX_REFERENCE_IMAGE_REDIRECTS) {
                if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw rejectReferenceImage("URL credentials are not allowed")
                val pinnedDns = vettedReferenceImageDns(url.host, httpClient.dns)
                // A shared pooled connection or a proxy's own DNS could bypass the vetted address list.
                val pool = ConnectionPool()
                val client = httpClient.newBuilder()
                    .dns(pinnedDns)
                    .proxy(Proxy.NO_PROXY)
                    .connectionPool(pool)
                    .cache(null)
                    .cookieJar(CookieJar.NO_COOKIES)
                    .authenticator(Authenticator.NONE)
                    .proxyAuthenticator(Authenticator.NONE)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .build()
                try {
                    val call = client.newCall(Request.Builder().url(url).get().build())
                    call.await().use { response ->
                        if (response.code in REDIRECT_CODES) {
                            // No redirect body is needed; prevent close() from draining an untrusted body.
                            call.cancel()
                            if (hop == MAX_REFERENCE_IMAGE_REDIRECTS) throw rejectReferenceImage("too many redirects")
                            val location = response.header("Location") ?: throw rejectReferenceImage("redirect has no location")
                            url = url.resolve(location) ?: throw rejectReferenceImage("invalid redirect")
                        } else {
                            if (!response.isSuccessful) {
                                call.cancel()
                                throw rejectReferenceImage("HTTP ${response.code}")
                            }
                            val bytes = try {
                                readBoundedReferenceImage(response.body)
                            } catch (e: Exception) {
                                call.cancel()
                                throw e
                            }
                            if (sniffImageMimeType(bytes) == null) throw rejectReferenceImage("not a PNG, JPEG, WebP or GIF image")
                            return@withContext bytes
                        }
                    }
                } finally {
                    pool.evictAll()
                }
            }
            throw rejectReferenceImage("too many redirects")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is IOException && e.message?.startsWith(REFERENCE_IMAGE_URL_ERROR) == true) throw e
            throw IOException(REFERENCE_IMAGE_URL_ERROR, e)
        }
    }
}

/** Resolve once, reject the entire answer if any address is unsafe, and pin all subsequent lookups. */
internal fun vettedReferenceImageDns(host: String, resolver: Dns): Dns {
    // OkHttp bypasses custom DNS for numeric IP hosts, so validate the literal itself rather than
    // allowing a custom resolver to substitute a different address for it.
    val numeric = host.contains(':') || host.all { it.isDigit() || it == '.' }
    val addresses = (if (numeric) listOf(InetAddress.getByName(host)) else resolver.lookup(host)).toList()
    if (addresses.isEmpty() || addresses.any { !isPublicReferenceImageAddress(it) }) {
        throw rejectReferenceImage("host resolves to a non-public address")
    }
    return Dns { requestedHost ->
        if (requestedHost != host) throw rejectReferenceImage("unexpected DNS lookup")
        addresses.toList()
    }
}

internal fun isPublicReferenceImageAddress(address: InetAddress): Boolean {
    val bytes = address.address
    if (bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } &&
        bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
    ) return isPublicReferenceImageAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return false
    return when (bytes.size) {
        4 -> {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            first != 0 && first != 10 && first != 127 && first < 224 &&
                !(first == 172 && second in 16..31) && !(first == 192 && second == 168) &&
                !(first == 169 && second == 254) && !(first == 100 && second in 64..127)
        }
        16 -> {
            val first = bytes[0].toInt() and 0xff
            // fc00::/7; Java's site-local check does not cover IPv6 unique-local addresses.
            (first and 0xfe) != 0xfc && !(bytes.take(12).all { it == 0.toByte() })
        }
        else -> false
    }
}

private fun readBoundedReferenceImage(body: ResponseBody): ByteArray {
    if (body.contentLength() > MAX_REFERENCE_IMAGE_BYTES) throw rejectReferenceImage("image exceeds 20 MB")
    val output = ByteArrayOutputStream()
    // The response owns the stream; on failure the caller cancels the socket before closing it.
    val input = body.byteStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = input.read(buffer, 0, minOf(buffer.size, MAX_REFERENCE_IMAGE_BYTES - output.size() + 1))
        if (read == -1) break
        if (output.size() + read > MAX_REFERENCE_IMAGE_BYTES) throw rejectReferenceImage("image exceeds 20 MB")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun rejectReferenceImage(reason: String) = IOException("$REFERENCE_IMAGE_URL_ERROR ($reason)")

internal fun sniffImageMimeType(bytes: ByteArray): String? = when {
    bytes.size >= PNG_SIGNATURE.size && bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
    bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
    else -> null
}
