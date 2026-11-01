package me.rerere.workspace.relay

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Plain sockets with TCP_NODELAY, for the relay's WebSocket (OkHttp leaves Nagle's algorithm on). */
internal object NoDelaySocketFactory : javax.net.SocketFactory() {
    private val plain: javax.net.SocketFactory = javax.net.SocketFactory.getDefault()
    private fun Socket.noDelay(): Socket = apply { tcpNoDelay = true }
    override fun createSocket(): Socket = plain.createSocket().noDelay()
    override fun createSocket(host: String, port: Int): Socket = plain.createSocket(host, port).noDelay()
    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        plain.createSocket(host, port, localHost, localPort).noDelay()
    override fun createSocket(host: InetAddress, port: Int): Socket = plain.createSocket(host, port).noDelay()
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        plain.createSocket(address, port, localAddress, localPort).noDelay()
}

/** One entry candidate's handshake time; [ip] null is the relay host name through DNS. */
data class RelayEdgeTiming(val ip: String?, val millis: Long?)

/** Where a computer's relay is: the relay's base URL and the deployer's access code, if any. */
data class RelayEndpoint(val baseUrl: String, val accessToken: String? = null) {
    init {
        val url = baseUrl.toHttpUrl()
        require(url.isHttps || url.host == "127.0.0.1" || url.host == "localhost" || url.host == "10.0.2.2") {
            "A relay must use HTTPS"
        }
    }

    val host: String get() = baseUrl.toHttpUrl().host
}

/**
 * Talks to relays (PROTOCOL §3, §5, §8). One instance per app; caches `/v1/info` and the entry
 * choice per network, since probing costs a TLS handshake per candidate.
 *
 * Entry probes are bare TLS handshakes with SNI and certificate checks for the relay's own name:
 * they never reach the Worker, so they cost nothing against the relay's request quota.
 */
class RelayClient(
    baseClient: OkHttpClient = OkHttpClient(),
    private val probeTimeoutMillis: Int = 3_000,
    private val infoTtlMillis: Long = 6 * 60 * 60 * 1000L,
    private val edgeTtlMillis: Long = 60 * 60 * 1000L,
) {
    private val client = baseClient.newBuilder()
        // SSH sends small packets back to back; Nagle's algorithm would hold each behind the previous ACK.
        .socketFactory(NoDelaySocketFactory)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val infoCache = ConcurrentHashMap<String, Pair<Long, RelayInfo>>()
    private val edgeCache = ConcurrentHashMap<String, Pair<Long, List<String?>>>()
    private val probes = Executors.newCachedThreadPool(ThreadFactory {
        Thread(it, "miffan-relay-probe").apply { isDaemon = true }
    })

    /** `GET /v1/info`, cached for a few hours unless [refresh]. */
    fun info(endpoint: RelayEndpoint, refresh: Boolean = false): RelayInfo {
        val key = endpoint.baseUrl.trimEnd('/')
        val now = System.currentTimeMillis()
        if (!refresh) infoCache[key]?.takeIf { now - it.first < infoTtlMillis }?.let { return it.second }
        val request = Request.Builder().url("$key/v1/info").build()
        val info = client.newBuilder().readTimeout(10, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val error = runCatching { relayJson.decodeFromString<RelayError>(body) }.getOrNull()
                throw RelayException(error?.reason ?: RelayException.PLATFORM, error?.retryAfter, response.code)
            }
            relayJson.decodeFromString<RelayInfo>(body)
        }
        if (info.v != 1) throw RelayException("unsupported_version", message = "Relay speaks protocol v${info.v}")
        infoCache[key] = now to info
        return info
    }

    /** Forgets entry choices, for example after the phone switched between Wi-Fi and mobile data. */
    fun forgetEdges() = edgeCache.clear()

    /**
     * Entry candidates, fastest first. `null` means the relay host name through normal DNS, which
     * is always included. [networkKey] separates choices per network.
     */
    fun edges(endpoint: RelayEndpoint, networkKey: String): List<String?> {
        val key = "${endpoint.baseUrl}|$networkKey"
        val now = System.currentTimeMillis()
        edgeCache[key]?.takeIf { now - it.first < edgeTtlMillis }?.let { return it.second }
        return measureEdges(endpoint, networkKey).map { it.ip }
    }

    /**
     * Measures every entry candidate now (TCP + TLS handshake), fastest first, and remembers the
     * order for [networkKey]. Unreachable entries come last with no time; DNS is always included.
     */
    fun measureEdges(endpoint: RelayEndpoint, networkKey: String): List<RelayEdgeTiming> {
        val candidates: List<String?> = runCatching { info(endpoint).edges }.getOrDefault(emptyList()).distinct() + null
        val url = endpoint.baseUrl.toHttpUrl()
        val timed = candidates.map { ip ->
            ip to probes.submit(Callable { probe(url.host, url.port, ip) })
        }.map { (ip, future) ->
            RelayEdgeTiming(ip, runCatching { future.get(probeTimeoutMillis + 500L, TimeUnit.MILLISECONDS) }.getOrNull()
                ?.let { it / 1_000_000 })
        }
        val ordered = timed.sortedWith(compareBy(nullsLast()) { it.millis })
        edgeCache["${endpoint.baseUrl}|$networkKey"] = System.currentTimeMillis() to ordered.map { it.ip }
        return ordered
    }

    /**
     * The Cloudflare location code ("LAX", "HKG"…) that answers through entry [ip], from the
     * edge's own `/cdn-cgi/trace`; it never reaches the relay's Worker. Null for other hosts.
     */
    fun edgeLocation(endpoint: RelayEndpoint, ip: String?): String? = runCatching {
        val request = Request.Builder().url(endpoint.baseUrl.trimEnd('/') + "/cdn-cgi/trace").build()
        clientFor(endpoint, ip).newBuilder().readTimeout(5, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            if (!response.isSuccessful) null
            else Regex("^colo=([A-Z]{3})$", RegexOption.MULTILINE).find(response.body.string())?.groupValues?.get(1)
        }
    }.getOrNull()

    private fun probe(host: String, port: Int, ip: String?): Long? {
        val start = System.nanoTime()
        val address = if (ip != null) InetAddress.getByName(ip) else InetAddress.getAllByName(host).first()
        Socket().use { raw ->
            raw.connect(InetSocketAddress(address, port), probeTimeoutMillis)
            raw.soTimeout = probeTimeoutMillis
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
            ssl.use {
                it.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, it.session)) return null
            }
        }
        return System.nanoTime() - start
    }

    private fun clientFor(endpoint: RelayEndpoint, ip: String?): OkHttpClient {
        if (ip == null) return client
        val host = endpoint.host
        val address = InetAddress.getByName(ip)
        return client.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname.equals(host, ignoreCase = true)) listOf(address) else Dns.SYSTEM.lookup(hostname)
        }).build()
    }

    /**
     * Opens a session through the fastest entry, trying the next one only when an entry could not
     * be reached at all. A refusal from the relay or connector is final and thrown as is.
     */
    fun open(
        endpoint: RelayEndpoint,
        hostId: String,
        channel: RelayChannel,
        signer: RelayDeviceSigner,
        networkKey: String,
        timeoutMillis: Long = 20_000,
    ): RelaySocket {
        val entries = edges(endpoint, networkKey).take(MAX_ENTRY_ATTEMPTS)
        var last: RelayException? = null
        for (ip in entries) {
            try {
                return RelaySocket.open(clientFor(endpoint, ip), endpoint.baseUrl, endpoint.accessToken, hostId, channel,
                    signer, timeoutMillis)
            } catch (error: RelayException) {
                if (error.reason != RelayException.UNREACHABLE) throw error
                last = error
                edgeCache.keys.removeIf { it.startsWith("${endpoint.baseUrl}|") }
            }
        }
        throw last ?: RelayException(RelayException.UNREACHABLE)
    }

    /** A JSch socket factory whose sockets are relay sessions; the host and port JSch passes are ignored. */
    fun jschSocketFactory(
        endpoint: RelayEndpoint,
        hostId: String,
        channel: RelayChannel,
        signer: RelayDeviceSigner,
        networkKey: String,
        timeoutMillis: Long = 20_000,
        onOpen: (RelaySocket) -> Unit = {},
    ): com.jcraft.jsch.SocketFactory = object : com.jcraft.jsch.SocketFactory {
        override fun createSocket(host: String, port: Int): Socket =
            open(endpoint, hostId, channel, signer, networkKey, timeoutMillis).also(onOpen)
        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    }

    private companion object {
        const val MAX_ENTRY_ATTEMPTS = 2
    }
}
