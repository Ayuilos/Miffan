package me.rerere.workspace.relay

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
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

/** `/v1/info` as fetched at [fetchedAtMillis] (wall clock), kept by a [RelayInfoStore]. */
data class StoredRelayInfo(val fetchedAtMillis: Long, val json: String)

/**
 * Keeps each relay's `/v1/info` across app restarts, so the first relay connection after a cold
 * start needs no request to the relay's Worker (and no trip over the possibly slow default route).
 * Keys are relay base URLs. Implementations must not throw.
 */
interface RelayInfoStore {
    fun read(baseUrl: String): StoredRelayInfo?
    fun write(baseUrl: String, info: StoredRelayInfo)
}

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
 * Talks to relays (PROTOCOL §3, §5, §8). One instance per app; caches `/v1/info` (in memory and in
 * [infoStore]) and the entry choice per network, since probing costs a TLS handshake per candidate.
 *
 * Entry probes are bare TLS handshakes with SNI and certificate checks for the relay's own name:
 * they never reach the Worker, so they cost nothing against the relay's request quota.
 */
class RelayClient(
    baseClient: OkHttpClient = OkHttpClient(),
    private val probeTimeoutMillis: Int = 3_000,
    private val infoTtlMillis: Long = 6 * 60 * 60 * 1000L,
    private val edgeTtlMillis: Long = 60 * 60 * 1000L,
    private val infoStore: RelayInfoStore? = null,
    /** After the fastest entry answers, how long slower ones may still finish before a connection uses the order. */
    private val probeSettleMillis: Long = 150,
    /** Connect timeout through one pinned entry IP: a probe just answered there within milliseconds. */
    private val pinnedConnectTimeoutMillis: Long = 3_000,
    /** Replaces the TLS handshake probe in tests: handshake nanos, or null when the entry failed. */
    private val edgeProbe: ((host: String, port: Int, ip: String?) -> Long?)? = null,
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

    private val refreshing = ConcurrentHashMap.newKeySet<String>()

    /** `GET /v1/info`, cached for a few hours (also across restarts) unless [refresh]. */
    fun info(endpoint: RelayEndpoint, refresh: Boolean = false): RelayInfo {
        val key = endpoint.baseUrl.trimEnd('/')
        if (!refresh) cachedInfo(key)?.takeIf { System.currentTimeMillis() - it.first < infoTtlMillis }?.let { return it.second }
        return fetchInfo(key)
    }

    /** The last known info, even past its lifetime, from memory or [infoStore]. */
    private fun cachedInfo(key: String): Pair<Long, RelayInfo>? {
        infoCache[key]?.let { return it }
        val stored = runCatching { infoStore?.read(key) }.getOrNull() ?: return null
        val info = runCatching { relayJson.decodeFromString<RelayInfo>(stored.json) }.getOrNull()?.takeIf { it.v == 1 }
            ?: return null
        return (stored.fetchedAtMillis to info).also { infoCache.putIfAbsent(key, it) }
    }

    /**
     * Info for choosing entries: whatever is known now, refreshed in the background once past its
     * lifetime, so a connection never waits for the Worker. Only a relay never seen before is
     * fetched on the spot.
     */
    private fun infoForEdges(endpoint: RelayEndpoint): RelayInfo? {
        val key = endpoint.baseUrl.trimEnd('/')
        val cached = cachedInfo(key) ?: return runCatching { fetchInfo(key) }.getOrNull()
        if (System.currentTimeMillis() - cached.first >= infoTtlMillis && refreshing.add(key)) {
            probes.execute {
                try { runCatching { fetchInfo(key) } } finally { refreshing.remove(key) }
            }
        }
        return cached.second
    }

    private fun fetchInfo(key: String): RelayInfo {
        val now = System.currentTimeMillis()
        val request = Request.Builder().url("$key/v1/info").build()
        val info = client.newBuilder().readTimeout(10, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val error = runCatching { relayJson.decodeFromString<RelayError>(body) }.getOrNull()
                throw RelayException(error?.reason ?: RelayException.PLATFORM, error?.retryAfter, response.code)
            }
            relayJson.decodeFromString<RelayInfo>(body) to body
        }
        if (info.first.v != 1) throw RelayException("unsupported_version", message = "Relay speaks protocol v${info.first.v}")
        infoCache[key] = now to info.first
        runCatching { infoStore?.write(key, StoredRelayInfo(now, info.second)) }
        return info.first
    }

    /** Forgets entry choices, for example after the phone switched between Wi-Fi and mobile data. */
    fun forgetEdges() = edgeCache.clear()

    /** Sessions opened by this client and not closed yet. */
    private val live: MutableSet<RelaySocket> = ConcurrentHashMap.newKeySet()

    /**
     * Checks every open session at once ([RelaySocket.checkAlive]) and closes the ones that no
     * longer answer, so their users reconnect now instead of after a minute of missing pongs. Runs
     * in the background; the future completes with how many were closed.
     */
    fun checkLiveSessions(timeoutMillis: Long = 3_000): java.util.concurrent.CompletableFuture<Int> {
        val checks = live.toList().map { socket ->
            java.util.concurrent.CompletableFuture.supplyAsync({ socket.checkAlive(timeoutMillis) }, probes)
        }
        return java.util.concurrent.CompletableFuture.allOf(*checks.toTypedArray())
            .thenApply { checks.count { !it.join() } }
    }

    /**
     * Entry candidates, fastest first. `null` means the relay host name through normal DNS, which
     * is always included. [networkKey] separates choices per network.
     */
    fun edges(endpoint: RelayEndpoint, networkKey: String): List<String?> {
        val key = "${endpoint.baseUrl}|$networkKey"
        val now = System.currentTimeMillis()
        edgeCache[key]?.takeIf { now - it.first < edgeTtlMillis }?.let { return it.second }
        val candidates = candidates(endpoint)
        // Nothing to choose between: a probe would only add a handshake before the real one.
        if (candidates.size == 1) return candidates.also { edgeCache[key] = now to it }
        return chooseEdges(endpoint, key, candidates)
    }

    private fun candidates(endpoint: RelayEndpoint): List<String?> =
        infoForEdges(endpoint)?.edges.orEmpty().distinct() + null

    /**
     * Probes every candidate in parallel but answers once the fastest has answered and the others
     * had [probeSettleMillis] more: a candidate that never answers no longer holds up the first
     * connection for the whole probe timeout. The rest finish in the background and then replace
     * the remembered order, unless it was forgotten meanwhile.
     */
    private fun chooseEdges(endpoint: RelayEndpoint, key: String, candidates: List<String?>): List<String?> {
        val url = endpoint.baseUrl.toHttpUrl()
        val done = ExecutorCompletionService<Pair<Int, Long?>>(probes)
        val futures = candidates.mapIndexed { index, ip ->
            done.submit { index to runCatching { runProbe(url.host, url.port, ip) }.getOrNull() }
        }
        val timings = arrayOfNulls<Long>(candidates.size)
        val finished = BooleanArray(candidates.size)
        var deadline = System.nanoTime() + (probeTimeoutMillis + 500L) * 1_000_000L
        var settling = false
        var remaining = candidates.size
        while (remaining > 0) {
            val wait = deadline - System.nanoTime()
            if (wait <= 0) break
            val (index, nanos) = done.poll(wait, TimeUnit.NANOSECONDS)?.get() ?: break
            finished[index] = true
            timings[index] = nanos
            remaining--
            if (nanos != null && !settling) {
                settling = true
                deadline = minOf(deadline, System.nanoTime() + probeSettleMillis * 1_000_000L)
            }
        }
        val order = order(candidates, timings, finished)
        val chosen = System.currentTimeMillis() to order
        edgeCache[key] = chosen
        if (remaining > 0) probes.execute {
            futures.forEach { future ->
                runCatching { future.get(probeTimeoutMillis + 500L, TimeUnit.MILLISECONDS) }.getOrNull()?.let { (index, nanos) ->
                    finished[index] = true
                    timings[index] = nanos
                }
            }
            edgeCache.replace(key, chosen, System.currentTimeMillis() to order(candidates, timings, finished))
        }
        return order
    }

    /** Answered entries fastest first, then ones still unanswered, then failed ones; stable otherwise. */
    private fun order(candidates: List<String?>, timings: Array<Long?>, finished: BooleanArray): List<String?> =
        candidates.indices.sortedWith(compareBy<Int>({ if (timings[it] != null) 0 else if (!finished[it]) 1 else 2 },
            { timings[it] ?: 0L })).map { candidates[it] }

    /**
     * Measures every entry candidate now (TCP + TLS handshake), fastest first, and remembers the
     * order for [networkKey]. Unreachable entries come last with no time; DNS is always included.
     * Waits for every probe: this is for diagnostics, connections use [edges].
     */
    fun measureEdges(endpoint: RelayEndpoint, networkKey: String): List<RelayEdgeTiming> {
        val candidates = candidates(endpoint)
        val url = endpoint.baseUrl.toHttpUrl()
        val timed = candidates.map { ip ->
            ip to probes.submit(Callable { runProbe(url.host, url.port, ip) })
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

    private fun runProbe(host: String, port: Int, ip: String?): Long? =
        if (edgeProbe != null) edgeProbe(host, port, ip) else probe(host, port, ip)

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
        return client.newBuilder().connectTimeout(pinnedConnectTimeoutMillis, TimeUnit.MILLISECONDS).dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname.equals(host, ignoreCase = true)) listOf(address) else Dns.SYSTEM.lookup(hostname)
        }).build()
    }

    /**
     * Opens a session through the fastest entry, trying the next one only when an entry could not
     * be reached at all, and the relay's name through normal DNS last (PROTOCOL §8.2). A refusal
     * from the relay or connector is final and thrown as is.
     */
    fun open(
        endpoint: RelayEndpoint,
        hostId: String,
        channel: RelayChannel,
        signer: RelayDeviceSigner,
        networkKey: String,
        timeoutMillis: Long = 20_000,
    ): RelaySocket {
        val entries = entryAttempts(edges(endpoint, networkKey))
        var last: RelayException? = null
        for (ip in entries) {
            try {
                return RelaySocket.open(clientFor(endpoint, ip), endpoint.baseUrl, endpoint.accessToken, hostId, channel,
                    signer, timeoutMillis).also { socket ->
                    live += socket
                    socket.addCloseListener { live -= it }
                }
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

    internal companion object {
        const val MAX_ENTRY_ATTEMPTS = 2

        /** The fastest [MAX_ENTRY_ATTEMPTS] entries, then normal DNS if it was not among them. */
        fun entryAttempts(order: List<String?>): List<String?> =
            order.take(MAX_ENTRY_ATTEMPTS).let { if (null in it) it else it + null }
    }
}
