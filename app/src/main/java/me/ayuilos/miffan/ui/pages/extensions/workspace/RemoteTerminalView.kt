package me.ayuilos.miffan.ui.pages.extensions.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.view.GestureDetector
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.text.InputType
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalRenderer
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** Process-owned emulator. Only a weak reference points at an attached Android view. */
internal class RemoteTerminalScreen {
    var sendBytes: (ByteArray) -> Unit = {}
    private var attached: WeakReference<RemoteTerminalView>? = null
    fun attach(view: RemoteTerminalView) { attached = WeakReference(view); view.invalidate() }
    fun detach(view: RemoteTerminalView) {
        if (attached?.get() === view) attached = null
    }
    private fun view(): RemoteTerminalView? = attached?.get()

    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            if (count > 0) sendBytes(data.copyOfRange(offset, offset + count))
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit

        override fun onCopyTextToClipboard(text: String) {
            view()?.copyToClipboard(text)
        }

        override fun onPasteTextFromClipboard() { view()?.pasteFromClipboard() }
        override fun onBell() = Unit
        override fun onColorsChanged() { view()?.invalidate() }
    }
    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { view()?.invalidate() }
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) = output.onCopyTextToClipboard(text)
        override fun onPasteTextFromClipboard(session: TerminalSession) { view()?.pasteFromClipboard() }
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) { view()?.invalidate() }
        override fun onTerminalCursorStateChange(state: Boolean) { view()?.invalidate() }
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String, message: String) = Log.e(tag, message).let { Unit }
        override fun logWarn(tag: String, message: String) = Log.w(tag, message).let { Unit }
        override fun logInfo(tag: String, message: String) = Log.i(tag, message).let { Unit }
        override fun logDebug(tag: String, message: String) = Log.d(tag, message).let { Unit }
        override fun logVerbose(tag: String, message: String) = Log.v(tag, message).let { Unit }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) =
            Log.e(tag, message, e).let { Unit }
        override fun logStackTrace(tag: String, e: Exception) = Log.e(tag, "Terminal error", e).let { Unit }
    }
    var emulator = TerminalEmulator(output, 80, 24, 2_000, sessionClient)
        private set

    fun appendOutput(bytes: ByteArray, count: Int = bytes.size) {
        if (count > 0) emulator.append(bytes, count)
        view()?.invalidate()
    }

    fun reset(columns: Int, rows: Int) {
        emulator = TerminalEmulator(output, columns, rows, 2_000, sessionClient)
        view()?.invalidate()
    }
}

/** A view can reattach to the same emulator after navigation or configuration changes. */
internal class RemoteTerminalView(context: Context) : View(context) {
    private val renderer = TerminalRenderer(
        (13f * resources.displayMetrics.scaledDensity).roundToInt().coerceAtLeast(12),
        Typeface.MONOSPACE,
    )
    var screen = RemoteTerminalScreen()
        set(value) {
            field.detach(this)
            field = value
            value.emulator.resize(columns, rows)
            topRow = 0
            scrollRemainder = 0f
            value.attach(this)
            invalidate()
        }
    private val emulator get() = screen.emulator
    private var topRow = 0
    private var scrollRemainder = 0f
    var columns: Int = 80
        private set
    var rows: Int = 24
        private set
    var sendBytes: (ByteArray) -> Unit = {}
    var onSizeInCellsChanged: (Int, Int) -> Unit = { _, _ -> }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            showKeyboard()
            return true
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent,
            distanceX: Float, distanceY: Float,
        ): Boolean {
            scrollRemainder += distanceY
            val lineHeight = renderer.fontLineSpacing.coerceAtLeast(1)
            val lines = (scrollRemainder / lineHeight).toInt()
            if (lines != 0) {
                scrollRemainder -= lines * lineHeight
                val oldest = -emulator.screen.activeTranscriptRows
                topRow = (topRow + lines).coerceIn(oldest, 0)
                invalidate()
            }
            return true
        }
    })

    init {
        screen.attach(this)
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    fun appendOutput(bytes: ByteArray, count: Int = bytes.size) {
        if (count <= 0) return
        screen.appendOutput(bytes, count)
        if (topRow < 0) {
            topRow = (topRow - emulator.scrollCounter).coerceAtLeast(-emulator.screen.activeTranscriptRows)
        }
        emulator.clearScrollCounter()
        invalidate()
    }

    fun resetScreen() {
        screen.reset(columns, rows)
        topRow = 0
        scrollRemainder = 0f
        invalidate()
    }

    fun sendText(text: String) {
        if (text.isNotEmpty()) sendBytes(text.toByteArray(Charsets.UTF_8))
    }

    fun sendSpecialKey(keyCode: Int) {
        val code = KeyHandler.getCode(
            keyCode, 0, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode,
        ) ?: when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007f"
            else -> null
        }
        if (code != null) sendText(code)
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        // Bracketed paste is generated by the emulator when the remote program requested it.
        emulator.paste(text.take(MAX_PASTE_CHARS))
    }

    internal fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    fun detachScreen() { screen.detach(this) }

    fun showKeyboard() {
        requestFocus()
        val inputManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        renderer.render(emulator, canvas, topRow, -1, -1, -1, -1)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val nextColumns = (w / renderer.fontWidth).toInt().coerceAtLeast(2)
        val nextRows = (h / renderer.fontLineSpacing).coerceAtLeast(2)
        if (nextColumns != columns || nextRows != rows) {
            columns = nextColumns
            rows = nextRows
            emulator.resize(columns, rows)
            topRow = topRow.coerceAtLeast(-emulator.screen.activeTranscriptRows)
            onSizeInCellsChanged(columns, rows)
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composingText: String? = null
            private var composingCursor = 0

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                composingText = value
                composingCursor = if (newCursorPosition > 0) {
                    (value.length + newCursorPosition - 1).coerceIn(0, value.length)
                } else {
                    newCursorPosition.coerceIn(0, value.length)
                }
                return true
            }

            override fun finishComposingText(): Boolean {
                val pending = composingText
                composingText = null
                composingCursor = 0
                pending?.let { sendText(it) }
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composingText = null
                composingCursor = 0
                text?.let { sendText(it.toString()) }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                composingText?.let { pending ->
                    val start = (composingCursor - beforeLength.coerceAtLeast(0)).coerceAtLeast(0)
                    val end = (composingCursor + afterLength.coerceAtLeast(0)).coerceAtMost(pending.length)
                    composingText = pending.removeRange(start, end)
                    composingCursor = start
                    return true
                }
                repeat(beforeLength.coerceIn(0, 256)) { sendSpecialKey(KeyEvent.KEYCODE_DEL) }
                repeat(afterLength.coerceIn(0, 256)) { sendText("\u001b[3~") }
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                val pending = composingText ?: return deleteSurroundingText(beforeLength, afterLength)
                val beforeCodePoints = beforeLength.coerceIn(0, pending.codePointCount(0, composingCursor))
                val afterCodePoints = afterLength.coerceIn(
                    0, pending.codePointCount(composingCursor, pending.length),
                )
                val start = pending.offsetByCodePoints(composingCursor, -beforeCodePoints)
                val end = pending.offsetByCodePoints(composingCursor, afterCodePoints)
                return deleteSurroundingText(composingCursor - start, end - composingCursor)
            }

            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
                composingText?.take(composingCursor)?.takeLast(n.coerceAtLeast(0)).orEmpty()

            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
                composingText?.drop(composingCursor)?.take(n.coerceAtLeast(0)).orEmpty()

            override fun sendKeyEvent(event: KeyEvent): Boolean =
                if (event.action != KeyEvent.ACTION_DOWN) true
                else if (event.keyCode == KeyEvent.KEYCODE_DEL && composingText != null) {
                    deleteSurroundingText(1, 0)
                } else onKeyDown(event.keyCode, event)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        var modifiers = 0
        if (event.isCtrlPressed) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed) modifiers = modifiers or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) modifiers = modifiers or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) modifiers = modifiers or KeyHandler.KEYMOD_NUM_LOCK
        val code = KeyHandler.getCode(
            keyCode, modifiers, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode,
        )
        if (code != null) {
            sendText(code)
            return true
        }
        if (event.isCtrlPressed && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            sendBytes(byteArrayOf((keyCode - KeyEvent.KEYCODE_A + 1).toByte()))
            return true
        }
        val unicode = event.unicodeChar
        if (unicode > 0 && (unicode and KeyCharacterMap.COMBINING_ACCENT) == 0 &&
            Character.isValidCodePoint(unicode)
        ) {
            sendText(String(Character.toChars(unicode)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    internal fun visibleText(): String = emulator.getSelectedText(0, 0, columns - 1, rows - 1)

    private companion object {
        const val MAX_PASTE_CHARS = 1_048_576
    }
}
