package me.ayuilos.miffan.data.repository.relay

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import me.rerere.workspace.relay.RELAY_STREAM_CONTENT_TYPE
import me.rerere.workspace.relay.RelayHttpResponse
import me.rerere.workspace.relay.RelayRawStream
import me.rerere.workspace.relay.RelayRawStreamListener
import me.rerere.workspace.relay.RelayStreamTransport
import okhttp3.OkHttpClient
import org.chromium.net.BidirectionalStream
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.CronetProvider
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.chromium.net.impl.NativeCronetEngineBuilderWithLibraryLoaderImpl

/**
 * [RelayStreamTransport] backed by Cronet for HTTP/3 (QUIC over UDP) with transparent HTTP/2 fallback.
 *
 * Provider resolution order (never falling back to `JavaCronetProvider`):
 * 1. Verified on-demand native library (`libcronet.143.0.7445.0.so`) via [CronetNativeInstaller]
 * 2. Enabled GMS Cronet provider (`Google-Play-Services-Cronet-Provider`)
 * 3. Enabled Android 14+ system `HttpEngine` provider (if `ExperimentalCronetEngine` is supported)
 *
 * Because TCP may be completely blocked by an ISP (making HTTP `Alt-Svc` unreachable), every target
 * `(host, port)` is registered proactively via [CronetEngine.Builder.addQuicHint].
 */
class CronetRelayStreamTransport(
    context: Context,
    okHttpClient: OkHttpClient,
    private val installer: CronetNativeInstaller = CronetNativeInstaller(
        installRootDir = File(context.noBackupFilesDir, "cronet/${CronetNativeInstaller.CRONET_AAR_VERSION}"),
        okHttpClient = okHttpClient,
    ),
) : RelayStreamTransport {
    private val appContext = context.applicationContext ?: context
    private val engineLock = Any()
    private val warmupStarted = AtomicBoolean(false)
    private val quicHints = LinkedHashSet<Pair<String, Int>>()
    @Volatile private var cachedEngine: Pair<Set<Pair<String, Int>>, ExperimentalCronetEngine>? = null
    @Volatile private var gmsFailed = false
    @Volatile private var httpEngineFailed = false

    private val callbackExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "miffan-cronet-cb").apply { isDaemon = true }
    }
    private val warmupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "miffan-cronet-init").apply { isDaemon = true }
    }
    private val timeoutScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "miffan-cronet-timer").apply { isDaemon = true }
    }

    override fun isAvailable(): Boolean {
        if (cachedEngine != null) return true
        if (installer.installedFile() != null) return true
        if (!gmsFailed && enabledGmsProvider() != null) return true
        if (!httpEngineFailed && enabledSystemHttpEngineProvider() != null) return true
        return false
    }

    override fun startWarmup() {
        if (isAvailable()) return
        if (!warmupStarted.compareAndSet(false, true)) return
        warmupExecutor.execute {
            runCatching { ensureReady() }
        }
    }

    override fun ensureReady(): Boolean {
        if (cachedEngine != null) return true
        if (installer.installedFile() != null) return true
        if (!gmsFailed && enabledGmsProvider() != null) return true
        if (!httpEngineFailed && enabledSystemHttpEngineProvider() != null) return true
        return installer.ensureInstalled() != null
    }

    override fun get(url: String, headers: Map<String, String>, timeoutMillis: Int): RelayHttpResponse {
        val engine = engineFor(url)
        val latch = CountDownLatch(1)
        val bodyOut = ByteArrayOutputStream()
        var status = 0
        var failure: Throwable? = null
        val done = AtomicBoolean(false)

        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
                if (done.compareAndSet(false, true)) {
                    failure = IOException("Unexpected redirect to $newLocationUrl")
                    request.cancel()
                    latch.countDown()
                }
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                status = info.httpStatusCode
                request.read(ByteBuffer.allocateDirect(16 * 1024))
            }

            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
                byteBuffer.flip()
                val n = byteBuffer.remaining()
                if (n > 0) {
                    val remainingCap = MAX_UNARY_BODY_BYTES - bodyOut.size()
                    if (remainingCap > 0) {
                        val take = minOf(n, remainingCap)
                        val chunk = ByteArray(take)
                        byteBuffer.get(chunk)
                        bodyOut.write(chunk)
                    }
                }
                if (bodyOut.size() >= MAX_UNARY_BODY_BYTES) {
                    if (done.compareAndSet(false, true)) {
                        request.cancel()
                        latch.countDown()
                    }
                    return
                }
                byteBuffer.clear()
                request.read(byteBuffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                if (done.compareAndSet(false, true)) latch.countDown()
            }

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
                if (done.compareAndSet(false, true)) {
                    failure = error
                    latch.countDown()
                }
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                if (done.compareAndSet(false, true)) {
                    if (status == 0) failure = IOException("Cronet request canceled")
                    latch.countDown()
                }
            }
        }

        val req = engine.newUrlRequestBuilder(url, callback, callbackExecutor).apply {
            setHttpMethod("GET")
            for ((k, v) in headers) addHeader(k, v)
        }.build()
        req.start()

        if (!latch.await(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)) {
            if (done.compareAndSet(false, true)) failure = IOException("Cronet GET timed out after $timeoutMillis ms")
            req.cancel()
            throw (failure as IOException)
        }
        failure?.let { throw if (it is IOException) it else IOException(it) }
        return RelayHttpResponse(status, bodyOut.toString(Charsets.UTF_8.name()))
    }

    override fun openStream(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMillis: Int,
        listener: RelayRawStreamListener,
    ): RelayRawStream {
        val engine = engineFor(url)
        val wrapper = CronetBidirectionalWrapper(connectTimeoutMillis, listener)
        val builder = engine.newBidirectionalStreamBuilder(url, wrapper.callback, callbackExecutor)
        builder.setHttpMethod("POST")
        builder.addHeader("Content-Type", RELAY_STREAM_CONTENT_TYPE)
        for ((k, v) in headers) {
            if (!k.equals("Content-Type", ignoreCase = true)) {
                builder.addHeader(k, v)
            }
        }
        builder.delayRequestHeadersUntilFirstFlush(false)
        val stream = builder.build()
        wrapper.start(stream)
        return wrapper
    }

    private fun engineFor(url: String): ExperimentalCronetEngine {
        val hint = extractHttpsHint(url)
        synchronized(engineLock) {
            val added = hint != null && quicHints.add(hint)
            val current = cachedEngine
            if (current != null && !added) return current.second

            val hintsSnapshot = LinkedHashSet(quicHints)
            val built = buildEngineLocked(hintsSnapshot)
                ?: throw IOException("No native Cronet or HttpEngine QUIC provider available")
            cachedEngine = hintsSnapshot to built
            return built
        }
    }

    private fun buildEngineLocked(hints: Set<Pair<String, Int>>): ExperimentalCronetEngine? {
        installer.installedFile()?.let { soFile ->
            runCatching { buildDownloadedNativeEngine(soFile, hints) }.getOrNull()?.let { return it }
        }
        if (!gmsFailed) {
            enabledGmsProvider()?.let { provider ->
                val engine = runCatching { buildFromProvider(provider, hints) }.getOrNull()
                if (engine != null) return engine
                gmsFailed = true
            }
        }
        if (!httpEngineFailed) {
            enabledSystemHttpEngineProvider()?.let { provider ->
                val engine = runCatching { buildFromProvider(provider, hints) }.getOrNull()
                if (engine != null) return engine
                httpEngineFailed = true
            }
        }
        installer.ensureInstalled()?.let { soFile ->
            return runCatching { buildDownloadedNativeEngine(soFile, hints) }.getOrNull()
        }
        return null
    }

    private fun buildDownloadedNativeEngine(soFile: File, hints: Set<Pair<String, Int>>): ExperimentalCronetEngine {
        // Construct NativeCronetEngineBuilderWithLibraryLoaderImpl directly rather than NativeCronetProvider.createBuilder(),
        // because NativeCronetProvider delegates to HttpEngineNativeProvider on Android 14+ and ignores setLibraryLoader.
        val implBuilder = NativeCronetEngineBuilderWithLibraryLoaderImpl(appContext)
        val builder = ExperimentalCronetEngine.Builder(implBuilder)
        builder.setLibraryLoader(object : CronetEngine.Builder.LibraryLoader() {
            override fun loadLibrary(libName: String) {
                System.load(soFile.absolutePath)
            }
        })
        configureBuilder(builder, hints)
        return builder.build() as ExperimentalCronetEngine
    }

    private fun buildFromProvider(provider: CronetProvider, hints: Set<Pair<String, Int>>): ExperimentalCronetEngine? {
        val builder = provider.createBuilder()
        configureBuilder(builder, hints)
        val engine = builder.build()
        if (engine is ExperimentalCronetEngine) return engine
        runCatching { engine.shutdown() }
        return null
    }

    private fun configureBuilder(builder: CronetEngine.Builder, hints: Set<Pair<String, Int>>) {
        builder.enableQuic(true)
        builder.enableHttp2(true)
        for ((host, port) in hints) {
            builder.addQuicHint(host, port, port)
        }
    }

    private fun enabledGmsProvider(): CronetProvider? = runCatching {
        CronetProvider.getAllProviders(appContext).firstOrNull {
            it.isEnabled && it.name == GMS_PROVIDER_NAME
        }
    }.getOrNull()

    private fun enabledSystemHttpEngineProvider(): CronetProvider? = runCatching {
        CronetProvider.getAllProviders(appContext).firstOrNull {
            it.isEnabled &&
                it.name != CronetProvider.PROVIDER_NAME_FALLBACK &&
                it.name != CronetProvider.PROVIDER_NAME_APP_PACKAGED &&
                it.name != GMS_PROVIDER_NAME
        }
    }.getOrNull()

    private fun extractHttpsHint(url: String): Pair<String, Int>? = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        host to port
    }.getOrNull()

    private inner class CronetBidirectionalWrapper(
        private val connectTimeoutMillis: Int,
        private val listener: RelayRawStreamListener,
    ) : RelayRawStream {
        private val sendLock = Any()
        private val outbound = ArrayDeque<ByteArray>()
        private val queuedByteCount = AtomicLong(0L)
        private val finishOnce = AtomicBoolean(false)

        private var streamReady = false
        private var inFlightWrite = false
        private var inFlightSize = 0
        private var localClosing = false
        private var terminated = false

        @Volatile private lateinit var stream: BidirectionalStream
        @Volatile private var httpStatus = 0
        private val errorBody = ByteArrayOutputStream()
        private val responseStarted = AtomicBoolean(false)
        @Volatile private var connectTimeoutFuture: ScheduledFuture<*>? = null

        val callback: BidirectionalStream.Callback = object : BidirectionalStream.Callback() {
            override fun onStreamReady(stream: BidirectionalStream) {
                synchronized(sendLock) {
                    streamReady = true
                    pumpWriteLocked()
                }
            }

            override fun onResponseHeadersReceived(stream: BidirectionalStream, info: UrlResponseInfo) {
                responseStarted.set(true)
                connectTimeoutFuture?.cancel(false)
                httpStatus = info.httpStatusCode
                if (httpStatus == 200) {
                    listener.onOpen(this@CronetBidirectionalWrapper)
                }
                stream.read(ByteBuffer.allocateDirect(32 * 1024))
            }

            override fun onReadCompleted(
                stream: BidirectionalStream,
                info: UrlResponseInfo,
                buffer: ByteBuffer,
                endOfStream: Boolean,
            ) {
                buffer.flip()
                val n = buffer.remaining()
                if (n > 0) {
                    val bytes = ByteArray(n)
                    buffer.get(bytes)
                    if (httpStatus == 200) {
                        listener.onBytes(this@CronetBidirectionalWrapper, bytes, 0, n)
                    } else {
                        val remain = MAX_ERROR_BODY_BYTES - errorBody.size()
                        if (remain > 0) errorBody.write(bytes, 0, minOf(n, remain))
                    }
                }
                if (!endOfStream && !finishOnce.get()) {
                    buffer.clear()
                    stream.read(buffer)
                }
            }

            override fun onWriteCompleted(
                stream: BidirectionalStream,
                info: UrlResponseInfo,
                buffer: ByteBuffer,
                endOfStream: Boolean,
            ) {
                synchronized(sendLock) {
                    queuedByteCount.addAndGet(-inFlightSize.toLong())
                    inFlightSize = 0
                    inFlightWrite = false
                    pumpWriteLocked()
                }
            }

            override fun onSucceeded(stream: BidirectionalStream, info: UrlResponseInfo) {
                markTerminated()
                if (!finishOnce.compareAndSet(false, true)) return
                val status = httpStatus
                if (status != 0 && status != 200) {
                    listener.onHttpError(
                        this@CronetBidirectionalWrapper,
                        status,
                        errorBody.toString(Charsets.UTF_8.name()),
                    )
                } else {
                    listener.onClosed(this@CronetBidirectionalWrapper)
                }
            }

            override fun onFailed(stream: BidirectionalStream, info: UrlResponseInfo?, error: CronetException) {
                connectTimeoutFuture?.cancel(false)
                markTerminated()
                if (!finishOnce.compareAndSet(false, true)) return
                listener.onFailure(this@CronetBidirectionalWrapper, error)
            }

            override fun onCanceled(stream: BidirectionalStream, info: UrlResponseInfo?) {
                connectTimeoutFuture?.cancel(false)
                markTerminated()
                if (!finishOnce.compareAndSet(false, true)) return
                listener.onClosed(this@CronetBidirectionalWrapper)
            }
        }

        fun start(s: BidirectionalStream) {
            stream = s
            connectTimeoutFuture = timeoutScheduler.schedule(
                {
                    if (!responseStarted.get() && finishOnce.compareAndSet(false, true)) {
                        markTerminated()
                        runCatching { s.cancel() }
                        listener.onFailure(this, IOException("Cronet stream connect timed out after $connectTimeoutMillis ms"))
                    }
                },
                connectTimeoutMillis.toLong(),
                TimeUnit.MILLISECONDS,
            )
            s.start()
        }

        override fun send(bytes: ByteArray): Boolean {
            if (bytes.isEmpty()) return true
            synchronized(sendLock) {
                if (localClosing || terminated || finishOnce.get()) return false
                outbound.addLast(bytes)
                queuedByteCount.addAndGet(bytes.size.toLong())
                pumpWriteLocked()
                return true
            }
        }

        override fun queuedBytes(): Long = queuedByteCount.get().coerceAtLeast(0L)

        override fun close(code: Int, reason: String) {
            var cancelNow = false
            synchronized(sendLock) {
                if (!localClosing) {
                    localClosing = true
                    if (streamReady && !inFlightWrite && outbound.isEmpty() && !terminated) {
                        val empty = ByteBuffer.allocateDirect(0)
                        runCatching {
                            stream.write(empty, true)
                            stream.flush()
                        }.onFailure { cancelNow = true }
                    }
                }
                if (terminated) cancelNow = true
            }
            if (cancelNow || !responseStarted.get()) {
                runCatching { stream.cancel() }
            } else {
                // Ensure half-closed peer streams don't linger indefinitely if peer vanished.
                timeoutScheduler.schedule({ runCatching { stream.cancel() } }, 1_500, TimeUnit.MILLISECONDS)
            }
        }

        private fun markTerminated() {
            synchronized(sendLock) {
                terminated = true
                outbound.clear()
                queuedByteCount.set(0L)
            }
        }

        private fun pumpWriteLocked() {
            if (!streamReady || inFlightWrite || outbound.isEmpty() || terminated) return
            val next = outbound.removeFirst()
            val endOfStream = localClosing && outbound.isEmpty()
            val buf = ByteBuffer.allocateDirect(next.size)
            buf.put(next)
            buf.flip()
            inFlightWrite = true
            inFlightSize = next.size
            runCatching {
                stream.write(buf, endOfStream)
                stream.flush()
            }.onFailure { err ->
                queuedByteCount.addAndGet(-inFlightSize.toLong())
                inFlightSize = 0
                inFlightWrite = false
                terminated = true
                if (finishOnce.compareAndSet(false, true)) {
                    listener.onFailure(this, err)
                }
            }
        }
    }

    companion object {
        private const val GMS_PROVIDER_NAME = "Google-Play-Services-Cronet-Provider"
        private const val MAX_UNARY_BODY_BYTES = 256 * 1024
        private const val MAX_ERROR_BODY_BYTES = 4096
    }
}
