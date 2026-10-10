package me.rerere.stream

import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.stream.StreamProtocol.value
import java.io.Closeable
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StreamSession(private val host: StreamHost, private val app: StreamApp, private val udpAddress: String,
    private val config: StreamConfig, surface: Surface) : Closeable {
    private val mutableState = MutableStateFlow<StreamState>(StreamState.Connecting)
    val state: StateFlow<StreamState> = mutableState.asStateFlow()
    private val mutableStats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = mutableStats.asStateFlow()
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val finished = CountDownLatch(1)
    @Volatile private var stop = CountDownLatch(1)
    @Volatile private var bridge = TcpBridge(host.connector)
    @Volatile private var currentSurface: Surface? = surface
    @Volatile private var stage = 0
    @Volatile private var failure: StreamState.Failed? = null
    @Volatile private var decoderFailure = false
    @Volatile private var worker: Thread? = null
    private var watcher: Job? = null
    @Volatile private var decoder: StreamDecoder? = null
    internal var allowSoftwareDecoder = false
    private val inputs = Any()
    private val pressedKeys = mutableMapOf<Int, Int>()
    private val pressedButtons = mutableSetOf<StreamMouseButton>()
    private val metrics = Any()
    private var firstFrameTime = 0L
    private var bytes = 0L
    private var meanTotal = 0.0
    private var previousFrame = -1
    @Volatile private var nativeActive = false
    @Volatile private var nativeEstablished = false
    @Volatile private var connectionDeadline = Long.MAX_VALUE
    @Volatile private var decoderConfiguring = false
    private var launchCompletedAt = 0L
    private var controlReady = false

    init { require(StreamProtocol.numericAddress(udpAddress)) { "UDP address must be numeric IPv4 or IPv6" } }

    fun start(scope: CoroutineScope) {
        check(!closed.get() && started.compareAndSet(false, true)) { "Session already started or closed" }
        synchronized(processLock) {
            if (active != null) {
                mutableState.value = StreamState.Failed(StreamFailureReason.BUSY, 0, -201); finished.countDown(); return
            }
            active = this
        }
        watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { close() } }
        worker = Thread({ run() }, "StreamSession").apply { start() }
    }
    private fun run() {
        connectionDeadline = Long.MAX_VALUE
        val watchdog = Thread({
            while (finished.count > 0) {
                if (!closed.get() && mutableState.value !is StreamState.Streaming && System.nanoTime() >= connectionDeadline && failure == null) {
                    failure = StreamState.Failed(when {
                        decoderConfiguring || stats.value.receivedFrames > 0 -> StreamFailureReason.DECODER
                        stage >= 8 -> StreamFailureReason.UDP_UNREACHABLE
                        else -> StreamFailureReason.TCP_RTSP
                    }, stage, -202)
                    stop.countDown(); bridge.close()
                }
                if (closed.get() || failure != null) interruptOwned()
                Thread.sleep(20)
            }
        }, "StreamDeadline").apply { isDaemon = true; start() }
        try {
            while (!closed.get() && host.retryAfterMillis > 0) {
                mutableStats.update { it.copy(retryAfterMillis = host.retryAfterMillis) }
                Thread.sleep(minOf(50, host.retryAfterMillis.coerceAtLeast(1)))
            }
            mutableStats.update { it.copy(retryAfterMillis = 0) }
            connectionDeadline = System.nanoTime() + config.connectTimeoutMillis * 1_000_000
            var codecs = config.codecs
            while (!closed.get() && failure == null) {
                stage = 0; launchCompletedAt = 0; controlReady = false; nativeEstablished = false; decoderFailure = false; stop = CountDownLatch(1); decoder = StreamDecoder(this)
                val info = StreamProtocol.server(host.request("serverinfo", bridge = bridge,
                    timeout = remaining(connectionDeadline)))
                if (!info.paired) throw StreamException(StreamFailureReason.HOST_REJECTED)
                val random = SecureRandom(); val key = ByteArray(16).also(random::nextBytes)
                val iv = ByteArray(16); random.nextBytes(iv); iv.fill(0, 4)
                val keyId = ((iv[0].toInt() and 255) shl 24) or ((iv[1].toInt() and 255) shl 16) or
                    ((iv[2].toInt() and 255) shl 8) or (iv[3].toInt() and 255)
                val response = try { host.request(if (info.currentApp != 0) "resume" else "launch", mapOf(
                    "appid" to app.id.toString(), "mode" to "${config.width}x${config.height}x${config.fps}",
                    "additionalStates" to "1", "sops" to "0", "rikey" to StreamProtocol.hex(key), "rikeyid" to keyId.toString(),
                    "localAudioPlayMode" to "0", "surroundAudioInfo" to "196610", "remoteControllersBitmap" to "0",
                    "gcmap" to "0", "corever" to "1"), bridge = bridge, timeout = remaining(connectionDeadline))
                } finally {
                    // Start after the response, not before a potentially slow forwarded HTTP request.
                    // If the reply is lost, use the request's end as a conservative anchor.
                    launchCompletedAt = System.nanoTime()
                }
                if (response.value("gamesession") == "0" || response.value("resume") == "0") throw StreamException(StreamFailureReason.HOST_REJECTED)
                val url = response.value("sessionUrl0")
                if (!url.startsWith("rtspenc://")) throw StreamException(StreamFailureReason.ENCRYPTION_REQUIRED)
                if (closed.get() || failure != null) break
                nativeActive = true
                val result = try {
                    StreamNative.run(this, udpAddress, info.version, info.gfeVersion, info.codecModeSupport, url,
                        config.width, config.height, config.fps, config.bitrateKbps,
                        (if (StreamCodec.H264 in codecs) 1 else 0) or (if (StreamCodec.HEVC in codecs) 0x100 else 0), key, iv)
                } finally { nativeActive = false; key.fill(0); iv.fill(0) }
                decoder?.close(); decoder = null; bridge.close(); bridge.awaitStopped(2000)
                if (!controlReady && launchCompletedAt != 0L) host.deferLaunch(launchCompletedAt)
                val wasStreaming = mutableState.value is StreamState.Streaming
                if (decoderFailure && wasStreaming) connectionDeadline = System.nanoTime() + config.connectTimeoutMillis * 1_000_000
                if (decoderFailure && StreamCodec.HEVC in codecs && StreamCodec.H264 in codecs && !closed.get() && System.nanoTime() < connectionDeadline) {
                    codecs = setOf(StreamCodec.H264); failure = null; mutableState.value = StreamState.Connecting
                    bridge = TcpBridge(host.connector); resetMetrics(); continue
                }
                if (decoderFailure && !closed.get() && failure == null) failure = StreamState.Failed(StreamFailureReason.DECODER, stage, -200)
                if (!closed.get() && failure == null && result != 0) failure = streamFailure(stage, result)
                break
            }
        } catch (e: Throwable) {
            if (!closed.get() && failure == null) failure = if (e is StreamException) StreamState.Failed(e.reason, e.stage, e.code)
                else StreamState.Failed(StreamFailureReason.TCP_RTSP, stage, -203)
        } finally {
            if (!controlReady && launchCompletedAt != 0L) host.deferLaunch(launchCompletedAt)
            releaseAllInput(); bridge.close(); bridge.awaitStopped(2000); decoder?.close(); decoder = null
            mutableState.value = failure ?: StreamState.Closed
            synchronized(processLock) { if (active === this) active = null }
            finished.countDown(); watchdog.join(1000); watcher?.cancel()
        }
    }
    private fun remaining(deadline: Long) = ((deadline - System.nanoTime()) / 1_000_000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    private fun interruptOwned() = synchronized(processLock) { if (active === this && nativeActive) StreamNative.interrupt() }
    // JNI callbacks, only called while this session owns common-c.
    private fun openTcp(port: Int): Int = if (closed.get() || failure != null || decoderFailure) -1 else bridge.open(port)
    private fun setupDecoder(format: Int, width: Int, height: Int, fps: Int): Boolean {
        mutableStats.update { it.copy(codec = if (format and 0x100 != 0) StreamCodec.HEVC else StreamCodec.H264, width = width, height = height, fps = fps) }
        decoderConfiguring = true
        val ok = try { decoder!!.setup(format, width, height, currentSurface) } finally { decoderConfiguring = false }
        if (!ok) decoderFailure = true
        return ok
    }
    private fun submitFrame(data: ByteArray, pts: Long, frameNumber: Int, idr: Boolean): Boolean {
        synchronized(metrics) {
            if (firstFrameTime == 0L) firstFrameTime = System.nanoTime()
            bytes += data.size
            val lost = if (previousFrame >= 0) (frameNumber - previousFrame - 1).coerceAtLeast(0) else 0
            previousFrame = frameNumber
            mutableStats.update { it.copy(receivedFrames = it.receivedFrames + 1, droppedFrames = it.droppedFrames + lost) }
        }
        return decoder?.frame(data, pts, idr) ?: false
    }
    private fun nativeEvent(kind: Int, newStage: Int, code: Int) {
        when (kind) {
            0 -> {
                stage = newStage
                if (newStage >= 9) { controlReady = true; host.controlEstablished() }
            }
            1 -> if (!decoderFailure && failure == null && !closed.get()) failure = streamFailure(newStage, code)
            2 -> {
                nativeEstablished = true
                if (stats.value.renderedFrames > 0 && !closed.get() && failure == null) mutableState.value = StreamState.Streaming
            }
            3 -> { if (!closed.get() && failure == null && !decoderFailure) failure = streamFailure(stage, code); stop.countDown() }
        }
    }
    private fun waitForStop() {
        while (!stop.await(100, TimeUnit.MILLISECONDS)) {
            if (closed.get() || failure != null || decoderFailure) break
            publish()
        }
        releaseAllInput()
    }
    private fun publish() {
        val n = StreamNative.networkStats()
        synchronized(metrics) {
            val elapsed = if (firstFrameTime == 0L) 0.0 else (System.nanoTime() - firstFrameTime) / 1e9
            mutableStats.update { it.copy(videoEncrypted = n[0] != 0L, audioEncrypted = n[1] != 0L, controlEncrypted = n[2] != 0L,
                rttMs = n[3].takeIf { v -> v >= 0 }?.toInt(), audioPackets = n[4], fecFailureEvents = n[5], idrRequestsSent = n[6],
                receivedFps = if (elapsed > 0) it.receivedFrames / elapsed else 0.0,
                renderedFps = if (elapsed > 0) it.renderedFrames / elapsed else 0.0,
                bitrateKbps = if (elapsed > 0) bytes * 8 / elapsed / 1000 else 0.0) }
        }
    }
    internal fun decoderConfigured(name: String, low: Boolean, hardware: Boolean) {
        mutableStats.update { it.copy(decoderName = name, lowLatency = low, hardwareAccelerated = hardware) }
    }
    internal fun rendered(ms: Double) {
        synchronized(metrics) {
            meanTotal += ms
            mutableStats.update { it.copy(renderedFrames = it.renderedFrames + 1,
                decodeMeanMs = meanTotal / (it.renderedFrames + 1), decodeMaxMs = maxOf(it.decodeMaxMs, ms)) }
        }
        if (nativeEstablished && !closed.get() && failure == null) mutableState.value = StreamState.Streaming
    }
    internal fun dropped() { mutableStats.update { it.copy(droppedFrames = it.droppedFrames + 1) } }
    internal fun decoderFailed() { decoderFailure = true; stop.countDown(); interruptOwned() }
    private fun resetMetrics() = synchronized(metrics) { firstFrameTime = 0; bytes = 0; meanTotal = 0.0; previousFrame = -1; mutableStats.value = StreamStats() }
    private fun send(kind: Int, a: Int, b: Int = 0, c: Int = 0, d: Int = 0) {
        synchronized(processLock) { if (active === this && nativeActive) StreamNative.input(kind, a, b, c, d) }
    }
    fun mousePosition(x: Int, y: Int, referenceWidth: Int, referenceHeight: Int) {
        require(referenceWidth in 1..32767 && referenceHeight in 1..32767)
        send(1, x.coerceIn(0, referenceWidth), y.coerceIn(0, referenceHeight), referenceWidth, referenceHeight)
    }
    fun mouseMove(dx: Int, dy: Int) = send(0, dx.coerceIn(-32768, 32767), dy.coerceIn(-32768, 32767))
    fun mouseButton(button: StreamMouseButton, down: Boolean) = synchronized(inputs) {
        if (down) pressedButtons.add(button) else pressedButtons.remove(button)
        send(2, button.wire, if (down) 1 else 0)
    }
    fun scroll(vertical: Int, horizontal: Int) {
        send(3, vertical.coerceIn(-32768, 32767)); send(4, horizontal.coerceIn(-32768, 32767))
    }
    fun key(windowsVirtualKey: Int, down: Boolean, modifiers: Int) = synchronized(inputs) {
        require(windowsVirtualKey in 0..255 && modifiers in 0..31)
        if (down) pressedKeys[windowsVirtualKey] = modifiers else pressedKeys.remove(windowsVirtualKey)
        send(5, windowsVirtualKey, if (down) 1 else 0, modifiers)
    }
    fun releaseAllInput() = synchronized(inputs) {
        pressedKeys.toMap().forEach { (key, mods) -> send(5, key, 0, mods) }; pressedKeys.clear()
        pressedButtons.toList().forEach { send(2, it.wire, 0) }; pressedButtons.clear()
    }
    fun setSurface(surface: Surface?) {
        currentSurface = surface; decoder?.surface(surface)
        requestIdr()
    }
    internal fun requestIdr() {
        synchronized(processLock) { if (active === this && nativeActive) StreamNative.idr() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        releaseAllInput(); stop.countDown(); bridge.close(); interruptOwned()
        if (!started.get()) { mutableState.value = StreamState.Closed; finished.countDown() }
    }
    /** Wait off the UI thread before starting another candidate. */
    fun awaitStopped(timeoutMillis: Long = 10000): Boolean = finished.await(timeoutMillis, TimeUnit.MILLISECONDS)
    private companion object { val processLock = Any(); var active: StreamSession? = null }
}
