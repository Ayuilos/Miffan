package me.rerere.rdp

import android.media.MediaCodec
import android.graphics.Bitmap
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.os.Build
import java.util.Locale
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.workspace.screen.RemoteScreenFrameSink
import me.rerere.workspace.screen.RemoteScreenState
import me.rerere.workspace.screen.RfbCursor
import me.rerere.workspace.screen.RfbRect

/**
 * One connection over an owned, already-open transport. Closing [transport] must unblock reads
 * and writes. Sink callbacks run on the RDP thread and should return promptly.
 */
class RdpSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val transport: Closeable,
    private val credentials: RdpCredentials,
    private val options: RdpOptions,
    private val sink: RemoteScreenFrameSink,
) : Closeable {
    private val mutableState = MutableStateFlow<RemoteScreenState>(RemoteScreenState.Connecting)
    val state: StateFlow<RemoteScreenState> = mutableState.asStateFlow()
    private val fingerprint = MutableStateFlow<String?>(null)
    val certificateSha256: StateFlow<String?> = fingerprint.asStateFlow()
    private val remoteClipboard = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val clipboard: SharedFlow<String> = remoteClipboard.asSharedFlow()
    private var clipboardToSend: String? = null
    val bytesReceived: Long get() = synchronized(bufferLock) { received }
    private val mutableStats = MutableStateFlow(RdpStats())
    val stats: StateFlow<RdpStats> = mutableStats.asStateFlow()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val bufferLock = Object()
    private val buffer = ByteArray(1024 * 1024)
    private var head = 0
    private var count = 0
    private var eof = false
    private var received = 0L
    private var sent = 0L
    private var lastNetworkPublish = 0L
    @Volatile private var failure: Throwable? = null
    @Volatile private var paused = false
    private val wakeLock = Any()
    private var wakeHandle = 0L
    private var wakeGeneration = 0L
    private var observedGeneration = 0L
    // The worker unregisters under this same lock before destroying the WinPR handle.
    private fun signalWork() = synchronized(wakeLock) {
        wakeGeneration++
        if (wakeHandle != 0L) nativeSignal(wakeHandle)
    }
    private fun onWakeReady(handle: Long) = synchronized(wakeLock) {
        wakeHandle = handle
        if (handle != 0L) nativeSignal(handle)
    }
    private fun workAvailable(): Boolean {
        val bytes = synchronized(bufferLock) { count > 0 || eof }
        val changed = synchronized(wakeLock) {
            (observedGeneration != wakeGeneration).also { observedGeneration = wakeGeneration }
        }
        return bytes || changed || isClosed()
    }
    private fun eventDriven() = options.eventDriven
    private fun ackBeforeSink() = options.ackBeforeSink
    private val commands = ArrayBlockingQueue<IntArray>(4096)
    private var cancellation: Job? = null
    @Volatile private var reader: Thread? = null
    @Volatile private var worker: Thread? = null
    private var frames = 0L
    private var windowFrames = 0L
    private var windowStart = System.nanoTime()

    @Synchronized fun start(scope: CoroutineScope) {
        check(!closed.get() && scope.isActive) { "Session is closed or scope is inactive" }
        check(started.compareAndSet(false, true)) { "Session already started" }
        try {
            Native.load()
        } catch (error: Throwable) {
            failure = error
            close()
            throw error
        }
        reader = Thread({ pump() }, "RDP-stream-reader").also { it.start() }
        worker = Thread({
            try {
                val directory = java.io.File(
                    requireNotNull(System.getProperty("java.io.tmpdir")), "miffan-rdp",
                ).apply { check(mkdirs() || isDirectory) }.absolutePath
                nativeRun(
                    credentials.username, credentials.password, credentials.domain ?: "",
                    options.width, options.height, options.security.ordinal, directory,
                )
            } catch (error: Throwable) {
                if (!closed.get()) failure = error
            } finally {
                close()
                reader?.join()
                cancellation?.cancel()
            }
        }, "RDP-session").also { it.start() }
        cancellation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        if (closed.get()) cancellation?.cancel()
    }

    fun setPaused(paused: Boolean) { this.paused = paused; signalWork() }
    fun pointer(x: Int, y: Int, buttons: Int) = enqueue(intArrayOf(1, x.coerceIn(0, 65535), y.coerceIn(0, 65535), buttons and 127))
    fun key(keysym: Int, down: Boolean) {
        RdpKeys.scancode(keysym)?.let { enqueue(intArrayOf(2, it, if (down) 1 else 0)) }
    }
    /** Rejects unsupported/long text atomically, without sending a prefix or closing the session. */
    fun typeText(text: String): Boolean {
        val accepted = synchronized(commands) {
            if (closed.get() || !RdpText.canTypeDirectly(text) || commands.remainingCapacity() < text.length) return false
            text.forEach { commands.offer(intArrayOf(3, it.code)) }
            true
        }
        if (accepted) signalWork()
        return accepted
    }
    fun sendClipboard(text: String) {
        RdpText.requireClipboardSize(text)
        synchronized(commands) {
            if (closed.get()) return
            clipboardToSend = text // coalesce; keep at most one bounded clipboard in memory
        }
        signalWork()
    }
    private fun takeClipboard(): String? = synchronized(commands) {
        clipboardToSend.also { clipboardToSend = null }
    }
    private fun onClipboardFailure() {
        if (!closed.get() && failure == null) failure = IllegalStateException("RDP clipboard negotiation failed")
    }
    private fun onClipboard(text: String) { if (!closed.get()) remoteClipboard.tryEmit(text) }
    private fun enqueue(command: IntArray) = synchronized(commands) {
        if (!closed.get() && !commands.offer(command)) {
            failure = IllegalStateException("RDP input queue overflow")
        }
        signalWork()
    }

    private fun pump() {
        val chunk = ByteArray(32768)
        try {
            while (!closed.get()) {
                val length = input.read(chunk)
                if (length < 0) break
                var offset = 0
                synchronized(bufferLock) {
                    received += length
                    // Receive counters also advance while the worker is decoding or no
                    // EndPaint occurs. CAS updates preserve concurrently published timings.
                    val now = System.nanoTime()
                    if (options.liveNetworkStats && now - lastNetworkPublish >= 500_000_000L) {
                        mutableStats.update { it.copy(bytesReceived = received) }
                        lastNetworkPublish = now
                    }
                    while (offset < length && !closed.get()) {
                        while (count == buffer.size && !closed.get()) bufferLock.wait()
                        val tail = (head + count) % buffer.size
                        val copy = minOf(length - offset, buffer.size - count, buffer.size - tail)
                        chunk.copyInto(buffer, tail, offset, offset + copy)
                        count += copy
                        signalWork()
                        offset += copy
                        bufferLock.notifyAll()
                    }
                }
            }
        } catch (error: Throwable) {
            if (!closed.get()) failure = error
        } finally {
            synchronized(bufferLock) { eof = true; bufferLock.notifyAll() }
            signalWork()
        }
    }

    // JNI entry points. The byte stream is nonblocking to FreeRDP, even for blocking SSH streams.
    private fun readTransport(bytes: ByteArray): Int = synchronized(bufferLock) {
        if (closed.get()) return -1
        if (count == 0) return if (eof) -1 else 0
        val length = minOf(count, bytes.size)
        val first = minOf(length, buffer.size - head)
        buffer.copyInto(bytes, 0, head, head + first)
        if (first < length) buffer.copyInto(bytes, first, 0, length - first)
        head = (head + length) % buffer.size
        count -= length
        bufferLock.notifyAll()
        length
    }
    private fun writeTransport(bytes: ByteArray): Boolean = try {
        if (closed.get()) false else { output.write(bytes); output.flush(); sent += bytes.size; true }
    } catch (error: Throwable) { if (!closed.get()) failure = error; false }
    private fun waitTransport(timeout: Int): Boolean = synchronized(bufferLock) {
        if (count == 0 && !eof && !closed.get()) bufferLock.wait(timeout.coerceIn(1, 50).toLong())
        count > 0 || eof || closed.get()
    }
    private fun isClosed() = closed.get() || failure != null
    private fun isPaused() = paused
    private fun nextCommand(): IntArray? = synchronized(commands) { commands.poll() }
    private fun verifyCertificate(actual: String): Boolean {
        fingerprint.value = actual
        return CertificateFingerprint.matches(options.certificateSha256, actual).also {
            if (!it) failure = SecurityException("RDP certificate SHA-256 mismatch")
        }
    }
    private fun onSize(width: Int, height: Int, security: String) {
        if (closed.get()) return
        sink.onSize(width, height, 1)
        mutableState.update { current ->
            if (current is RemoteScreenState.Closed) current else RemoteScreenState.Connected("RDP", width, height, 1)
        }
        mutableStats.update { it.copy(security = security, width = width, height = height) }
    }
    private fun acquireBitmap(width: Int, height: Int): Bitmap? {
        if (!options.directBitmap || paused || closed.get()) return null
        val direct = sink as? RdpBitmapFrameSink ?: return null
        val bitmap = direct.acquireBitmap(width, height) ?: return null
        if (!bitmap.isRecycled && bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888 &&
            bitmap.width == width && bitmap.height == height) return bitmap
        direct.releaseBitmap(bitmap)
        return null
    }
    private fun releaseBitmap(bitmap: Bitmap) { (sink as RdpBitmapFrameSink).releaseBitmap(bitmap) }
    private fun onPixels(x: Int, y: Int, width: Int, height: Int, pixels: IntArray) {
        if (!paused && !closed.get()) sink.onPixels(RfbRect(x, y, width, height), pixels)
    }
    private fun onFrame() {
        if (!paused && !closed.get()) { sink.onFrameComplete(); frames++; windowFrames++ }
        publishStats()
    }
    private fun onCursor(width: Int, height: Int, x: Int, y: Int, pixels: IntArray) {
        if (!closed.get()) sink.onCursor(RfbCursor(width, height, x, y, pixels))
    }
    private fun onEncoding(encoding: String, decoder: String?) {
        if (mutableStats.value.encoding == encoding && mutableStats.value.decoder == decoder) return
        val hardware = if (decoder != null && Build.VERSION.SDK_INT >= 29) {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == decoder }?.isHardwareAccelerated
        } else null
        mutableStats.update { it.copy(encoding = encoding, decoder = decoder, h264HardwareAccelerated = hardware) }
    }
    // Called only on the RDP worker. Probe vendor parameters before configuring the NDK codec.
    // API 31 enumeration is essential for Codec2: never infer OMX-era keys from the name.
    private fun preferredDecoder(): String? {
        if (!options.lowLatency || Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && !it.isAlias && it.supportedTypes.any { type -> type == "video/avc" } &&
                    it.getCapabilitiesForType("video/avc").let { caps ->
                        caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency) &&
                            caps.videoCapabilities?.isSizeSupported(options.width, options.height) == true
                    }
            }?.name
        }.getOrNull()
    }
    private fun decoderOptions(name: String): Int {
        val feature = if (Build.VERSION.SDK_INT >= 30) runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == name }
                ?.getCapabilitiesForType("video/avc")
                ?.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        }.getOrNull() else null
        mutableStats.update { it.copy(lowLatencySupported = feature) }
        if (!options.lowLatency) return 0
        var flags = 1 // KEY_PRIORITY=0 (API 23+)
        if (feature == true) flags = flags or 2
        if (Build.VERSION.SDK_INT >= 31 && (name.startsWith("c2.qti.") || name.startsWith("OMX.qcom."))) {
            runCatching {
                val probe = MediaCodec.createByCodecName(name)
                try {
                    val parameters = probe.supportedVendorParameters
                    if ("vendor.qti-ext-dec-low-latency.enable" in parameters &&
                        probe.getParameterDescriptor("vendor.qti-ext-dec-low-latency.enable")?.type == MediaFormat.TYPE_INTEGER) flags = flags or 4
                    if ("vendor.qti-ext-dec-picture-order.enable" in parameters &&
                        probe.getParameterDescriptor("vendor.qti-ext-dec-picture-order.enable")?.type == MediaFormat.TYPE_INTEGER) flags = flags or 8
                    val latencyParameters = parameters.filter { "latency" in it || "picture-order" in it }.sorted()
                    Log.i("RemoteScreenPerf", "RDP decoder=$name vendorLatencyParameters=$latencyParameters")
                    Log.i("RemoteScreenPerf", "RDP decoder=$name FEATURE_LowLatency=$feature qtiLow=${flags and 4 != 0} qtiOrder=${flags and 8 != 0}")
                } finally { probe.release() }
            }.onFailure { Log.i("RemoteScreenPerf", "RDP vendor parameter query unavailable for $name") }
        }
        return flags
    }
    private fun onDecoderFailure() {
        if (!closed.get() && failure == null) failure = IllegalStateException("MediaCodec timed out waiting for an RDP frame (5 seconds)")
    }
    private fun onDecoderConfiguration(configuration: String) {
        mutableStats.update { it.copy(decoderConfiguration = configuration) }
    }
    private var lastPerfLog = System.nanoTime()
    private fun onPerformance(values: DoubleArray) {
        check(values.size == 54)
        mutableStats.update { it.copy(
            decodeMs = values[0], decodeMeanMs = values[1], decodeMaxMs = values[2],
            decodedFrames = values[3].toLong(), decodeFramesPerSecond = values[4],
            inFlightFrames = values[5].toInt(), peakInFlightFrames = values[6].toInt(),
            outputWaitTimeouts = values[7].toLong(),
            yuvToRgbMs = values[8], yuvToRgbMeanMs = values[9], yuvToRgbMaxMs = values[10],
            sinkMs = values[11], sinkMeanMs = values[12], sinkMaxMs = values[13],
            surfaceToAckMs = values[14], surfaceToAckMeanMs = values[15], surfaceToAckMaxMs = values[16],
            neonYuv = values[17] == 1.0, pendingDecodeMs = values[18],
            ackQueueMs = values[19], ackQueueMeanMs = values[20], ackQueueMaxMs = values[21],
            frameDataMs = values[22], frameDataMeanMs = values[23], frameDataMaxMs = values[24],
            surfaceWorkMs = values[25], surfaceWorkMeanMs = values[26], surfaceWorkMaxMs = values[27],
            loopWaitMs = values[28], loopWaitMeanMs = values[29], loopWaitMaxMs = values[30],
            composeMs = values[31], composeMeanMs = values[32], composeMaxMs = values[33],
            inputWaitMs = values[34], inputWaitMeanMs = values[35], inputWaitMaxMs = values[36],
            outputWaitMs = values[37], outputWaitMeanMs = values[38], outputWaitMaxMs = values[39],
            outputAccessMs = values[40], outputAccessMeanMs = values[41], outputAccessMaxMs = values[42],
            outputReleaseMs = values[43], outputReleaseMeanMs = values[44], outputReleaseMaxMs = values[45],
            inputQueueMs = values[46], inputQueueMeanMs = values[47], inputQueueMaxMs = values[48],
            transportReadCalls = values[49].toLong(), transportReadBytes = values[50].toLong(), frameReadCalls = values[51].toLong(), directBitmapFrames = values[52].toLong(), ackBeforeSinkFrames = values[53].toLong(),
        ) }
    }
    private fun publishStats() {
        val now = System.nanoTime()
        val elapsed = now - windowStart
        if (options.liveNetworkStats) mutableStats.update { it.copy(bytesReceived = bytesReceived, bytesSent = sent) }
        if (elapsed < 500_000_000) return
        mutableStats.update { it.copy(frames = frames, framesPerSecond = windowFrames * 1e9 / elapsed,
            bytesReceived = synchronized(bufferLock) { received }, bytesSent = sent) }
        if (now - lastPerfLog >= 2_000_000_000L) {
            val s = mutableStats.value
            fun ms(last: Double, mean: Double, max: Double) = String.format(Locale.ROOT, "%.2f/%.2f/%.2f", last, mean, max)
            Log.i("RemoteScreenPerf", "RDP ${s.width}x${s.height} ${s.security} ${s.encoding} decoder=${s.decoder} config=${s.decoderConfiguration} " +
                String.format(Locale.ROOT, "fps=%.2f decodeFps=%.2f ", s.framesPerSecond, s.decodeFramesPerSecond) +
                "decodeMs(last/mean/max)=${ms(s.decodeMs,s.decodeMeanMs,s.decodeMaxMs)} inFlight=${s.inFlightFrames}/${s.peakInFlightFrames} pendingDecodeMs=${String.format(Locale.ROOT, "%.2f", s.pendingDecodeMs)} outputTimeouts=${s.outputWaitTimeouts} " +
                "yuvMs=${ms(s.yuvToRgbMs,s.yuvToRgbMeanMs,s.yuvToRgbMaxMs)} neon=${s.neonYuv} sinkMs=${ms(s.sinkMs,s.sinkMeanMs,s.sinkMaxMs)} " +
                "surfaceToAckMs=${ms(s.surfaceToAckMs,s.surfaceToAckMeanMs,s.surfaceToAckMaxMs)} ackQueueMs=${ms(s.ackQueueMs,s.ackQueueMeanMs,s.ackQueueMaxMs)} " +
                "frameDataMs=${ms(s.frameDataMs,s.frameDataMeanMs,s.frameDataMaxMs)} surfaceWorkMs=${ms(s.surfaceWorkMs,s.surfaceWorkMeanMs,s.surfaceWorkMaxMs)} " +
                "loopWaitMs=${ms(s.loopWaitMs,s.loopWaitMeanMs,s.loopWaitMaxMs)} composeMs=${ms(s.composeMs,s.composeMeanMs,s.composeMaxMs)} " +
                "inputWaitMs=${ms(s.inputWaitMs,s.inputWaitMeanMs,s.inputWaitMaxMs)} outputWaitMs=${ms(s.outputWaitMs,s.outputWaitMeanMs,s.outputWaitMaxMs)} " +
                "outputAccessMs=${ms(s.outputAccessMs,s.outputAccessMeanMs,s.outputAccessMaxMs)} outputReleaseMs=${ms(s.outputReleaseMs,s.outputReleaseMeanMs,s.outputReleaseMaxMs)} " +
                "inputQueueMs=${ms(s.inputQueueMs,s.inputQueueMeanMs,s.inputQueueMaxMs)} reads=${s.transportReadCalls} frameReads=${s.frameReadCalls} bitmapFrames=${s.directBitmapFrames} ackBeforeSinkFrames=${s.ackBeforeSinkFrames} rx=${s.bytesReceived}")
            lastPerfLog = now
        }
        windowFrames = 0
        windowStart = now
    }
    private fun onFailure(code: Long) {
        if (code != 0L && !closed.get() && failure == null) failure = IllegalStateException("FreeRDP error 0x${code.toString(16)}")
    }
    /** Closes the owned transport to unblock both IO threads; native cleanup happens on the worker. */
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        signalWork()
        runCatching { transport.close() }
        synchronized(bufferLock) { bufferLock.notifyAll() }
        synchronized(commands) { commands.clear(); clipboardToSend = null }
        mutableState.value = RemoteScreenState.Closed(failure)
        cancellation?.cancel()
    }
    /** For tests/background callers only; never blocks the main thread. */
    internal fun awaitStopped(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        listOfNotNull(worker, reader).forEach { it.join(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)) }
        return worker?.isAlive != true && reader?.isAlive != true
    }
    private external fun nativeSignal(handle: Long)
    private external fun nativeRun(username: String, password: String, domain: String, width: Int, height: Int, security: Int, directory: String)
    private object Native { fun load() { System.loadLibrary("miffanrdp") } }
}
