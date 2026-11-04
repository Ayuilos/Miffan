package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import me.ayuilos.miffan.data.repository.RemoteHostDesktop
import kotlinx.coroutines.delay
import me.ayuilos.miffan.data.repository.SpaceMediaClosedException
import me.ayuilos.miffan.data.repository.CuaSpaceDesktopSession
import me.rerere.cuaspace.SpaceWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
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
import me.ayuilos.miffan.data.ai.computer.ComputerHolder
import me.ayuilos.miffan.data.ai.computer.RemoteComputerControl
import me.ayuilos.miffan.data.ai.computer.RemoteController
import me.ayuilos.miffan.data.db.entity.RemoteScreenEndpoint
import me.ayuilos.miffan.data.repository.RemoteScreenConnection
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import me.ayuilos.miffan.data.repository.RemoteScreenRepository
import me.ayuilos.miffan.data.repository.SnapshotDesktopSession
import me.ayuilos.miffan.data.repository.snapshotQuality
import me.ayuilos.miffan.data.repository.RemoteDesktopProtocol
import me.ayuilos.miffan.data.repository.RemoteScreenQuality
import me.ayuilos.miffan.data.repository.RemoteStreamCustom
import me.ayuilos.miffan.data.repository.RemoteStreamRequest
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.data.repository.RemoteStreamCertificateChangedException
import me.ayuilos.miffan.data.repository.RemoteStreamFallback
import me.ayuilos.miffan.data.repository.RemoteSurfaceTarget
import me.ayuilos.miffan.data.repository.RemoteSunshineFirewallPlan
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
    /** A Mac shortcut over a stream needs cua-driver on the Mac ([cuaMissing]) or its call failed. */
    data class MacShortcutFailed(val cuaMissing: Boolean) : RemoteScreenNotice
    /** The remote side copied text (Latin-1 only through standard RFB). */
    data class RemoteClipboard(val text: String) : RemoteScreenNotice
    /** High-performance mode was on but this connection uses VNC or RDP instead. */
    data class StreamFallback(val fallback: RemoteStreamFallback) : RemoteScreenNotice
    /** The first stream on mobile data: what the chosen picture level may cost per hour. */
    data class StreamOnMeteredNetwork(val quality: RemoteScreenQuality, val custom: RemoteStreamCustom) : RemoteScreenNotice
    /** The approved Windows firewall change was refused or did not verify; nothing reconnects. */
    data class FirewallFailed(val detail: String?) : RemoteScreenNotice
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
    /** Set when the picture runs through a relay: its pace in kbps (0 = no cap). */
    val relayMediaKbps: Int? = null,
    /** Only one window of the computer is shown. */
    val window: Boolean = false,
)

data class RemoteScreenArgs(val workspaceId: String)

/**
 * A window offered in the picker, with its thumbnail once captured and the desktop it is on.
 * [background]: its app runs without showing it, so picking it opens the app first.
 */
data class RemoteWindowChoice(val window: SpaceWindow, val thumbnail: ImageBitmap?, val desktop: Long?, val current: Boolean,
    val background: Boolean)

data class RemoteWindowList(val choices: List<RemoteWindowChoice>, val desktops: List<RemoteHostDesktop>)

/** Why the shown window has no picture. */
enum class RemoteWindowIssue { MINIMIZED, CLOSED, PERMISSION, OTHER_DESKTOP, LOCKED, NOT_AUTHORIZED }

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

    private val _custom = MutableStateFlow(RemoteStreamCustom.of(hints.getInt(CUSTOM_HEIGHT_KEY, 1080), hints.getInt(CUSTOM_FPS_KEY, 30)))
    /** The size and frame rate a stream uses at [RemoteScreenQuality.CUSTOM]; shared by all networks. */
    val custom: StateFlow<RemoteStreamCustom> = _custom.asStateFlow()

    // Experimental single-window view: a Mac that shares its desktop through cua-spacesd.
    private val _windowView = MutableStateFlow(false)
    /** Whether this computer can show a single window. */
    val windowView: StateFlow<Boolean> = _windowView.asStateFlow()
    private val _target = MutableStateFlow<SpaceWindow?>(null)
    /** The window shown alone; null shows the whole screen. */
    val target: StateFlow<SpaceWindow?> = _target.asStateFlow()
    private val _windows = MutableStateFlow<RemoteWindowList?>(null)
    /** The windows to pick from, with thumbnails filling in; null while loading. */
    val windows: StateFlow<RemoteWindowList?> = _windows.asStateFlow()
    private val _opening = MutableStateFlow<String?>(null)
    /** The app being opened for a window that ran in the background. */
    val opening: StateFlow<String?> = _opening.asStateFlow()
    private val _windowIssue = MutableStateFlow<RemoteWindowIssue?>(null)
    val windowIssue: StateFlow<RemoteWindowIssue?> = _windowIssue.asStateFlow()
    /** The shown window's size before "fit to phone" changed it, to put it back. */
    private val _fittedFrom = MutableStateFlow<Pair<Double, Double>?>(null)
    val fitted: StateFlow<Boolean> = _fittedFrom.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private var windowsJob: Job? = null
    private val listedWindows = MutableStateFlow<me.ayuilos.miffan.data.repository.RemoteHostWindows?>(null)
    /** Pictures taken so far by window id; main thread only. */
    private val thumbnails = mutableMapOf<String, WindowThumbnail>()
    /** Whether each window was shown when the computer last could tell (its desktop in front); main thread only. */
    private val shownWindows = mutableMapOf<String, Boolean>()
    private val pickerOpen = MutableStateFlow(false)
    /** Counts picker openings, which cut the loops' waits short. */
    private val pickerOpens = MutableStateFlow(0)

    private val _connectionInfo = MutableStateFlow<RemoteConnectionInfo?>(null)
    val connectionInfo: StateFlow<RemoteConnectionInfo?> = _connectionInfo.asStateFlow()

    private val _audio = MutableStateFlow<Boolean?>(null)
    /** Whether the stream's sound plays; null when the connection carries no sound. */
    val audio: StateFlow<Boolean?> = _audio.asStateFlow()

    /** The plain connection was chosen from the menu; kept until the user asks for the stream again. */
    private var skipStream = false

    private val _relaySnapshots = MutableStateFlow(hints.getBoolean(RELAY_SNAPSHOT_KEY, true))
    /**
     * Through a relay, still pictures (snapshot mode) rather than the live picture; remembered for
     * every computer. Windows always uses pictures through a relay.
     */
    val relaySnapshots: StateFlow<Boolean> = _relaySnapshots.asStateFlow()

    /** Switches between still pictures and the live picture through a relay, and reconnects. */
    fun useRelaySnapshots(enabled: Boolean) {
        _relaySnapshots.value = enabled
        hints.edit().putBoolean(RELAY_SNAPSHOT_KEY, enabled).apply()
        reconnect()
    }

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

    /** The [video] picture for pages that remember the screen; see [currentFrame]. */
    internal val videoFrames = RemoteVideoFrames()

    /**
     * What the screen shows now, for pages that keep a picture of it (a mini box's detail, the chat):
     * the video's when one plays, otherwise the bitmap. Null when nothing real has been drawn yet.
     */
    fun currentFrame(): Bitmap? =
        (if (_video.value != null) videoFrames.current() else _bitmap.value)?.takeUnless { it.isRecycled || it.isBlank() }

    private val _streamStats = MutableStateFlow<StreamStats?>(null)
    /** The stream's own numbers; null unless [video] is set. */
    val streamStats: StateFlow<StreamStats?> = _streamStats.asStateFlow()

    private val _stillPicture = MutableStateFlow(false)
    /** The picture stopped refreshing by itself (a still screen through a relay); [refreshPicture] resumes it. */
    val stillPicture: StateFlow<Boolean> = _stillPicture.asStateFlow()

    /** Fetches a new picture now and resumes automatic refreshing; see [stillPicture]. */
    fun refreshPicture() {
        connection?.session?.refresh()
    }

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
    /** The key control is arbitrated under: the host's id, or a partner's own computer's (see spaceControlKey). */
    private val _controlKey = MutableStateFlow<String?>(null)

    /**
     * Who drives this machine's desktop. Any input from this page makes the user the controller,
     * which stops the partner's computer actions until [handBackToPartner] or the page closes.
     */
    val controller: StateFlow<RemoteController> = combine(_controlKey, control.states) { key, states ->
        key?.let { states[it] } ?: RemoteController.IDLE
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RemoteController.IDLE)

    /** The partner turn holding this desktop, from its first action until its reply ends; null when none. */
    val holder: StateFlow<ComputerHolder?> = combine(_controlKey, control.holders) { key, holders -> key?.let { holders[it] } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The partner tried to act while the user held this desktop and is waiting for it back. */
    val partnerWaiting: StateFlow<Boolean> = combine(_controlKey, control.waiting) { key, waiting -> key in waiting }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun handBackToPartner() {
        _controlKey.value?.let(control::userHandsBack)
    }

    /** The user touched the screen: take the desktop now, before a tap or drag sends any input. */
    fun takeControl() = userInput()

    private fun userInput() {
        _controlKey.value?.let { if (control.controller(it) != RemoteController.USER) control.userTakesOver(it) }
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
    /** A Mac's VNC viewer kept open, paused, behind a stream; see [reconnectWithStream]. */
    private var heldVnc: RemoteScreenConnection? = null
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
        // A Windows computer can fail before any connection reports its platform; its settings already know it.
        viewModelScope.launch {
            val hostId = repository.hostIdOf(args.workspaceId) ?: return@launch
            val known = runCatching { repository.getConfig(hostId)?.platform }.getOrNull() ?: return@launch
            if (_platform.value == RemoteScreenPlatform.UNKNOWN) _platform.value = known
        }
        viewModelScope.launch {
            _windowView.value = repository.hostWindowsAvailable(args.workspaceId)
            followWindows()
        }
        connect()
    }

    /**
     * Keeps the window list current while the page is on screen, so the picker opens with it: every
     * 10 seconds, every 2 while the picker is open ([watchWindows]). Pictures are taken for new windows
     * and ones that just came back; the picker being open also refreshes the others now and then (each
     * takes seconds on the computer and shares its capture with the picture being watched).
     */
    private fun followWindows() {
        windowsJob?.cancel()
        if (!_windowView.value || !visible) { windowsJob = null; return }
        windowsJob = viewModelScope.launch {
            launch {
                while (true) {
                    val opens = pickerOpens.value
                    runCatching { repository.hostWindows(args.workspaceId) }
                        .onSuccess { listed -> noteShown(listed); listedWindows.value = listed; publishWindows() }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            Log.w(TAG, "Listing windows failed", error)
                            if (_windows.value == null) _windows.value = RemoteWindowList(emptyList(), emptyList())
                        }
                    // Opening the picker cuts the wait short.
                    withTimeoutOrNull(if (pickerOpen.value) WINDOW_REFRESH_MILLIS else WINDOW_IDLE_REFRESH_MILLIS) {
                        pickerOpens.first { it != opens }
                    }
                }
            }
            // Pictures take seconds each, so they run beside the list instead of holding it up.
            while (true) {
                val opens = pickerOpens.value
                val listed = listedWindows.filterNotNull().first()
                val open = pickerOpen.value
                val now = SystemClock.elapsedRealtime()
                val due = listed.windows.filter { hosted ->
                    // A window known to be shown nowhere has no picture to take.
                    if (hosted.window.state == SpaceWindow.State.MINIMIZED || hosted.hidden) return@filter false
                    val thumb = thumbnails[hosted.window.id] ?: return@filter true
                    when {
                        thumb.image == null -> now - thumb.at > if (open) BACKGROUND_RETRY_MILLIS else BACKGROUND_IDLE_RETRY_MILLIS
                        open -> now - thumb.at > THUMBNAIL_REFRESH_MILLIS
                        else -> false
                    }
                }.sortedWith(compareBy({ thumbnails[it.window.id]?.image != null }, { !it.current }))
                if (due.isEmpty()) {
                    withTimeoutOrNull(WINDOW_REFRESH_MILLIS) { pickerOpens.first { it != opens } }
                    continue
                }
                runCatching {
                    repository.hostWindowThumbnails(args.workspaceId, due.map { it.window }).collect { (window, bytes) ->
                        val image = bytes?.let {
                            withContext(Dispatchers.Default) {
                                BitmapFactory.decodeByteArray(it, 0, it.size)?.takeUnless { bitmap -> bitmap.isBlank() }?.asImageBitmap()
                            }
                        }
                        val at = SystemClock.elapsedRealtime()
                        // A failed picture keeps the last good one; a real picture means the window is shown.
                        thumbnails[window.id] = if (image != null) WindowThumbnail(image, at)
                            else WindowThumbnail(thumbnails[window.id]?.image, at, failed = true)
                        if (image != null) shownWindows[window.id] = true
                        publishWindows()
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Window pictures failed", error)
                    delay(WINDOW_REFRESH_MILLIS)
                }
            }
        }
    }

    /**
     * Remembers which windows the computer showed while their desktop was in front, the only time it
     * can tell. A window that comes back gets a fresh picture right away.
     */
    private fun noteShown(listed: me.ayuilos.miffan.data.repository.RemoteHostWindows) {
        listed.windows.filter { it.current }.forEach { hosted ->
            val shown = !hosted.hidden
            if (shown && shownWindows[hosted.window.id] == false) thumbnails[hosted.window.id]?.let {
                thumbnails[hosted.window.id] = WindowThumbnail(it.image, 0L, failed = it.failed)
            }
            shownWindows[hosted.window.id] = shown
        }
        val ids = listed.windows.mapTo(HashSet()) { it.window.id }
        shownWindows.keys.retainAll(ids)
        thumbnails.keys.retainAll(ids)
    }

    /** The picker opened: refresh now and more often, and refresh pictures. Undone by [stopWindows]. */
    fun watchWindows() {
        pickerOpen.value = true
        pickerOpens.value++
        if (windowsJob?.isActive != true) followWindows()
    }

    fun stopWindows() {
        pickerOpen.value = false
    }

    /**
     * The picker's rows from the latest list and the pictures so far; a picture stays until a newer one
     * comes. On the desktop in front the computer says which windows are shown; elsewhere what it said
     * last time holds, and a window it never saw is in the background when it gives no picture.
     */
    private fun publishWindows() {
        val listed = listedWindows.value ?: return
        _windows.value = RemoteWindowList(listed.windows.map { hosted ->
            val thumb = thumbnails[hosted.window.id]
            val background = when {
                hosted.current -> hosted.hidden
                else -> when (shownWindows[hosted.window.id]) {
                    true -> false
                    false -> true
                    null -> thumb != null && thumb.image == null && thumb.failed
                }
            }
            RemoteWindowChoice(hosted.window, thumb?.image, hosted.desktop, hosted.current, background)
        }, listed.desktops)
    }

    /**
     * Shows [choice]. A window whose app runs in the background is opened first, as a Dock click
     * would, then the window the app shows; [onShown] runs once something is shown.
     */
    fun pickWindow(choice: RemoteWindowChoice, onShown: () -> Unit = {}) {
        if (!choice.background) { showWindow(choice.window); onShown(); return }
        viewModelScope.launch {
            _opening.value = choice.window.app
            try {
                val shown = runCatching { repository.reopenHostApp(args.workspaceId, choice.window) }
                    .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Opening ${choice.window.app} failed", it) }
                    .getOrNull()
                thumbnails[choice.window.id]?.let { thumbnails[choice.window.id] = WindowThumbnail(it.image, 0L, failed = it.failed) }
                shownWindows[choice.window.id] = true
                showWindow(shown ?: choice.window)
                onShown()
            } finally {
                _opening.value = null
            }
        }
    }

    /** Shows [window] alone, or the whole screen for null; a minimized window is brought back first. */
    fun showWindow(window: SpaceWindow?) {
        viewModelScope.launch {
            unfitWindow()
            _windowIssue.value = null
            if (window != null && window.state == SpaceWindow.State.MINIMIZED) {
                runCatching { repository.restoreHostWindow(args.workspaceId, window) }
            }
            _target.value = window
            reconnect()
        }
    }

    /** Puts a minimized window back, then shows it again. */
    fun restoreWindow() {
        val window = _target.value ?: return
        viewModelScope.launch {
            runCatching { repository.restoreHostWindow(args.workspaceId, window) }
            _windowIssue.value = null
            reconnect()
        }
    }

    /** KDE: grants the helper Plasma's remote desktop permission (the user just agreed), then shows the window. */
    fun authorizeWindows() {
        viewModelScope.launch {
            val granted = runCatching { repository.authorizeHostWindows(args.workspaceId) }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Authorizing windows failed", it) }
                .getOrDefault(false)
            if (!granted) return@launch
            _windowIssue.value = null
            reconnect()
        }
    }

    /** Raises the window on the computer, switching to its desktop when it is on another one. */
    fun activateWindow() {
        val window = _target.value ?: return
        viewModelScope.launch {
            runCatching { repository.activateHostWindow(args.workspaceId, window) }
            _windowIssue.value = null
            reconnect()
        }
    }

    /**
     * Reshapes the shown window on the computer to the phone's [aspect] (width / height), so it fills
     * the phone; [unfitWindow] puts the old size back.
     */
    fun fitWindow(aspect: Float) {
        val window = _target.value ?: return
        if (_fittedFrom.value != null || aspect <= 0f) return
        viewModelScope.launch {
            val height = window.height.coerceIn(FIT_MIN_HEIGHT, FIT_MAX_HEIGHT)
            val width = (height * aspect).coerceAtLeast(FIT_MIN_WIDTH)
            runCatching { repository.resizeHostWindow(args.workspaceId, window, width, height) }
                .onSuccess { _fittedFrom.value = window.width to window.height }
                .onFailure { Log.w(TAG, "Resizing the window failed", it) }
        }
    }

    suspend fun unfitWindow() {
        val window = _target.value ?: return
        val (width, height) = _fittedFrom.value ?: return
        _fittedFrom.value = null
        runCatching { repository.resizeHostWindow(args.workspaceId, window, width, height) }
    }

    fun toggleFit(aspect: Float) {
        if (_fittedFrom.value != null) viewModelScope.launch { unfitWindow() } else fitWindow(aspect)
    }

    /** Leaves the computer's window as it was found when the page goes away. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun restoreFittedWindow() {
        val window = _target.value ?: return
        val size = _fittedFrom.value ?: return
        kotlinx.coroutines.GlobalScope.launch {
            runCatching { repository.resizeHostWindow(args.workspaceId, window, size.first, size.second) }
        }
    }

    fun reconnect() {
        close()
        connect()
    }

    /** Stop requesting frames while the page is not visible; bandwidth drops to zero. */
    fun setVisible(value: Boolean) {
        val changed = visible != value
        visible = value
        connection?.session?.setPaused(!value)
        // The window list sleeps with the page.
        if (changed) followWindows()
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
        // Through a relay high-performance mode cannot work (no UDP), so suggesting it would only disappoint.
        if (streamChecked || opened.session.surface != null || opened.streamFallback != null || opened.relayMediaKbps != null) return
        if (opened.platform == RemoteScreenPlatform.UNKNOWN || hints.getBoolean(streamDismissedKey(opened.hostId), false)) return
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

    fun pairStream(interruptActiveStream: Boolean = false) =
        _streamSuggestion.value?.let { streamSetup.pair(it.hostId, it.revision, interruptActiveStream) }
    fun dismissStreamInterrupt() = _streamSuggestion.value?.let { streamSetup.dismissInterrupt(it.hostId) }
    fun cancelStreamPairing() = streamSetup.cancelPairing()
    /** Called only from the confirmation that shows exactly what changes on the computer. */
    fun enforceStreamEncryption() = _streamSuggestion.value?.let { streamSetup.enforceEncryption(it.hostId, it.revision) }
    fun enableStream() = _streamSuggestion.value?.let { streamSetup.setEnabled(it.hostId, true) }
    fun startSunshine() = _streamSuggestion.value?.let { streamSetup.start(it.hostId, it.revision) }

    /** The suggestion's flow ended; once the mode is on, reconnect to use it. */
    fun finishStreamSuggestion(enabled: Boolean) {
        if (!enabled) return
        _streamSuggestion.value = null
        reconnect()
    }

    private var typingPreparedAt = 0L

    /**
     * Windows writes typed text through the cua bridge, which exits after two idle minutes; start it
     * when the screen connects or the keyboard opens so the first keystrokes need not wait for it.
     */
    fun prepareTyping() {
        if (_platform.value != RemoteScreenPlatform.WINDOWS) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - typingPreparedAt < 60_000) return
        typingPreparedAt = now
        viewModelScope.launch {
            try { repository.prepareWindowsTyping(args.workspaceId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { typingPreparedAt = 0L }
        }
    }

    private val _windowsSetup = MutableStateFlow(false)
    /** A Windows computer has no plain connection, so its failed connection walks through Sunshine setup. */
    val windowsSetup: StateFlow<Boolean> = _windowsSetup.asStateFlow()

    fun beginWindowsStreamSetup() {
        viewModelScope.launch {
            val hostId = repository.hostIdOf(args.workspaceId) ?: return@launch
            val host = runCatching { workspaces.getHostById(hostId) }.getOrNull() ?: return@launch
            _streamSuggestion.value = StreamSuggestion(host.id, host.connectionRevision)
            _windowsSetup.value = true
            streamSetup.check(host.id, host.connectionRevision)
        }
    }

    fun finishWindowsStreamSetup(completed: Boolean) {
        _windowsSetup.value = false
        _streamSuggestion.value = null
        if (completed) reconnect()
    }

    private val _firewallApplying = MutableStateFlow(false)
    val firewallApplying: StateFlow<Boolean> = _firewallApplying.asStateFlow()

    /** Called only from the confirmation that lists this exact plan; the computer re-checks it before writing. */
    fun applySunshineFirewall(plan: RemoteSunshineFirewallPlan) {
        if (_firewallApplying.value) return
        _firewallApplying.value = true
        viewModelScope.launch {
            try {
                val hostId = repository.hostIdOf(args.workspaceId) ?: return@launch
                val host = workspaces.getHostById(hostId) ?: return@launch
                val result = repository.applySunshineFirewall(host.id, host.connectionRevision, plan.approve())
                if (result.success) reconnect() else _notices.tryEmit(RemoteScreenNotice.FirewallFailed(result.detail))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _notices.tryEmit(RemoteScreenNotice.FirewallFailed(error.localizedMessage))
            } finally {
                _firewallApplying.value = false
            }
        }
    }

    /** Applies at once on VNC; a stream reconnects with the new resolution and bitrate. */
    fun setQuality(quality: RemoteScreenQuality) {
        if (quality == _quality.value) return
        _quality.value = quality
        hints.edit().putString(qualityKey, quality.name).apply()
        val session = connection?.session ?: return
        when (session.protocol) {
            RemoteDesktopProtocol.VNC -> session.setMaxFps(vncFps(quality))
            RemoteDesktopProtocol.STREAM, RemoteDesktopProtocol.SPACE -> reconnect()
            RemoteDesktopProtocol.SNAPSHOT -> (session as? SnapshotDesktopSession)?.setQuality(quality.snapshotQuality())
            RemoteDesktopProtocol.RDP -> Unit
        }
    }

    /** Selects [RemoteScreenQuality.CUSTOM] at [custom]; a stream reconnects only when its picture changes. */
    fun setCustom(custom: RemoteStreamCustom) {
        val changed = custom != _custom.value
        _custom.value = custom
        hints.edit().putInt(CUSTOM_HEIGHT_KEY, custom.height).putInt(CUSTOM_FPS_KEY, custom.fps).apply()
        if (_quality.value != RemoteScreenQuality.CUSTOM) setQuality(RemoteScreenQuality.CUSTOM)
        else if (changed && connection?.session?.protocol == RemoteDesktopProtocol.STREAM) reconnect()
    }

    /** Reconnects with or without high-performance mode for this visit only; the computer's setting stays. */
    fun reconnectWithStream(stream: Boolean) {
        skipStream = !stream
        val current = connection
        if (stream && heldVnc == null && current != null && current.platform == RemoteScreenPlatform.MACOS &&
            current.session.protocol == RemoteDesktopProtocol.VNC
        ) {
            // Closing the last Screen Sharing viewer locks a Mac again if it was locked when the
            // viewer connected, and the lock screen ignores streamed keys. Keep it until the page leaves.
            current.session.setPaused(true)
            heldVnc = current
            connection = null
        }
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
     * A Mission Control shortcut. VNC sends Control+arrow; a stream's keys reach macOS without the
     * fn flag its system shortcuts need, so a stream presses them through cua-driver over SSH.
     */
    internal fun macShortcut(shortcut: MacShortcut) {
        // Snapshot mode presses keys through cua-driver too, which has the fn flag macOS wants here.
        if (connection?.session?.protocol !in setOf(RemoteDesktopProtocol.STREAM, RemoteDesktopProtocol.SNAPSHOT)) {
            key(when (shortcut) {
                MacShortcut.DESKTOP_LEFT -> RfbKeys.LEFT
                MacShortcut.DESKTOP_RIGHT -> RfbKeys.RIGHT
                MacShortcut.MISSION_CONTROL -> RfbKeys.UP
                MacShortcut.APP_WINDOWS -> RfbKeys.DOWN
            }, setOf(RemoteModifier.CONTROL))
            return
        }
        userInput()
        keyboard.trySend {
            val name = when (shortcut) {
                MacShortcut.DESKTOP_LEFT -> "desktop-left"
                MacShortcut.DESKTOP_RIGHT -> "desktop-right"
                MacShortcut.MISSION_CONTROL -> "mission-control"
                MacShortcut.APP_WINDOWS -> "app-windows"
            }
            try {
                if (!repository.pressMacShortcut(args.workspaceId, name)) _notices.tryEmit(RemoteScreenNotice.MacShortcutFailed(cuaMissing = true))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "Mac shortcut $name failed", error)
                _notices.tryEmit(RemoteScreenNotice.MacShortcutFailed(cuaMissing = false))
            }
        }
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
        val connectStarted = SystemClock.elapsedRealtime()
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
                    RemoteStreamRequest(quality, skip = skipStream, custom = _custom.value, snapshot = _relaySnapshots.value,
                        window = _target.value),
                )
                connection = opened
                if (_target.value != null) Log.d(PERF_TAG, "Window stream opened after ${SystemClock.elapsedRealtime() - connectStarted} ms")
                _hostId.value = opened.hostId
                _controlKey.value = opened.controlKey
                _platform.value = opened.platform
                prepareTyping()
                opened.streamFallback?.let { _notices.tryEmit(RemoteScreenNotice.StreamFallback(it)) }
                _connectionInfo.value = RemoteConnectionInfo(opened.session.protocol, opened.streamRequested,
                    opened.streamAddress, opened.streamFallback, skipStream, opened.relayMediaKbps, window = _target.value != null)
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
                    // The hint quotes high-performance mode's data use; a partner's own computer is not that.
                    if (metered && opened.session.protocol == RemoteDesktopProtocol.STREAM && !hints.getBoolean(METERED_STREAM_KEY, false)) {
                        hints.edit().putBoolean(METERED_STREAM_KEY, true).apply()
                        _notices.tryEmit(RemoteScreenNotice.StreamOnMeteredNetwork(_quality.value, _custom.value))
                    }
                }
                (opened.session as? CuaSpaceDesktopSession)?.takeIf { _target.value != null }?.let { session ->
                    // Input lands on whatever is in front on the computer: notice when that stops being this window.
                    val shown = _target.value
                    if (shown != null) launch {
                        while (true) {
                            if (visible) repository.hostWindowInFront(args.workspaceId, shown)?.let(session::noteFront)
                            delay(FRONT_CHECK_MILLIS)
                        }
                    }
                    launch {
                        session.suspended.collect { reason ->
                            _windowIssue.value = when {
                                reason == null -> null
                                // A Linux computer drops input while its screen is locked.
                                reason == "locked" -> RemoteWindowIssue.LOCKED
                                reason.contains("minimi", ignoreCase = true) -> RemoteWindowIssue.MINIMIZED
                                reason.contains("hidden", ignoreCase = true) || reason.contains("space", ignoreCase = true) -> RemoteWindowIssue.OTHER_DESKTOP
                                else -> RemoteWindowIssue.PERMISSION
                            }
                        }
                    }
                }
                opened.session.setPaused(!visible)
                opened.session.statsLogger = { Log.d(PERF_TAG, it.toString()) }
                opened.session.start(viewModelScope)
                launch { opened.session.stats.collect { _stats.value = it } }
                launch { opened.session.autoRefreshStopped.collect { _stillPicture.value = it } }
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
                            if (_target.value != null) Log.d(PERF_TAG, "Window picture after ${SystemClock.elapsedRealtime() - connectStarted} ms")
                            serverWidth = state.width
                            serverHeight = state.height
                            suggestStream(opened)
                            _state.value = RemoteScreenUiState.Connected(
                                state.name, (state.width + state.scale - 1) / state.scale,
                                (state.height + state.scale - 1) / state.scale,
                            )
                        }
                        is RemoteScreenState.Closed -> {
                            if (_target.value != null) state.error?.let(::windowIssueOf)?.let { _windowIssue.value = it }
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
                if (_target.value != null) windowIssueOf(error)?.let { _windowIssue.value = it }
                _state.value = RemoteScreenUiState.Failed(error)
                checkAutomaticFix(error)
            }
        }
    }

    private fun windowIssueOf(error: Throwable): RemoteWindowIssue? {
        val text = generateSequence(error) { it.cause }.joinToString(" ") { it.message.orEmpty() }
        return when {
            (error as? SpaceMediaClosedException)?.code == 4410 -> RemoteWindowIssue.CLOSED
            // A KDE computer whose user has not yet let the helper use Plasma's remote desktop portal.
            text.contains("not_authorized") -> RemoteWindowIssue.NOT_AUTHORIZED
            text.contains("TCC") || text.contains("permission denied", ignoreCase = true) -> RemoteWindowIssue.PERMISSION
            text.contains("not found", ignoreCase = true) || text.contains("NOT_FOUND") -> RemoteWindowIssue.CLOSED
            else -> null
        }
    }

    private fun close() {
        _cursor.value = RemoteCursor.Unknown
        _stats.value = null
        _rdpStats.value = null
        _streamStats.value = null
        _stillPicture.value = false
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
        restoreFittedWindow()
        keyboard.close()
        close()
        heldVnc?.close()
        heldVnc = null
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
    // A custom size and frame rate only shape the stream.
    RemoteScreenQuality.CUSTOM -> 10
}

private const val CUSTOM_HEIGHT_KEY = "stream_custom_height"
private const val CUSTOM_FPS_KEY = "stream_custom_fps"
private const val AUDIO_KEY = "stream_audio"
private const val RELAY_SNAPSHOT_KEY = "relay_snapshots"
private const val METERED_STREAM_KEY = "stream_metered_warned"

/** Shared with the page's one-time hints. */
internal const val SCREEN_HINTS = "remote_screen_hints"
private const val WINDOW_REFRESH_MILLIS = 2_000L
private const val WINDOW_IDLE_REFRESH_MILLIS = 10_000L
private const val FRONT_CHECK_MILLIS = 1_000L
private const val BACKGROUND_IDLE_RETRY_MILLIS = 120_000L

/**
 * A video picture lives in its TextureView, out of the VM's reach: [reader] reads it while the view
 * is up, and [last] keeps what it showed as the view went away.
 */
internal class RemoteVideoFrames {
    @Volatile var reader: (() -> Bitmap?)? = null
    @Volatile var last: Bitmap? = null

    /** The live picture when it can still be read, else the kept one; never a black one. */
    fun current(): Bitmap? = runCatching { reader?.invoke() }.getOrNull()?.takeUnless { it.isBlank() } ?: last

    /** [view]'s picture at most [THUMBNAIL_EDGE] on its long side; null when there is none yet. */
    fun read(view: android.view.TextureView, width: Int, height: Int): Bitmap? {
        val scale = minOf(1f, THUMBNAIL_EDGE.toFloat() / maxOf(width, height, 1))
        return runCatching { view.getBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1)) }
            .getOrNull()?.takeUnless { it.isBlank() }
    }

    private companion object { const val THUMBNAIL_EDGE = 1280 }
}

/** True when a capture shows nothing: every sampled pixel black or transparent. */
private fun android.graphics.Bitmap.isBlank(): Boolean {
    val steps = 12
    for (i in 0 until steps) for (j in 0 until steps) {
        val pixel = getPixel(width * (2 * i + 1) / (2 * steps), height * (2 * j + 1) / (2 * steps))
        val alpha = pixel ushr 24
        val brightest = maxOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)
        if (alpha > 16 && brightest > 12) return false
    }
    return true
}
private const val THUMBNAIL_REFRESH_MILLIS = 15_000L
// A window that gave no picture is tried again now and then, in case its app shows it.
private const val BACKGROUND_RETRY_MILLIS = 30_000L

/** A window's last good picture, when it was last tried, and whether that try gave none (all black or too slow). */
private class WindowThumbnail(val image: ImageBitmap?, val at: Long, val failed: Boolean = false)
private const val FIT_MIN_HEIGHT = 800.0
private const val FIT_MAX_HEIGHT = 1000.0
private const val FIT_MIN_WIDTH = 420.0

private const val INITIAL_CLIPBOARD_MILLIS = 3_000L
private const val PERF_TAG = "RemoteScreenPerf"
private const val TAG = "RemoteScreenVM"
