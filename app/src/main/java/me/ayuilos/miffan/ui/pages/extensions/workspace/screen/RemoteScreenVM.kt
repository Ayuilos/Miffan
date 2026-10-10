package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import me.ayuilos.miffan.data.ai.computer.RemoteComputerControl
import me.ayuilos.miffan.data.ai.computer.RemoteController
import me.ayuilos.miffan.data.db.entity.RemoteScreenEndpoint
import me.ayuilos.miffan.data.repository.RemoteScreenConnection
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import me.ayuilos.miffan.data.repository.RemoteScreenRepository
import me.ayuilos.miffan.data.repository.RemoteDesktopProtocol
import me.ayuilos.miffan.data.repository.RemoteScreenQuality
import me.ayuilos.miffan.data.repository.RemoteStreamRequest
import me.ayuilos.miffan.data.repository.audio
import me.ayuilos.miffan.data.repository.open
import me.ayuilos.miffan.data.repository.streamAddress
import me.ayuilos.miffan.data.repository.streamRequested
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.data.repository.RemoteStreamCertificateChangedException
import me.ayuilos.miffan.data.repository.RemoteStreamFallback
import me.ayuilos.miffan.data.repository.RemoteSurfaceTarget
import me.rerere.stream.StreamStats
import me.rerere.workspace.screen.Framebuffer
import me.rerere.workspace.screen.RemoteScreenFrameSink
import me.rerere.workspace.screen.RemoteScreenOptions
import me.rerere.workspace.screen.RemoteScreenStats
import me.rerere.rdp.RdpBitmapFrameSink
import me.rerere.rdp.RdpStats
import me.rerere.workspace.screen.RemoteScreenState
import me.rerere.workspace.screen.RfbJpegDecoder
import me.rerere.workspace.screen.RfbCursor
import me.rerere.workspace.screen.RfbKeys
import me.rerere.workspace.screen.RfbRect

/** What the screen page shows above the canvas. */
sealed interface RemoteScreenUiState {
    data object Connecting : RemoteScreenUiState

    /** [width]/[height] are bitmap (or stream video) pixels; input coordinates use the same space. */
    data class Connected(val name: String, val width: Int, val height: Int) : RemoteScreenUiState

    data class Failed(val error: Throwable) : RemoteScreenUiState
    data object Closed : RemoteScreenUiState
}

enum class RemoteMouseButton(internal val mask: Int) { LEFT(1), MIDDLE(2), RIGHT(4) }

/**
 * Modifiers held while a key is pressed. [COMMAND] is the platform shortcut key (Command on
 * macOS, Ctrl elsewhere); [SUPER] is always the Super/Windows/Command key itself.
 */
enum class RemoteModifier { SHIFT, CONTROL, ALT, COMMAND, SUPER }

/** One-shot results the page reports as a toast or snackbar. */
sealed interface RemoteScreenNotice {
    /** Text had characters VNC cannot type and this platform has no clipboard bridge yet. */
    data object TextNotTypable : RemoteScreenNotice
    data class ClipboardFailed(val error: Throwable) : RemoteScreenNotice
    /** The remote side copied text (Latin-1 only through standard RFB). */
    data class RemoteClipboard(val text: String) : RemoteScreenNotice
    /** High-performance mode was on but this connection uses VNC or RDP instead. */
    data class StreamFallback(val fallback: RemoteStreamFallback) : RemoteScreenNotice
    /** The first stream on mobile data: what the chosen picture level may cost per hour. */
    data class StreamOnMeteredNetwork(val quality: RemoteScreenQuality) : RemoteScreenNotice
}

/** How the current connection reaches the desktop, for the page's status line and details. */
data class RemoteConnectionInfo(
    val protocol: RemoteDesktopProtocol,
    /** The computer's high-performance switch is on and this connection did not skip it. */
    val streamRequested: Boolean,
    /** The UDP address a stream uses. */
    val streamAddress: String?,
    /** Why a requested stream was not used. */
    val fallback: RemoteStreamFallback?,
    /** The user chose the plain connection for this visit from the menu. */
    val streamSkipped: Boolean,
)

data class RemoteScreenArgs(val workspaceId: String)

/** What the page draws as the remote pointer. */
sealed interface RemoteCursor {
    /** The server never sent a shape; draw a local arrow. */
    data object Unknown : RemoteCursor

    /** The server hides its pointer (for example while typing). */
    data object Hidden : RemoteCursor

    /**
     * The server's pointer image. Draw [bitmap] with its hotspot at the pointer position, at
     * `size / scale` dp so Retina (scale 2) cursors keep the same on-screen size.
     */
    class Shape(val bitmap: Bitmap, val hotspotX: Int, val hotspotY: Int, val scale: Int) : RemoteCursor
}

/**
 * Owns one VNC viewer for the page's lifetime: survives rotation, closes when the page leaves the
 * back stack. Frames land in [bitmap] on the session thread; [frameVersion] changes after each
 * frame so the canvas redraws. All coordinates in this API are bitmap pixels.
 */
class RemoteScreenVM(
    private val args: RemoteScreenArgs,
    private val repository: RemoteScreenRepository,
    private val workspaces: WorkspaceRepository,
    private val control: RemoteComputerControl,
    context: Context,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<RemoteScreenUiState>(RemoteScreenUiState.Connecting)
    val state: StateFlow<RemoteScreenUiState> = _state.asStateFlow()

    private val _bitmap = MutableStateFlow<Bitmap?>(null)
    val bitmap: StateFlow<Bitmap?> = _bitmap.asStateFlow()

    private val _frameVersion = MutableStateFlow(0L)
    val frameVersion: StateFlow<Long> = _frameVersion.asStateFlow()

    private val _cursor = MutableStateFlow<RemoteCursor>(RemoteCursor.Unknown)
    /** The remote pointer shape, when the server sends one. */
    val cursor: StateFlow<RemoteCursor> = _cursor.asStateFlow()

    private val _bytesReceived = MutableStateFlow(0L)
    /** Bytes received on the current connection, updated with each frame. */
    val bytesReceived: StateFlow<Long> = _bytesReceived.asStateFlow()

    private val _notices = MutableSharedFlow<RemoteScreenNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<RemoteScreenNotice> = _notices.asSharedFlow()

    /** True on metered networks; the page may show a data-usage hint. */
    val metered: Boolean = connectivity()?.isActiveNetworkMetered ?: true

    private val hints = appContext.getSharedPreferences(SCREEN_HINTS, Context.MODE_PRIVATE)

    /** Remembered separately for mobile data and other networks; mobile data starts at [RemoteScreenQuality.SAVER]. */
    private val qualityKey = if (metered) "quality_metered" else "quality_unmetered"
    private val _quality = MutableStateFlow(
        hints.getString(qualityKey, null)?.let { saved -> RemoteScreenQuality.entries.firstOrNull { it.name == saved } }
            ?: if (metered) RemoteScreenQuality.SAVER else RemoteScreenQuality.BALANCED,
    )
    /** The picture level; VNC maps it to a frame cap, a stream to resolution and bitrate, RDP ignores it. */
    val quality: StateFlow<RemoteScreenQuality> = _quality.asStateFlow()

    private val _connectionInfo = MutableStateFlow<RemoteConnectionInfo?>(null)
    val connectionInfo: StateFlow<RemoteConnectionInfo?> = _connectionInfo.asStateFlow()

    private val _audio = MutableStateFlow<Boolean?>(null)
    /** Whether the stream's sound plays; null when the connection carries no sound. */
    val audio: StateFlow<Boolean?> = _audio.asStateFlow()

    /** The plain connection was chosen from the menu; kept until the user asks for the stream again. */
    private var skipStream = false

    private val _stats = MutableStateFlow<RemoteScreenStats?>(null)
    /** Rolling session statistics for the performance overlay; null until connected. */
    val stats: StateFlow<RemoteScreenStats?> = _stats.asStateFlow()

    private val _canUseAutomatic = MutableStateFlow(false)
    /** The last failure was a fixed endpoint nothing listens on; automatic connection may fix it. */
    val canUseAutomatic: StateFlow<Boolean> = _canUseAutomatic.asStateFlow()

    private val _rdpStats = MutableStateFlow<RdpStats?>(null)
    /** RDP's own numbers (codec, decoder); null on VNC connections. */
    val rdpStats: StateFlow<RdpStats?> = _rdpStats.asStateFlow()

    private val _video = MutableStateFlow<RemoteSurfaceTarget?>(null)
    /**
     * Set while a high-performance (Sunshine) stream is connected: it decodes into a Surface the
     * page provides, so [bitmap] stays null and input coordinates are video pixels.
     */
    val video: StateFlow<RemoteSurfaceTarget?> = _video.asStateFlow()

    private val _streamStats = MutableStateFlow<StreamStats?>(null)
    /** The stream's own numbers; null unless [video] is set. */
    val streamStats: StateFlow<StreamStats?> = _streamStats.asStateFlow()

    private val streamSetup = RemoteStreamSetupController(repository, viewModelScope)
    /** Sunshine on this computer, read once to suggest high-performance mode; see [streamSuggestion]. */
    val streamSetups = streamSetup.states

    private val _streamSuggestion = MutableStateFlow<StreamSuggestion?>(null)
    /**
     * Set when a plain VNC/RDP connection runs on a Linux computer whose Sunshine could make it
     * smoother; the page shows a dismissible suggestion while the check says so.
     */
    val streamSuggestion: StateFlow<StreamSuggestion?> = _streamSuggestion.asStateFlow()
    private var streamChecked = false

    /** Frames the canvas actually drew; the overlay compares it with decoded updates. */
    val framesDrawn = java.util.concurrent.atomic.AtomicLong()

    private val _hostId = MutableStateFlow<String?>(null)

    /**
     * Who drives this machine's desktop. Any input from this page makes the user the controller,
     * which stops the partner's computer actions until [handBackToPartner] or the page closes.
     */
    val controller: StateFlow<RemoteController> = combine(_hostId, control.states) { hostId, states ->
        hostId?.let { states[it] } ?: RemoteController.IDLE
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RemoteController.IDLE)

    fun handBackToPartner() {
        _hostId.value?.let(control::userHandsBack)
    }

    /** The user touched the screen: take the desktop now, before a tap or drag sends any input. */
    fun takeControl() = userInput()

    private fun userInput() {
        _hostId.value?.let { if (control.controller(it) != RemoteController.USER) control.userTakesOver(it) }
    }

    private val _platform = MutableStateFlow(RemoteScreenPlatform.UNKNOWN)
    /** Detected remote platform, for key labels such as ⌘ versus Super. */
    val platform: StateFlow<RemoteScreenPlatform> = _platform.asStateFlow()

    /**
     * Keyboard work runs strictly in order: a paste through the remote clipboard is async, and a
     * Return typed right after it must not overtake it.
     */
    private val keyboard = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    @Volatile private var connection: RemoteScreenConnection? = null
    @Volatile private var scale = 1
    @Volatile private var serverWidth = 0
    @Volatile private var serverHeight = 0
    private var buttons = 0
    private var pointerX = 0
    private var pointerY = 0
    private var visible = true
    private var connectJob: Job? = null

    /**
     * RDP writes dirty rectangles straight into [bitmap] from native code. Every callback runs on
     * the session worker and the bitmap is only replaced in onSize on that same thread, never
     * recycled, so a lease needs no lock beyond matching the current size.
     */
    private val sink = object : RdpBitmapFrameSink {
        override fun acquireBitmap(width: Int, height: Int): Bitmap? =
            _bitmap.value?.takeIf { it.width == width && it.height == height && it.isMutable && !it.isRecycled }

        override fun releaseBitmap(bitmap: Bitmap) = Unit

        override fun onSize(width: Int, height: Int, scale: Int) {
            this@RemoteScreenVM.scale = scale
            _bitmap.value = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        override fun onPixels(rect: RfbRect, pixels: IntArray) {
            _bitmap.value?.setPixels(pixels, 0, rect.width, rect.x, rect.y, rect.width, rect.height)
        }

        override fun onCursor(cursor: RfbCursor) {
            _cursor.value = if (cursor.width == 0 || cursor.height == 0 || cursor.pixels.none { it != 0 }) {
                RemoteCursor.Hidden
            } else RemoteCursor.Shape(
                Bitmap.createBitmap(cursor.pixels, cursor.width, cursor.height, Bitmap.Config.ARGB_8888),
                cursor.hotspotX, cursor.hotspotY, this@RemoteScreenVM.scale,
            )
        }

        override fun onFrameComplete() {
            _bytesReceived.value = connection?.session?.bytesReceived ?: 0L
            _frameVersion.value++
        }
    }

    private val jpeg = RfbJpegDecoder { bytes, length, fb, x, y, w, h -> decodeJpeg(bytes, length, fb, x, y, w, h) }

    init {
        viewModelScope.launch { for (job in keyboard) job() }
        connect()
    }

    fun reconnect() {
        close()
        connect()
    }

    /** Stop requesting frames while the page is not visible; bandwidth drops to zero. */
    fun setVisible(value: Boolean) {
        visible = value
        connection?.session?.setPaused(!value)
    }

    /**
     * The user confirmed that this computer's RDP or Sunshine certificate changed for a reason
     * they know; pin the new one and connect again. Never called without that confirmation.
     */
    fun trustCertificate(sha256: String) {
        val hostId = _hostId.value ?: return
        val stream = (_state.value as? RemoteScreenUiState.Failed)?.error is RemoteStreamCertificateChangedException
        viewModelScope.launch {
            try {
                if (stream) repository.pinStreamCertificate(hostId, sha256) else repository.pinRdpCertificate(hostId, sha256)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.value = RemoteScreenUiState.Failed(error)
                return@launch
            }
            reconnect()
        }
    }

    /**
     * Switches this computer from a fixed endpoint to automatic connection, keeping its
     * authentication, then connects again. Offered only after [canUseAutomatic].
     */
    fun useAutomaticConnection() {
        _canUseAutomatic.value = false
        viewModelScope.launch {
            try {
                val hostId = repository.hostIdOf(args.workspaceId) ?: return@launch
                val config = repository.getConfig(hostId) ?: return@launch
                repository.updateConfig(hostId, enabled = true, endpoint = RemoteScreenEndpoint.Helper,
                    auth = config.auth, username = config.username, password = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.value = RemoteScreenUiState.Failed(error)
                return@launch
            }
            reconnect()
        }
    }

    /** Offers automatic connection when a fixed endpoint turned out to have nothing listening. */
    private fun checkAutomaticFix(error: Throwable) {
        if (!isClosedScreenPort(error)) return
        viewModelScope.launch {
            val hostId = repository.hostIdOf(args.workspaceId) ?: return@launch
            val endpoint = runCatching { repository.getConfig(hostId)?.endpoint }.getOrNull() ?: return@launch
            _canUseAutomatic.value = endpoint != RemoteScreenEndpoint.Helper
        }
    }

    /** Reads Sunshine once per page, only for plain connections the user has not declined to improve. */
    private fun suggestStream(opened: RemoteScreenConnection) {
        if (streamChecked || opened.session.surface != null || opened.streamFallback != null) return
        if (opened.platform != RemoteScreenPlatform.LINUX || hints.getBoolean(streamDismissedKey(opened.hostId), false)) return
        streamChecked = true
        viewModelScope.launch {
            val host = runCatching { workspaces.getHostById(opened.hostId) }.getOrNull() ?: return@launch
            _streamSuggestion.value = StreamSuggestion(host.id, host.connectionRevision)
            streamSetup.check(host.id, host.connectionRevision)
        }
    }

    fun dismissStreamSuggestion() {
        val suggestion = _streamSuggestion.value ?: return
        hints.edit().putBoolean(streamDismissedKey(suggestion.hostId), true).apply()
        _streamSuggestion.value = null
    }

    fun pairStream() = _streamSuggestion.value?.let { streamSetup.pair(it.hostId, it.revision) }
    fun cancelStreamPairing() = streamSetup.cancelPairing()
    /** Called only from the confirmation that shows exactly what changes on the computer. */
    fun enforceStreamEncryption() = _streamSuggestion.value?.let { streamSetup.enforceEncryption(it.hostId, it.revision) }
    fun enableStream() = _streamSuggestion.value?.let { streamSetup.setEnabled(it.hostId, true) }

    /** The suggestion's flow ended; once the mode is on, reconnect to use it. */
    fun finishStreamSuggestion(enabled: Boolean) {
        if (!enabled) return
        _streamSuggestion.value = null
        reconnect()
    }

    /** Applies at once on VNC; a stream reconnects with the new resolution and bitrate. */
    fun setQuality(quality: RemoteScreenQuality) {
        if (quality == _quality.value) return
        _quality.value = quality
        hints.edit().putString(qualityKey, quality.name).apply()
        val session = connection?.session ?: return
        when (session.protocol) {
            RemoteDesktopProtocol.VNC -> session.setMaxFps(vncFps(quality))
            RemoteDesktopProtocol.STREAM -> reconnect()
            RemoteDesktopProtocol.RDP -> Unit
        }
    }

    /** Reconnects with or without high-performance mode for this visit only; the computer's setting stays. */
    fun reconnectWithStream(stream: Boolean) {
        skipStream = !stream
        reconnect()
    }

    fun setAudio(enabled: Boolean) {
        hints.edit().putBoolean(AUDIO_KEY, enabled).apply()
        connection?.session?.audio?.setEnabled(enabled)
    }

    fun movePointer(x: Float, y: Float) {
        userInput()
        updatePointer(x, y)
        send()
    }

    fun press(button: RemoteMouseButton, down: Boolean) {
        userInput()
        buttons = if (down) buttons or button.mask else buttons and button.mask.inv()
        send()
    }

    fun click(x: Float, y: Float, button: RemoteMouseButton = RemoteMouseButton.LEFT, count: Int = 1) {
        updatePointer(x, y)
        // Move first, as a real mouse does: toolkits such as GTK on Wayland ignore a press that
        // arrives in the same event that brings the pointer into the widget.
        send()
        repeat(count) {
            press(button, true)
            press(button, false)
        }
    }

    /** Positive [steps] scrolls content down (wheel towards the user). */
    fun scroll(steps: Int, horizontal: Boolean = false) {
        userInput()
        val mask = when {
            horizontal && steps > 0 -> 64
            horizontal -> 32
            steps > 0 -> 16
            else -> 8
        }
        repeat(kotlin.math.abs(steps)) {
            connection?.session?.pointer(pointerX, pointerY, buttons or mask)
            send()
        }
    }

    fun key(keysym: Int, modifiers: Set<RemoteModifier> = emptySet()) {
        userInput()
        keyboard.trySend { sendKey(keysym, modifiers) }
    }

    /**
     * Types text from the phone keyboard. Latin text goes as key events; anything else is put on
     * the remote clipboard over SSH and pasted with the platform shortcut.
     */
    fun typeText(text: String) {
        userInput()
        keyboard.trySend {
            val current = connection ?: return@trySend
            if (current.session.typeText(text)) return@trySend
            try {
                if (repository.setRemoteClipboard(args.workspaceId, current.platform, text)) {
                    sendKey('v'.code, setOf(RemoteModifier.COMMAND))
                } else _notices.tryEmit(RemoteScreenNotice.TextNotTypable)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _notices.tryEmit(RemoteScreenNotice.ClipboardFailed(error))
            }
        }
    }

    private fun sendKey(keysym: Int, modifiers: Set<RemoteModifier>) {
        val session = connection?.session ?: return
        val held = modifiers.map(::modifierKeysym)
        held.forEach { session.key(it, true) }
        session.tapKey(keysym)
        held.asReversed().forEach { session.key(it, false) }
    }

    private fun connect() {
        _state.value = RemoteScreenUiState.Connecting
        _canUseAutomatic.value = false
        connectJob = viewModelScope.launch {
            try {
                val quality = _quality.value
                val opened = repository.open(
                    args.workspaceId, sink, jpeg,
                    RemoteScreenOptions(
                        jpegQuality = if (quality == RemoteScreenQuality.SAVER) 4 else 6,
                        maxFps = vncFps(quality),
                        lowColor = quality == RemoteScreenQuality.SAVER,
                    ),
                    RemoteStreamRequest(quality, skip = skipStream),
                )
                connection = opened
                _hostId.value = opened.hostId
                _platform.value = opened.platform
                opened.streamFallback?.let { _notices.tryEmit(RemoteScreenNotice.StreamFallback(it)) }
                _connectionInfo.value = RemoteConnectionInfo(opened.session.protocol, opened.streamRequested,
                    opened.streamAddress, opened.streamFallback, skipStream)
                opened.session.audio?.let { audio ->
                    if (hints.getBoolean(AUDIO_KEY, false)) audio.setEnabled(true)
                    launch { audio.enabled.collect { _audio.value = it } }
                }
                opened.session.surface?.let { target ->
                    // The pointer is part of the video picture; a local arrow would show it twice.
                    _cursor.value = RemoteCursor.Hidden
                    scale = 1
                    _video.value = target
                    // A stream has no frame sink callback; its byte count is sampled instead.
                    launch {
                        while (true) {
                            _bytesReceived.value = opened.session.bytesReceived
                            kotlinx.coroutines.delay(1_000)
                        }
                    }
                    if (metered && !hints.getBoolean(METERED_STREAM_KEY, false)) {
                        hints.edit().putBoolean(METERED_STREAM_KEY, true).apply()
                        _notices.tryEmit(RemoteScreenNotice.StreamOnMeteredNetwork(_quality.value))
                    }
                }
                opened.session.setPaused(!visible)
                opened.session.statsLogger = { Log.d(PERF_TAG, it.toString()) }
                opened.session.start(viewModelScope)
                launch { opened.session.stats.collect { _stats.value = it } }
                opened.session.rdpStats?.let { flow -> launch { flow.collect { _rdpStats.value = it } } }
                opened.session.streamStats?.let { flow -> launch { flow.collect { _streamStats.value = it } } }
                val openedAt = SystemClock.elapsedRealtime()
                launch {
                    // Servers send their current clipboard right after connecting; only later copies are news.
                    opened.session.clipboard.collect {
                        if (SystemClock.elapsedRealtime() - openedAt > INITIAL_CLIPBOARD_MILLIS) _notices.tryEmit(RemoteScreenNotice.RemoteClipboard(it))
                    }
                }
                opened.session.state.collect { state ->
                    when (state) {
                        RemoteScreenState.Connecting -> _state.value = RemoteScreenUiState.Connecting
                        is RemoteScreenState.Connected -> {
                            serverWidth = state.width
                            serverHeight = state.height
                            suggestStream(opened)
                            _state.value = RemoteScreenUiState.Connected(
                                state.name, (state.width + state.scale - 1) / state.scale,
                                (state.height + state.scale - 1) / state.scale,
                            )
                        }
                        is RemoteScreenState.Closed -> {
                            _state.value = state.error?.let(RemoteScreenUiState::Failed) ?: RemoteScreenUiState.Closed
                            state.error?.let(::checkAutomaticFix)
                            opened.close()
                            if (connection === opened) connection = null
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.value = RemoteScreenUiState.Failed(error)
                checkAutomaticFix(error)
            }
        }
    }

    private fun close() {
        _cursor.value = RemoteCursor.Unknown
        _stats.value = null
        _rdpStats.value = null
        _streamStats.value = null
        _video.value = null
        _connectionInfo.value = null
        _audio.value = null
        connectJob?.cancel()
        connectJob = null
        connection?.close()
        connection = null
        buttons = 0
    }

    private fun updatePointer(x: Float, y: Float) {
        pointerX = (x * scale).toInt().coerceIn(0, maxOf(0, serverWidth - 1))
        pointerY = (y * scale).toInt().coerceIn(0, maxOf(0, serverHeight - 1))
    }

    private fun send() {
        connection?.session?.pointer(pointerX, pointerY, buttons)
    }

    private fun modifierKeysym(modifier: RemoteModifier): Int = when (modifier) {
        RemoteModifier.SHIFT -> RfbKeys.SHIFT_L
        RemoteModifier.CONTROL -> RfbKeys.CONTROL_L
        RemoteModifier.ALT -> RfbKeys.ALT_L
        RemoteModifier.COMMAND ->
            if (connection?.platform == RemoteScreenPlatform.MACOS) RfbKeys.SUPER_L else RfbKeys.CONTROL_L
        RemoteModifier.SUPER -> RfbKeys.SUPER_L
    }

    private fun connectivity(): ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

    override fun onCleared() {
        // Leaving the screen hands the desktop back to the partner.
        handBackToPartner()
        keyboard.close()
        close()
    }

    private companion object {
        fun decodeJpeg(bytes: ByteArray, length: Int, fb: Framebuffer, x: Int, y: Int, w: Int, h: Int) {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, length)
                ?: throw java.io.IOException("Invalid JPEG rectangle")
            try {
                decoded.getPixels(fb.pixels, y * fb.width + x, fb.width, 0, 0, minOf(w, decoded.width), minOf(h, decoded.height))
            } finally {
                decoded.recycle()
            }
        }
    }
}

/** The host a [RemoteScreenVM.streamSuggestion] is about, and the identity it was checked against. */
data class StreamSuggestion(val hostId: String, val revision: String)

private fun streamDismissedKey(hostId: String) = "stream_suggestion_dismissed_$hostId"

/** VNC frame caps for each picture level. */
private fun vncFps(quality: RemoteScreenQuality) = when (quality) {
    RemoteScreenQuality.SAVER -> 5
    RemoteScreenQuality.BALANCED -> 10
    RemoteScreenQuality.BEST -> 20
}

private const val AUDIO_KEY = "stream_audio"
private const val METERED_STREAM_KEY = "stream_metered_warned"

/** Shared with the page's one-time hints. */
internal const val SCREEN_HINTS = "remote_screen_hints"

private const val INITIAL_CLIPBOARD_MILLIS = 3_000L
private const val PERF_TAG = "RemoteScreenPerf"
