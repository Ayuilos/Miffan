package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import android.content.Context
import android.graphics.Bitmap
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
import me.ayuilos.miffan.data.repository.RemoteScreenConnection
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import me.ayuilos.miffan.data.repository.RemoteScreenRepository
import me.rerere.workspace.screen.Framebuffer
import me.rerere.workspace.screen.RemoteScreenFrameSink
import me.rerere.workspace.screen.RemoteScreenOptions
import me.rerere.workspace.screen.RemoteScreenState
import me.rerere.workspace.screen.RfbJpegDecoder
import me.rerere.workspace.screen.RfbKeys
import me.rerere.workspace.screen.RfbRect

/** What the screen page shows above the canvas. */
sealed interface RemoteScreenUiState {
    data object Connecting : RemoteScreenUiState

    /** [width]/[height] are bitmap pixels; input coordinates use the same space. */
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
}

data class RemoteScreenArgs(val workspaceId: String)

/**
 * Owns one VNC viewer for the page's lifetime: survives rotation, closes when the page leaves the
 * back stack. Frames land in [bitmap] on the session thread; [frameVersion] changes after each
 * frame so the canvas redraws. All coordinates in this API are bitmap pixels.
 */
class RemoteScreenVM(
    private val args: RemoteScreenArgs,
    private val repository: RemoteScreenRepository,
    context: Context,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<RemoteScreenUiState>(RemoteScreenUiState.Connecting)
    val state: StateFlow<RemoteScreenUiState> = _state.asStateFlow()

    private val _bitmap = MutableStateFlow<Bitmap?>(null)
    val bitmap: StateFlow<Bitmap?> = _bitmap.asStateFlow()

    private val _frameVersion = MutableStateFlow(0L)
    val frameVersion: StateFlow<Long> = _frameVersion.asStateFlow()

    private val _bytesReceived = MutableStateFlow(0L)
    /** Bytes received on the current connection, updated with each frame. */
    val bytesReceived: StateFlow<Long> = _bytesReceived.asStateFlow()

    private val _notices = MutableSharedFlow<RemoteScreenNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<RemoteScreenNotice> = _notices.asSharedFlow()

    /** True on metered networks; the page may show a data-usage hint. */
    val metered: Boolean = connectivity()?.isActiveNetworkMetered ?: true

    private val _maxFps = MutableStateFlow(if (metered) 10 else 20)
    /** Frame-rate cap; kept across reconnects and applied to every new session. */
    val maxFps: StateFlow<Int> = _maxFps.asStateFlow()

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

    private val sink = object : RemoteScreenFrameSink {
        override fun onSize(width: Int, height: Int, scale: Int) {
            this@RemoteScreenVM.scale = scale
            _bitmap.value = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        override fun onPixels(rect: RfbRect, pixels: IntArray) {
            _bitmap.value?.setPixels(pixels, 0, rect.width, rect.x, rect.y, rect.width, rect.height)
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

    fun setMaxFps(fps: Int) {
        _maxFps.value = fps
        connection?.session?.setMaxFps(fps)
    }

    fun movePointer(x: Float, y: Float) {
        updatePointer(x, y)
        send()
    }

    fun press(button: RemoteMouseButton, down: Boolean) {
        buttons = if (down) buttons or button.mask else buttons and button.mask.inv()
        send()
    }

    fun click(x: Float, y: Float, button: RemoteMouseButton = RemoteMouseButton.LEFT, count: Int = 1) {
        updatePointer(x, y)
        repeat(count) {
            press(button, true)
            press(button, false)
        }
    }

    /** Positive [steps] scrolls content down (wheel towards the user). */
    fun scroll(steps: Int, horizontal: Boolean = false) {
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
        keyboard.trySend { sendKey(keysym, modifiers) }
    }

    /**
     * Types text from the phone keyboard. Latin text goes as key events; anything else is put on
     * the remote clipboard over SSH and pasted with the platform shortcut.
     */
    fun typeText(text: String) {
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
        connectJob = viewModelScope.launch {
            try {
                val opened = repository.open(
                    args.workspaceId, sink, jpeg,
                    RemoteScreenOptions(
                        jpegQuality = if (metered) 4 else 6,
                        maxFps = _maxFps.value,
                        lowColor = metered,
                    ),
                )
                connection = opened
                _platform.value = opened.platform
                opened.session.setPaused(!visible)
                opened.session.start(viewModelScope)
                launch { opened.session.clipboard.collect { _notices.tryEmit(RemoteScreenNotice.RemoteClipboard(it)) } }
                opened.session.state.collect { state ->
                    when (state) {
                        RemoteScreenState.Connecting -> _state.value = RemoteScreenUiState.Connecting
                        is RemoteScreenState.Connected -> {
                            serverWidth = state.width
                            serverHeight = state.height
                            _state.value = RemoteScreenUiState.Connected(
                                state.name, (state.width + state.scale - 1) / state.scale,
                                (state.height + state.scale - 1) / state.scale,
                            )
                        }
                        is RemoteScreenState.Closed -> {
                            _state.value = state.error?.let(RemoteScreenUiState::Failed) ?: RemoteScreenUiState.Closed
                            opened.close()
                            if (connection === opened) connection = null
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.value = RemoteScreenUiState.Failed(error)
            }
        }
    }

    private fun close() {
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
