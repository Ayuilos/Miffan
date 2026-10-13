package me.ayuilos.miffan.data.repository

import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.stream.StreamException
import me.rerere.stream.StreamFailureReason
import me.rerere.stream.StreamSession
import me.rerere.stream.StreamState
import me.rerere.workspace.screen.RemoteScreenState
import me.rerere.workspace.screen.RemoteScreenStats

/** Native streaming was preflighted by open(); start binds it to the viewer's lifetime. */
class StreamDesktopSession internal constructor(
    val delegate: StreamSession,
    private val lifetime: CoroutineScope,
    fingerprint: String,
    private val name: String,
    private val writeClipboard: (String) -> Unit,
    platform: RemoteScreenPlatform = RemoteScreenPlatform.LINUX,
) : RemoteDesktopSession, RemoteSurfaceTarget {
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private var watcher: Job? = null
    private var target: Surface? = null
    private var paused = false
    /** Typed input in order: clipboard pastes and plain key taps share one queue so they never overtake. */
    private sealed interface InputWrite
    private data class ClipboardWrite(val text: String, val paste: Boolean) : InputWrite
    private data class KeyTaps(val text: String) : InputWrite
    private val writes = Channel<InputWrite>(Channel.UNLIMITED)
    private val input = StreamDesktopInput(delegate::mousePosition, delegate::mouseButton, delegate::scroll, delegate::key, platform)
    override val audio: RemoteAudioControl = object : RemoteAudioControl {
        override val enabled = requireNotNull(delegate.audio).enabled
        override fun setEnabled(enabled: Boolean) { delegate.audio?.setEnabled(enabled) }
    }
    override val protocol = RemoteDesktopProtocol.STREAM
    override val surface: RemoteSurfaceTarget get() = this
    override val streamStats get() = delegate.stats
    override val videoSize: StateFlow<RemoteVideoSize?> = MappedStateFlow(delegate.stats) {
        if (it.width > 0 && it.height > 0) RemoteVideoSize(it.width, it.height) else null
    }
    override val state: StateFlow<RemoteScreenState> = MappedStateFlow(delegate.state) {
        when (it) {
            StreamState.Connecting -> RemoteScreenState.Connecting
            StreamState.Streaming -> RemoteScreenState.Connected(name, delegate.stats.value.width, delegate.stats.value.height, 1)
            StreamState.Closed -> RemoteScreenState.Closed(null)
            is StreamState.Failed -> RemoteScreenState.Closed(if (it.reason == StreamFailureReason.CERTIFICATE_MISMATCH)
                RemoteStreamCertificateChangedException(fingerprint, null) else StreamException(it.reason, it.stage, it.code))
        }
    }
    override val clipboard = MutableSharedFlow<String>()
    override val bytesReceived: Long get() = delegate.bytesReceived
    override val certificateSha256: StateFlow<String?> = MutableStateFlow(fingerprint)
    override val rdpStats: StateFlow<me.rerere.rdp.RdpStats>? = null
    override val stats: StateFlow<RemoteScreenStats> = MappedStateFlow(delegate.stats) {
        RemoteScreenStats(fps = it.renderedFps, encodings = setOfNotNull(it.codec?.name))
    }
    override var statsLogger: ((RemoteScreenStats) -> Unit)? = null
    init {
        lifetime.launch {
            for (write in writes) when (write) {
                is KeyTaps -> if (!closed.get()) write.text.forEach { input.key(it.code, true); input.key(it.code, false) }
                // A failed write must never paste the previous clipboard contents.
                is ClipboardWrite -> try { writeClipboard(write.text); if (write.paste && !closed.get()) input.paste() }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { /* SSH failure is surfaced by the lease; keep the input worker alive. */ }
            }
        }
    }
    override fun start(scope: CoroutineScope) {
        check(!closed.get() && started.compareAndSet(false, true)) { "Session already started or closed" }
        watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        lifetime.launch { stats.collect { statsLogger?.invoke(it) } }
    }
    @Synchronized override fun setSurface(surface: Surface?) {
        target = surface
        if (!closed.get()) delegate.setSurface(if (paused) null else target)
    }
    @Synchronized override fun setPaused(paused: Boolean) {
        this.paused = paused
        if (!closed.get()) delegate.setSurface(if (paused) null else target) // setSurface requests IDR on resume.
    }
    override fun setMaxFps(fps: Int) {}
    override fun pointer(x: Int, y: Int, buttons: Int) {
        if (!closed.get()) videoSize.value?.let { input.pointer(x, y, buttons, it) }
    }
    override fun key(keysym: Int, down: Boolean) { if (!closed.get()) input.key(keysym, down) }
    /**
     * Digits and spaces go as real keys: they arrive at once and pass a desktop IME untouched.
     * Everything else is pasted, so a Chinese IME on the computer cannot turn letters into words.
     */
    override fun typeText(text: String): Boolean {
        if (closed.get()) return false
        var start = 0
        while (start < text.length) {
            val keys = text[start].isTypedAsKey()
            var end = start + 1
            while (end < text.length && text[end].isTypedAsKey() == keys) end++
            val part = text.substring(start, end)
            if (!writes.trySend(if (keys) KeyTaps(part) else ClipboardWrite(part, true)).isSuccess) return false
            start = end
        }
        return true
    }
    private fun Char.isTypedAsKey() = this in '0'..'9' || this == ' '
    override fun sendClipboard(text: String) { if (!closed.get()) writes.trySend(ClipboardWrite(text, false)) }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            writes.close(); watcher?.cancel(); delegate.close(); lifetime.cancel()
        }
    }
}
