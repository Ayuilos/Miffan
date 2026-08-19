package me.ayuilos.miffan.data.repository

import java.io.FilterInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class WorkspaceNetworkBroker(
    systemDns: Dns = Dns.SYSTEM,
) {
    private val client = OkHttpClient.Builder()
        .dns(PublicAddressDns(systemDns))
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    fun <T> fetch(
        rawUrl: String,
        consume: (InputStream) -> T,
    ): T {
        val original = requireAllowedUrl(rawUrl)
        var current = original
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            client.newCall(Request.Builder().url(current).get().build()).execute().use { response ->
                if (response.code in REDIRECT_CODES) {
                    require(redirectCount < MAX_REDIRECTS) { "Too many workspace fetch redirects" }
                    val location = response.header("Location")
                        ?: error("Workspace fetch redirect is missing Location")
                    current = requireAllowedUrl(current.resolve(location)?.toString().orEmpty())
                    require(current.host == original.host) {
                        "Workspace fetch redirect changed host"
                    }
                    return@repeat
                }
                require(response.isSuccessful) { "Workspace fetch failed: HTTP ${response.code}" }
                val body = response.body
                val length = body.contentLength()
                require(length < 0 || length <= MAX_FETCH_BYTES) {
                    "Workspace fetch exceeds $MAX_FETCH_BYTES bytes"
                }
                return body.byteStream().use { consume(BoundedInputStream(it, MAX_FETCH_BYTES)) }
            }
        }
        error("Too many workspace fetch redirects")
    }

    private fun requireAllowedUrl(rawUrl: String): HttpUrl {
        val url = rawUrl.toHttpUrlOrNull() ?: error("Workspace fetch URL is invalid")
        require(url.scheme == "https") { "Workspace fetch requires HTTPS" }
        require(url.port == 443) { "Workspace fetch requires the standard HTTPS port" }
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "Workspace fetch URL must not contain credentials"
        }
        require(!url.host.isIpLiteral()) { "Workspace fetch requires a public DNS hostname" }
        return url
    }

    private class PublicAddressDns(private val delegate: Dns) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            require(!hostname.isIpLiteral()) { "Workspace fetch requires a public DNS hostname" }
            return delegate.lookup(hostname).also { addresses ->
                require(addresses.isNotEmpty() && addresses.all { it.isPublicRoutable() }) {
                    "Workspace fetch blocked a non-public network destination"
                }
            }
        }
    }

    private class BoundedInputStream(
        input: InputStream,
        private val maximum: Long,
    ) : FilterInputStream(input) {
        private var consumed = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) account(count.toLong())
            return count
        }

        private fun account(bytes: Long) {
            consumed = Math.addExact(consumed, bytes)
            require(consumed <= maximum) { "Workspace fetch exceeds $maximum bytes" }
        }
    }

    companion object {
        const val MAX_FETCH_BYTES = 8L * 1024 * 1024
        private const val MAX_REDIRECTS = 3
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        internal fun InetAddress.isPublicRoutable(): Boolean {
            if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress ||
                isSiteLocalAddress || isMulticastAddress
            ) return false
            val bytes = address
            return when (this) {
                is Inet4Address -> {
                    val first = bytes[0].toInt() and 0xff
                    val second = bytes[1].toInt() and 0xff
                    val third = bytes[2].toInt() and 0xff
                    !(first == 0 || first >= 224 ||
                        (first == 100 && second in 64..127) ||
                        (first == 192 && second == 0) ||
                        (first == 192 && second == 0 && third == 2) ||
                        (first == 192 && second == 88 && third == 99) ||
                        (first == 198 && second in 18..19) ||
                        (first == 198 && second == 51 && third == 100) ||
                        (first == 203 && second == 0 && third == 113))
                }

                is Inet6Address -> {
                    val first = bytes[0].toInt() and 0xff
                    val second = bytes[1].toInt() and 0xff
                    val third = bytes[2].toInt() and 0xff
                    val fourth = bytes[3].toInt() and 0xff
                    first and 0xfe != 0xfc &&
                        !(first == 0x20 && second == 0x01 && third == 0x00 && fourth == 0x00) &&
                        !(first == 0x20 && second == 0x01 && third == 0x00 && fourth == 0x02) &&
                        !(first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8) &&
                        !(first == 0x20 && second == 0x02) &&
                        !(first == 0x01 && (1..7).all { bytes[it].toInt() == 0 })
                }

                else -> false
            }
        }

        private fun String.isIpLiteral(): Boolean =
            contains(':') || matches(Regex("[0-9.]+"))
    }
}
