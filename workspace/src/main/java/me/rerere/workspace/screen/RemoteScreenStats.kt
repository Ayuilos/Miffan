package me.rerere.workspace.screen

import java.util.ArrayDeque

/** Immutable snapshot; averages include empty/cursor-only updates, but [fps] does not. */
data class RemoteScreenStats(
    val fps: Double = 0.0,
    val bytesPerSecond: Double = 0.0,
    val avgUpdateBytes: Double = 0.0,
    val maxUpdateBytes: Long = 0,
    val avgLatencyMillis: Double = 0.0,
    val avgReadMillis: Double = 0.0,
    val avgDecodeMillis: Double = 0.0,
    val avgScaleMillis: Double = 0.0,
    val avgSinkMillis: Double = 0.0,
    val avgChangedPixels: Double = 0.0,
    val emptyUpdates: Int = 0,
    val encodings: Set<String> = emptySet(),
    val scale: Int = 1,
    val pixelFormat: RfbPixelFormat = RfbPixelFormat.RGB888,
    val outstandingRequests: Int = 0,
) {
    companion object { const val WINDOW_MILLIS = 2_000L }
}

/** Reader-owned rolling samples: small metadata only, no framebuffer copies. */
internal class ScreenStatsWindow(private val started: Long, initialBytes: Long) {
    internal data class Update(
        val at: Long, val bytes: Long, val latency: Long?, val read: Long, val decode: Long,
        val scale: Long, val sink: Long, val pixels: Long, val displayed: Boolean, val encodings: Set<Int>,
    )
    private data class Traffic(val at: Long, val bytes: Long)
    private val updates = ArrayDeque<Update>()
    private val traffic = ArrayDeque<Traffic>().apply { add(Traffic(started, initialBytes)) }

    fun add(update: Update) { updates.addLast(update) }

    fun snapshot(now: Long, bytes: Long, scale: Int, format: RfbPixelFormat, outstanding: Int): RemoteScreenStats {
        val cutoff = now - RemoteScreenStats.WINDOW_MILLIS * 1_000_000
        while (updates.isNotEmpty() && updates.first.at <= cutoff) updates.removeFirst()
        // Keep the sample just before the cutoff so idle traffic also ages out.
        traffic.addLast(Traffic(now, bytes))
        while (traffic.size > 1 && traffic.elementAt(1).at <= cutoff) traffic.removeFirst()
        val seconds = (now - maxOf(started, cutoff)).coerceAtLeast(1) / 1e9
        val baseline = traffic.first
        val trafficSeconds = (now - baseline.at).coerceAtLeast(1) / 1e9
        val count = updates.size.coerceAtLeast(1)
        fun average(value: (Update) -> Long) = updates.sumOf(value).toDouble() / count
        val matched = updates.mapNotNull { it.latency }
        return RemoteScreenStats(
            fps = updates.count { it.displayed } / seconds,
            bytesPerSecond = (bytes - baseline.bytes) / trafficSeconds,
            avgUpdateBytes = average { it.bytes }, maxUpdateBytes = updates.maxOfOrNull { it.bytes } ?: 0,
            avgLatencyMillis = if (matched.isEmpty()) 0.0 else matched.average() / 1e6,
            avgReadMillis = average { it.read } / 1e6, avgDecodeMillis = average { it.decode } / 1e6,
            avgScaleMillis = average { it.scale } / 1e6, avgSinkMillis = average { it.sink } / 1e6,
            avgChangedPixels = average { it.pixels }, emptyUpdates = updates.count { !it.displayed },
            encodings = updates.flatMap { it.encodings }.mapTo(linkedSetOf()) { encodingName(it) }.toSet(),
            scale = scale, pixelFormat = format, outstandingRequests = outstanding,
        )
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        RfbClient.ENCODING_RAW -> "Raw"
        RfbClient.ENCODING_COPY_RECT -> "CopyRect"
        RfbClient.ENCODING_TIGHT -> "Tight"
        RfbClient.ENCODING_TIGHT_JPEG -> "TightJPEG"
        RfbClient.ENCODING_ZRLE -> "ZRLE"
        RfbClient.ENCODING_CURSOR -> "Cursor"
        RfbClient.ENCODING_DESKTOP_SIZE -> "DesktopSize"
        else -> encoding.toString()
    }
}

/** FIFO timestamps include the update being decoded until [complete] is called. */
internal class ScreenRequestPacer(private val depth: Int) {
    private val requests = ArrayDeque<Long>()
    private var lastRequest: Long? = null
    private var initial = true
    private var resizeAt: Long? = null
    val outstanding: Int get() = requests.size

    fun latency(received: Long): Long? = requests.peekFirst()?.let { (received - it).coerceAtLeast(0) }
    fun complete() { if (requests.isNotEmpty()) requests.removeFirst() }
    fun resized(now: Long) { resizeAt = now }

    fun waitNanos(now: Long, maxFps: Int, paused: Boolean, resume: Boolean): Long {
        if (paused || outstanding >= depth) return Long.MAX_VALUE
        val resize = resizeAt
        val drain = if (resize != null && outstanding > 0)
            (resize + RemoteScreenStats.WINDOW_MILLIS * 1_000_000 - now).coerceAtLeast(0) else 0
        val interval = if (resume) 0 else lastRequest?.let {
            (it + 1_000_000_000L / maxFps.coerceIn(1, 60) - now).coerceAtLeast(0)
        } ?: 0
        return maxOf(drain, interval)
    }

    /** Null means wait. During header processing, only depth 2 may overlap the current update. */
    fun request(now: Long, maxFps: Int, paused: Boolean, atHeader: Boolean = false, resume: Boolean = false): Boolean? {
        if (paused || outstanding >= depth) return null
        if (atHeader && depth == 1) return null
        val resize = resizeAt
        if (resize != null) {
            // Merged requests have no individual acknowledgement. After a bounded drain, use
            // a free slot for a full refresh without forgetting the remaining FIFO timestamp.
            if (outstanding > 0 && now - resize < RemoteScreenStats.WINDOW_MILLIS * 1_000_000) return null
        }
        val interval = 1_000_000_000L / maxFps.coerceIn(1, 60)
        if (!resume && lastRequest?.let { now - it < interval } == true) return null
        val incremental = !initial && resize == null
        requests.addLast(now)
        lastRequest = now
        initial = false
        resizeAt = null
        return incremental
    }
}
