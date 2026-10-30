package me.ayuilos.miffan.data.repository

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import me.rerere.cuaspace.SpaceMediaEvent

/**
 * Decodes a Space's H.264 frames onto a Surface. Without a Surface nothing is decoded; a new Surface, a
 * new codec epoch or a new frame size starts over at the next keyframe ([needKeyframe] asks for one).
 * [rendered] gets each frame's sequence once the codec has drawn it.
 */
internal class SpaceVideoDecoder(
    private val needKeyframe: () -> Unit,
    private val rendered: (Long) -> Unit,
) : Closeable {
    private val lock = Any()
    private val closed = AtomicBoolean()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var epoch = -1L
    private var width = 0
    private var height = 0
    private var waitingKeyframe = true
    private var failures = 0
    private val drain = Thread({
        val info = MediaCodec.BufferInfo()
        while (!closed.get()) {
            synchronized(lock) {
                try {
                    val c = codec
                    var index = c?.dequeueOutputBuffer(info, 0) ?: -1
                    while (c != null && index >= 0) {
                        c.releaseOutputBuffer(index, true)
                        rendered(info.presentationTimeUs)
                        index = c.dequeueOutputBuffer(info, 0)
                    }
                } catch (_: Exception) {
                    reset()
                }
            }
            Thread.sleep(4)
        }
    }, "SpaceVideoOutput").apply { isDaemon = true; start() }

    fun setSurface(target: Surface?) = synchronized(lock) {
        surface = target
        reset()
    }

    /** Queues one frame; frames before the next keyframe are dropped after any reset. */
    fun frame(frame: SpaceMediaEvent.Frame) = synchronized(lock) {
        if (surface == null) return
        if (frame.codecEpoch != epoch || frame.width != width || frame.height != height) {
            reset()
            epoch = frame.codecEpoch
            width = frame.width
            height = frame.height
        }
        if (waitingKeyframe) {
            if (!frame.keyframe) { needKeyframe(); return }
            if (codec == null && !create()) return
            waitingKeyframe = false
        }
        val c = codec ?: return
        try {
            val index = c.dequeueInputBuffer(10_000)
            if (index < 0) return
            val buffer = c.getInputBuffer(index) ?: return
            if (frame.data.size > buffer.capacity()) {
                c.queueInputBuffer(index, 0, 0, frame.sequence, 0)
                reset(); needKeyframe(); return
            }
            buffer.clear()
            buffer.put(frame.data)
            // The sequence rides as the timestamp, so the output side knows which frame it drew.
            c.queueInputBuffer(index, 0, frame.data.size, frame.sequence,
                if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            failures = 0
        } catch (_: Exception) {
            if (++failures >= 3) reset()
            needKeyframe()
        }
    }

    private fun create(): Boolean {
        val target = surface ?: return false
        if (width <= 0 || height <= 0) return false
        return try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (android.os.Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                configure(format, target, null, 0)
                start()
            }
            true
        } catch (_: Exception) {
            codec?.let { runCatching { it.release() } }
            codec = null
            false
        }
    }

    /** Drops the codec; the next keyframe builds a new one. */
    private fun reset() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        waitingKeyframe = true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { reset() }
        if (Thread.currentThread() !== drain) drain.join(2_000)
    }
}
