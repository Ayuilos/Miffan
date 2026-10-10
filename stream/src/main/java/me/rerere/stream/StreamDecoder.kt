package me.rerere.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class StreamDecoder(private val session: StreamSession) : Closeable {
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var mime = ""
    private var width = 0
    private var height = 0
    private val closed = AtomicBoolean()
    private val submitted = ConcurrentHashMap<Long, Long>()
    private var failures = 0
    private var lastOutput = System.nanoTime()
    private var stallRecoveries = 0
    private var waitingIdr = true
    private val drain = Thread({
        val info = MediaCodec.BufferInfo()
        while (!closed.get()) {
            try {
                synchronized(lock) {
                    val c = codec
                    if (c != null) {
                        var index = c.dequeueOutputBuffer(info, 0)
                        while (index >= 0) {
                            val now = System.nanoTime(); lastOutput = now; stallRecoveries = 0
                            val start = submitted.remove(info.presentationTimeUs)
                            c.releaseOutputBuffer(index, surface != null)
                            if (start != null && info.size >= 0) session.rendered((now - start) / 1e6)
                            index = c.dequeueOutputBuffer(info, 0)
                        }
                        // Bound stalled codecs; fallback must not wait forever for output.
                        val checkTime = now()
                        // A new frame after a network gap must get its own decode budget.
                        // Only pending inputs that actually waited 3s indicate a codec stall.
                        if (checkTime - lastOutput > 3_000_000_000L && submitted.values.any { checkTime - it > 3_000_000_000L }) {
                            // Lost references may leave a valid codec waiting for an IDR.
                            // Flush and retry before treating repeated stalls as decoder failure.
                            if (++stallRecoveries >= 3) session.decoderFailed()
                            else { c.flush(); waitingIdr = true; session.requestIdr() }
                            lastOutput = checkTime; submitted.clear()
                        }
                    }
                }
            } catch (_: Exception) { if (++failures >= 3) session.decoderFailed() }
            Thread.sleep(2)
        }
    }, "StreamCodecOutput").apply { isDaemon = true; start() }
    private fun now() = System.nanoTime()
    fun setup(format: Int, w: Int, h: Int, target: Surface?): Boolean = synchronized(lock) {
        mime = if (format and 0x100 != 0) "video/hevc" else "video/avc"; width = w; height = h; surface = target
        runCatching { recreate() }.isSuccess
    }
    private fun recreate() {
        codec?.let { runCatching { it.stop() }; it.release() }; codec = null
        submitted.clear(); waitingIdr = true; stallRecoveries = 0; lastOutput = now()
        val target = surface ?: return
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            !it.isEncoder && it.supportedTypes.any { t -> t == mime } &&
                it.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(width, height) == true
        }.sortedByDescending { if (Build.VERSION.SDK_INT >= 29) it.isHardwareAccelerated else !it.name.startsWith("OMX.google.") }
        var last: Throwable? = null
        for (candidate in candidates) {
            val hardware = if (Build.VERSION.SDK_INT >= 29) candidate.isHardwareAccelerated else !candidate.name.startsWith("OMX.google.")
            // Emulator software codecs are allowed only with an explicit test configuration.
            if (!hardware && !session.allowSoftwareDecoder) continue
            var c: MediaCodec? = null
            try {
                c = MediaCodec.createByCodecName(candidate.name)
                val caps = candidate.getCapabilitiesForType(mime)
                var low = Build.VERSION.SDK_INT >= 30 && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                val f = MediaFormat.createVideoFormat(mime, width, height).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (low) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    for ((key, value) in listOf("vendor.qti-ext-dec-low-latency.enable" to 1, "vendor.qti-ext-dec-picture-order.enable" to 0)) {
                        if (key in c.supportedVendorParameters && c.getParameterDescriptor(key)?.type == MediaFormat.TYPE_INTEGER) {
                            f.setInteger(key, value); if (key.contains("low-latency")) low = true
                        }
                    }
                }
                c.configure(f, target, null, 0); c.start(); codec = c
                session.decoderConfigured(candidate.name, low, hardware); return
            } catch (e: Throwable) { last = e; c?.let { runCatching { it.release() } } }
        }
        throw IllegalStateException("No usable decoder", last)
    }
    fun surface(target: Surface?) = synchronized(lock) {
        surface = target
        if (mime.isNotEmpty()) {
            try { recreate() } catch (_: Exception) { session.decoderFailed() }
        }
    }
    fun frame(bytes: ByteArray, pts: Long, idr: Boolean): Boolean = synchronized(lock) {
        val c = codec ?: return true // Keep receiving and maintaining the encrypted session without a Surface.
        if (waitingIdr && !idr) return false
        try {
            val index = c.dequeueInputBuffer(1000)
            if (index < 0) { session.dropped(); return false }
            val buffer = c.getInputBuffer(index)!!
            if (bytes.size > buffer.capacity()) { c.queueInputBuffer(index, 0, 0, pts, 0); session.dropped(); return false }
            buffer.clear(); buffer.put(bytes)
            val time = now()
            submitted[pts] = time
            c.queueInputBuffer(index, 0, bytes.size, pts, 0)
            waitingIdr = false; failures = 0; true
        } catch (_: Exception) { if (++failures >= 3) session.decoderFailed(); false }
    }
    override fun close() {
        closed.set(true)
        synchronized(lock) { codec?.let { runCatching { it.stop() }; runCatching { it.release() } }; codec = null; submitted.clear() }
        if (Thread.currentThread() !== drain) drain.join(2000)
    }
}
