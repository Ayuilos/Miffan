package me.ayuilos.miffan.data.repository

import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import me.rerere.rdp.RdpSession
import me.rerere.rdp.RdpStats
import me.rerere.workspace.screen.RemoteScreenSession
import me.rerere.workspace.screen.RemoteScreenState
import me.rerere.workspace.screen.RemoteScreenStats

enum class RemoteDesktopProtocol { VNC, RDP, STREAM }

data class RemoteVideoSize(val width: Int, val height: Int)

/** Frames go to a Surface instead of the frame sink. The UI owns its Surface. */
interface RemoteSurfaceTarget {
    val videoSize: StateFlow<RemoteVideoSize?>
    fun setSurface(surface: android.view.Surface?)
}

interface RemoteAudioControl {
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
}

interface RemoteDesktopSession : Closeable {
    val audio: RemoteAudioControl? get() = null
    val surface: RemoteSurfaceTarget? get() = null
    val streamStats: StateFlow<me.rerere.stream.StreamStats>? get() = null
    val protocol: RemoteDesktopProtocol
    val state: StateFlow<RemoteScreenState>
    val clipboard: SharedFlow<String>
    val bytesReceived: Long
    val certificateSha256: StateFlow<String?>
    val rdpStats: StateFlow<RdpStats>?
    /** Compatibility for existing VNC consumers; RDP only maps FPS and encoding here. */
    val stats: StateFlow<RemoteScreenStats>
    var statsLogger: ((RemoteScreenStats) -> Unit)?
    fun start(scope: CoroutineScope)
    fun setPaused(paused: Boolean)
    fun setMaxFps(fps: Int)
    fun pointer(x: Int, y: Int, buttons: Int)
    fun key(keysym: Int, down: Boolean)
    fun tapKey(keysym: Int) { key(keysym, true); key(keysym, false) }
    fun typeText(text: String): Boolean
    fun sendClipboard(text: String)
}

class VncDesktopSession(val delegate: RemoteScreenSession) : RemoteDesktopSession {
    override val protocol = RemoteDesktopProtocol.VNC
    override val state get() = delegate.state
    override val clipboard get() = delegate.clipboard
    override val bytesReceived get() = delegate.bytesReceived
    override val certificateSha256: StateFlow<String?> = MutableStateFlow(null)
    override val rdpStats: StateFlow<RdpStats>? = null
    override val stats get() = delegate.stats
    override var statsLogger: ((RemoteScreenStats) -> Unit)?
        get() = delegate.statsLogger
        set(value) { delegate.statsLogger = value }
    override fun start(scope: CoroutineScope) = delegate.start(scope)
    override fun setPaused(paused: Boolean) = delegate.setPaused(paused)
    override fun setMaxFps(fps: Int) = delegate.setMaxFps(fps)
    override fun pointer(x: Int, y: Int, buttons: Int) = delegate.pointer(x, y, buttons)
    override fun key(keysym: Int, down: Boolean) = delegate.key(keysym, down)
    override fun typeText(text: String) = delegate.typeText(text)
    override fun sendClipboard(text: String) = delegate.sendClipboard(text)
    override fun close() = delegate.close()
}

class RdpDesktopSession(
    val delegate: RdpSession, private val expectedPin: String?,
    private val onCertificateVerified: (suspend (String) -> Unit)? = null,
) : RemoteDesktopSession {
    private var certificateObserver: Job? = null
    override val protocol = RemoteDesktopProtocol.RDP
    override val state: StateFlow<RemoteScreenState> = MappedStateFlow(delegate.state) { value ->
        if (value is RemoteScreenState.Closed && value.error is SecurityException && expectedPin != null)
            RemoteScreenState.Closed(RemoteRdpCertificateChangedException(expectedPin, delegate.certificateSha256.value))
        else value
    }
    override val clipboard get() = delegate.clipboard
    override val bytesReceived get() = delegate.bytesReceived
    override val certificateSha256 get() = delegate.certificateSha256
    override val rdpStats get() = delegate.stats
    override val stats: StateFlow<RemoteScreenStats> = MappedStateFlow(delegate.stats) {
        RemoteScreenStats(fps = it.framesPerSecond, encodings = setOf(it.encoding))
    }
    override var statsLogger: ((RemoteScreenStats) -> Unit)? = null
    override fun start(scope: CoroutineScope) {
        check(certificateObserver == null) { "Session already started" }
        val observer = if (expectedPin != null && onCertificateVerified != null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                persistVerifiedRdpCertificate(state, certificateSha256, expectedPin, onCertificateVerified)
            }
        } else null
        try {
            delegate.start(scope)
            certificateObserver = observer
        } catch (error: Throwable) { observer?.cancel(); throw error }
    }
    override fun setPaused(paused: Boolean) = delegate.setPaused(paused)
    override fun setMaxFps(fps: Int) {} // RDP is server-paced.
    override fun pointer(x: Int, y: Int, buttons: Int) = delegate.pointer(x, y, buttons)
    override fun key(keysym: Int, down: Boolean) = delegate.key(keysym, down)
    override fun typeText(text: String) = delegate.typeText(text)
    override fun sendClipboard(text: String) = delegate.sendClipboard(text)
    override fun close() { certificateObserver?.cancel(); delegate.close() }
}

/** Lazy mapping retains StateFlow's current value without another lifetime or background job. */
internal class MappedStateFlow<T, R>(private val source: StateFlow<T>, private val transform: (T) -> R) : StateFlow<R> {
    override val value get() = transform(source.value)
    override val replayCache get() = listOf(value)
    @OptIn(InternalCoroutinesApi::class)
    override suspend fun collect(collector: FlowCollector<R>): Nothing = source.collect(object : FlowCollector<T> {
        override suspend fun emit(value: T) = collector.emit(transform(value))
    })
}
