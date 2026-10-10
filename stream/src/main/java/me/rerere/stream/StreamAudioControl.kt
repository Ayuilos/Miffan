package me.rerere.stream

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Disabled by default. Owns playback only; no capture or microphone path. */
class StreamAudioControl internal constructor(context: Context,
    private val stats: ((StreamStats) -> StreamStats) -> Unit) : Closeable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val lock = Any()
    private val mutableEnabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    private var closed = false
    private var generation = 0L
    private var focus: AudioFocusRequest? = null
    private var config: StreamOpusConfig? = null
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private data class Packet(val bytes: ByteArray, val time: Long)
    private val packets = ArrayDeque<Packet>()
    private var written = 0L
    private var lastHead = 0L
    private val pcmPending = ArrayDeque<Pair<Long, Long>>()

    fun setEnabled(enabled: Boolean) = synchronized(lock) {
        if (closed || mutableEnabled.value == enabled) return@synchronized
        if (!enabled) { disable(); return@synchronized }
        val token = ++generation
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                synchronized(lock) {
                    // Ignore a callback belonging to an abandoned playback generation.
                    if (token == generation && change != AudioManager.AUDIOFOCUS_GAIN) disable()
                }
            }, Handler(Looper.getMainLooper())).build()
        focus = request
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            disable(); stats { it.copy(audioError = "Audio focus denied") }; return@synchronized
        }
        mutableEnabled.value = true
        stats { it.copy(audioError = null) }
        if (worker == null) worker = Thread(::pump, "StreamOpusOutput").apply { isDaemon = true; start() }
    }

    internal fun configure(value: StreamOpusConfig) = synchronized(lock) {
        releasePlayback(); config = value
        try { value.header() } catch (_: Exception) { fail("Unsupported negotiated Opus configuration") }
    }

    internal fun packet(bytes: ByteArray) = synchronized(lock) {
        if (!mutableEnabled.value || closed) return@synchronized
        val now = System.nanoTime()
        // Discard the older queued packets on network/codec stalls; retain the newest packet.
        if (packets.size >= 50 || packets.firstOrNull()?.let { audioBacklogExpired(it.time, now) } == true) {
            packets.clear(); drop()
        }
        packets.addLast(Packet(bytes, now))
    }

    private fun createPlayback(c: StreamOpusConfig) {
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec = decoder
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, c.rate, c.channels).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(c.header()))
            setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).apply { flip() })
            setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000).apply { flip() })
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        decoder.configure(format, null, null, 0); decoder.start()
        val minimum = AudioTrack.getMinBufferSize(c.rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val output = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder()
            .setSampleRate(c.rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, c.rate * 4 / 50))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
        track = output
        check(output.state == AudioTrack.STATE_INITIALIZED)
        output.play(); written = 0; lastHead = 0
        stats { it.copy(audioDecoderName = decoder.name, audioTrackActive = true) }
    }

    private fun pump() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            synchronized(lock) {
                if (closed) return
                if (mutableEnabled.value && config != null) {
                    try {
                        val c = config!!
                        if (codec == null) createPlayback(c)
                        val decoder = codec!!; val output = track!!
                        val head = output.playbackHeadPosition.toLong() and 0xffffffffL
                        val advanced = (head - lastHead) and 0xffffffffL
                        lastHead = head
                        if (advanced > 0) stats { it.copy(audioPlayedFrames = it.audioPlayedFrames + advanced) }
                        while (pcmPending.firstOrNull()?.let { it.first <= head } == true) pcmPending.removeFirst()
                        if (written - head > c.rate / 10 ||
                            pcmPending.firstOrNull()?.let { audioBacklogExpired(it.second, System.nanoTime()) } == true) {
                            output.pause(); output.flush(); output.play(); written = 0; lastHead = 0; pcmPending.clear(); drop()
                        }
                        // Bound work per pass, so disabling/focus-loss never waits on a blocking write.
                        repeat(8) {
                            val index = decoder.dequeueOutputBuffer(info, 0)
                            if (index >= 0) {
                                try {
                                    if (info.size > 0) {
                                        if (audioBacklogExpired(info.presentationTimeUs * 1000, System.nanoTime())) drop()
                                        else {
                                            val buffer = requireNotNull(decoder.getOutputBuffer(index))
                                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                            val n = output.write(buffer, info.size, AudioTrack.WRITE_NON_BLOCKING)
                                            check(n >= 0) { "AudioTrack write failed" }
                                            written += n / 4
                                            if (n > 0) pcmPending.addLast(written to info.presentationTimeUs * 1000)
                                            stats { it.copy(audioWrittenFrames = it.audioWrittenFrames + n / 4) }
                                            if (n < info.size) drop() // Unwritten PCM is discarded, never retried late.
                                        }
                                    }
                                } finally { decoder.releaseOutputBuffer(index, false) }
                            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                val f = decoder.outputFormat
                                check(f.getInteger(MediaFormat.KEY_SAMPLE_RATE) == c.rate &&
                                    f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == c.channels)
                                check(!f.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                            }
                        }
                        repeat(4) {
                            val packet = packets.firstOrNull() ?: return@repeat
                            if (audioBacklogExpired(packet.time, System.nanoTime())) { packets.removeFirst(); drop() }
                            else {
                                val index = decoder.dequeueInputBuffer(0)
                                if (index >= 0) {
                                    val buffer = requireNotNull(decoder.getInputBuffer(index))
                                    buffer.clear(); check(packet.bytes.size <= buffer.remaining())
                                    buffer.put(packet.bytes)
                                    decoder.queueInputBuffer(index, 0, packet.bytes.size, packet.time / 1000, 0)
                                    packets.removeFirst()
                                }
                            }
                        }
                    } catch (_: Exception) { fail("Opus playback failed") }
                }
            }
            Thread.sleep(if (enabled.value) 2 else 20)
        }
    }

    private fun drop() { stats { it.copy(audioBacklogDrops = it.audioBacklogDrops + 1) } }
    private fun fail(reason: String) { disable(); stats { it.copy(audioError = reason) } }
    private fun releasePlayback() {
        packets.clear()
        track?.let { runCatching { it.pause() }; runCatching { it.flush() }; runCatching { it.release() } }; track = null
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }; codec = null
        written = 0; lastHead = 0; pcmPending.clear()
        stats { it.copy(audioTrackActive = false) }
    }
    private fun disable() {
        mutableEnabled.value = false; ++generation
        releasePlayback()
        focus?.let { manager.abandonAudioFocusRequest(it) }; focus = null
    }
    override fun close() {
        val thread = synchronized(lock) {
            if (closed) return
            closed = true; disable(); worker
        }
        if (Thread.currentThread() !== thread) thread?.join(2000)
    }
}
