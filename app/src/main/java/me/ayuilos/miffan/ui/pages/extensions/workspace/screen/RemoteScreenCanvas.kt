package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import android.content.Context
import android.graphics.Bitmap
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.workspace.screen.RfbKeys
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun RemoteScreenCanvas(
    bitmap: Bitmap,
    frameVersion: State<Long>,
    cursor: State<RemoteCursor>,
    trackpad: Boolean,
    macOS: Boolean,
    vm: RemoteScreenVM,
    modifier: Modifier = Modifier,
    zoomControls: Boolean = true,
) {
    var macExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val scope = rememberCoroutineScope()
    val input = remember(bitmap.width, bitmap.height, trackpad, vm) {
        ScreenGestures(context, bitmap.width, bitmap.height, trackpad, vm, scope)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(input, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) input.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); input.cancel() }
    }
    Box(modifier) {
    Canvas(Modifier.fillMaxSize().onSizeChanged { input.resize(Size(it.width.toFloat(), it.height.toFloat())) }
        // Touching the picture folds the desktop shortcuts away, without taking the touch.
        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); macExpanded = false } }
        .pointerInteropFilter { input.event(it) }) {
        // The VM mutates the same Bitmap. Reading this State here invalidates drawing each frame
        // without recomposing the page or allocating another framebuffer.
        frameVersion.value
        vm.framesDrawn.incrementAndGet()
        val topLeft = input.topLeft
        val scale = input.scale
        drawImage(image, dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
            dstSize = IntSize(max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt())),
            filterQuality = FilterQuality.Medium)
        if (trackpad) {
            val tip = input.toViewport(input.pointer)
            when (val shape = cursor.value) {
                RemoteCursor.Unknown -> drawArrowCursor(tip)
                RemoteCursor.Hidden -> Unit
                is RemoteCursor.Shape -> {
                    // One cursor pixel per dp on a normal screen, two on Retina.
                    val unit = 1.dp.toPx() / shape.scale
                    drawImage(
                        shape.bitmap.asImageBitmap(),
                        dstOffset = IntOffset((tip.x - shape.hotspotX * unit).roundToInt(), (tip.y - shape.hotspotY * unit).roundToInt()),
                        dstSize = IntSize((shape.bitmap.width * unit).roundToInt().coerceAtLeast(1), (shape.bitmap.height * unit).roundToInt().coerceAtLeast(1)),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
        }
    }
    // Pinching is the computer's own zoom, so enlarging the picture on the phone has buttons.
    if (zoomControls) RemoteScreenControls(
        canZoomOut = input.zoomed,
        onZoomIn = { input.zoomStep(zoomIn = true) },
        onZoomOut = { input.zoomStep(zoomIn = false) },
        onMacShortcut = if (macOS) { shortcut ->
            vm.key(when (shortcut) {
                MacShortcut.DESKTOP_LEFT -> RfbKeys.LEFT
                MacShortcut.DESKTOP_RIGHT -> RfbKeys.RIGHT
                MacShortcut.MISSION_CONTROL -> RfbKeys.UP
                MacShortcut.APP_WINDOWS -> RfbKeys.DOWN
            }, setOf(RemoteModifier.CONTROL))
        } else null,
        macExpanded = macExpanded,
        onMacExpandedChange = { macExpanded = it },
        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
    )
    }
}

/**
 * A desktop arrow pointer whose tip is the click point, at a fixed on-screen size regardless of
 * zoom: black with a white outline, like the macOS cursor.
 */
private fun DrawScope.drawArrowCursor(tip: Offset) {
    val unit = 1.2.dp.toPx()
    val arrow = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x, tip.y + 17 * unit)
        lineTo(tip.x + 4 * unit, tip.y + 13 * unit)
        lineTo(tip.x + 7 * unit, tip.y + 20 * unit)
        lineTo(tip.x + 9.5f * unit, tip.y + 19 * unit)
        lineTo(tip.x + 6.5f * unit, tip.y + 12 * unit)
        lineTo(tip.x + 12 * unit, tip.y + 12 * unit)
        close()
    }
    drawPath(arrow, Color.Black.copy(alpha = 0.25f), style = Stroke(width = 4 * unit, join = StrokeJoin.Round))
    drawPath(arrow, Color.White, style = Stroke(width = 2.5f * unit, join = StrokeJoin.Round))
    drawPath(arrow, Color.Black)
}

/**
 * Android's tap timing for one finger; [MultiTouchClassifier] decides what two or more fingers
 * mean. One owner always releases drags.
 */
private class ScreenGestures(
    context: Context,
    private val width: Int,
    private val height: Int,
    private val trackpad: Boolean,
    private val vm: RemoteScreenVM,
    private val scope: CoroutineScope,
) {
    private var fling: Job? = null
    private var viewport by mutableStateOf(Size.Zero)
    private var zoom by mutableFloatStateOf(1f)
    private var pan by mutableStateOf(Offset.Zero)
    var pointer by mutableStateOf(Offset(width / 2f, height / 2f))
        private set
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val density = context.resources.displayMetrics.density
    private var down = Offset.Zero
    private var last = Offset.Zero
    private var longPressed = false
    private var doublePressed = false
    private var dragging = false
    private var moved = false
    private var multi = false
    /** The first finger had already moved when the others landed, so this is not a two-finger tap. */
    private var movedBeforeMulti = false
    private var multiCenter = Offset.Zero
    private var classifier: MultiTouchClassifier? = null

    val scale: Float get() = if (viewport == Size.Zero) 1f else min(viewport.width / width, viewport.height / height) * zoom
    val topLeft: Offset get() = Offset((viewport.width - width * scale) / 2f, (viewport.height - height * scale) / 2f) + pan
    val zoomed: Boolean get() = zoom > 1f
    fun toViewport(point: Offset): Offset = topLeft + point * scale
    private fun toBitmap(point: Offset): Offset = (point - topLeft) / scale
    private fun inside(point: Offset): Boolean = point.x in 0f..width.toFloat() && point.y in 0f..height.toFloat()
    private fun clampPoint(point: Offset) = Offset(point.x.coerceIn(0f, (width - 1).toFloat()), point.y.coerceIn(0f, (height - 1).toFloat()))
    private fun clampPan() {
        val x = max(0f, (width * scale - viewport.width) / 2f)
        val y = max(0f, (height * scale - viewport.height) / 2f)
        pan = Offset(pan.x.coerceIn(-x, x), pan.y.coerceIn(-y, y))
    }
    fun resize(size: Size) { viewport = size; clampPan() }
    private fun click(button: RemoteMouseButton, count: Int = 1, at: Offset = last) {
        val point = if (trackpad) pointer else toBitmap(at)
        if (trackpad || inside(point)) vm.click(point.x, point.y, button, count)
    }
    private fun move(point: Offset) {
        pointer = clampPoint(point)
        vm.movePointer(pointer.x, pointer.y)
    }
    /** Moves the touchpad pointer by a finger movement, faster for quick swipes like a trackpad. */
    private fun movePointerBy(delta: Offset) {
        val speed = delta.getDistance() / max(1f, density)
        val acceleration = (1f + speed / 24f).coerceAtMost(3f)
        move(pointer + delta / scale * acceleration)
        followPointer()
    }
    private fun followPointer() {
        val point = toViewport(pointer)
        val margin = min(32f * density, min(viewport.width, viewport.height) / 4f)
        pan += Offset(
            when { point.x < margin -> margin - point.x; point.x > viewport.width - margin -> viewport.width - margin - point.x; else -> 0f },
            when { point.y < margin -> margin - point.y; point.y > viewport.height - margin -> viewport.height - margin - point.y; else -> 0f },
        )
        clampPan()
    }
    private fun zoomBy(factor: Float, anchor: Offset) {
        val pixel = toBitmap(anchor)
        zoom = (zoom * factor).coerceIn(1f, MAX_VIEW_ZOOM)
        pan += anchor - toViewport(pixel)
        clampPan()
        if (trackpad) followPointer()
    }
    /** The zoom buttons: around the pointer on a touchpad, around the middle of the view otherwise. */
    fun zoomStep(zoomIn: Boolean) {
        val anchor = if (trackpad) toViewport(pointer) else Offset(viewport.width / 2f, viewport.height / 2f)
        zoomBy(if (zoomIn) VIEW_ZOOM_STEP else 1f / VIEW_ZOOM_STEP, anchor)
    }
    fun release() {
        if (dragging) vm.press(RemoteMouseButton.LEFT, false)
        dragging = false
    }

    /** Momentum after a two-finger scroll, like a trackpad: keep scrolling and slow down. */
    private fun startFling(velocityX: Float, velocityY: Float) {
        fling?.cancel()
        fling = scope.launch {
            val step = MultiTouchClassifier.SCROLL_STEP_DP * density
            var vx = velocityX
            var vy = velocityY
            var wheelX = 0f
            var wheelY = 0f
            while (hypot(vx, vy) > FLING_STOP_DP_PER_MS * density) {
                delay(FLING_FRAME_MS)
                // Fingers moving up scroll content down, with the live scroll's sign and gain.
                val gain = MultiTouchClassifier.acceleration(hypot(vx, vy), density)
                wheelX -= vx * FLING_FRAME_MS * gain
                wheelY -= vy * FLING_FRAME_MS * gain
                val vertical = (wheelY / step).toInt().coerceIn(-MAX_FLING_NOTCHES, MAX_FLING_NOTCHES)
                val horizontal = (wheelX / step).toInt().coerceIn(-MAX_FLING_NOTCHES, MAX_FLING_NOTCHES)
                if (vertical != 0) vm.scroll(vertical)
                if (horizontal != 0) vm.scroll(horizontal, horizontal = true)
                wheelX -= horizontal * step
                wheelY -= vertical * step
                vx *= FLING_DECAY
                vy *= FLING_DECAY
            }
        }
    }

    fun cancel() {
        fling?.cancel()
        release()
        classifier = null
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, time, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        detector.onTouchEvent(event)
        event.recycle()
    }

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!multi) click(RemoteMouseButton.LEFT, at = Offset(e.x, e.y))
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { doublePressed = true; return true }
        override fun onDoubleTapEvent(e: MotionEvent): Boolean = true
        override fun onLongPress(e: MotionEvent) { if (!multi && !doublePressed) longPressed = true }
    })

    private fun perform(actions: List<MultiTouchAction>) = actions.forEach { action ->
        when (action) {
            is MultiTouchAction.Scroll -> {
                if (action.vertical != 0) vm.scroll(action.vertical)
                if (action.horizontal != 0) vm.scroll(action.horizontal, horizontal = true)
            }
            // The keyboard shortcut apps use for zoom: Command on a Mac, Ctrl elsewhere.
            is MultiTouchAction.Zoom -> vm.key(if (action.zoomIn) KEY_EQUAL else KEY_MINUS, setOf(RemoteModifier.COMMAND))
            MultiTouchAction.RightClick -> if (!movedBeforeMulti) click(RemoteMouseButton.RIGHT, at = multiCenter)
            is MultiTouchAction.Fling -> startFling(action.velocityX, action.velocityY)
        }
    }

    fun event(e: MotionEvent): Boolean {
        val point = Offset(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Touching the glass stops momentum, as on a trackpad.
                fling?.cancel()
                vm.takeControl()
                release()
                down = point
                last = point
                longPressed = false
                doublePressed = false
                moved = false
                multi = false
                classifier = null
                detector.onTouchEvent(e)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                release()
                if (!multi) {
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    detector.onTouchEvent(cancel)
                    cancel.recycle()
                    multi = true
                    movedBeforeMulti = moved
                    multiCenter = center(e)
                    classifier = MultiTouchClassifier(slop, density)
                    // Scroll and right-click act where the fingers are, unless a pointer is shown.
                    if (trackpad) vm.movePointer(pointer.x, pointer.y)
                    else {
                        val target = toBitmap(multiCenter)
                        if (inside(target)) vm.movePointer(target.x, target.y)
                    }
                }
                classifier?.pointersDown(xs(e), ys(e), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    classifier?.let { perform(it.move(xs(e), ys(e), e.eventTime)) }
                } else {
                    val delta = point - last
                    if ((point - down).getDistance() > slop) moved = true
                    if (moved && (longPressed && !trackpad && inside(toBitmap(down)) || doublePressed && trackpad)) {
                        if (!dragging) {
                            if (!trackpad) move(toBitmap(down))
                            vm.press(RemoteMouseButton.LEFT, true)
                            dragging = true
                        }
                    }
                    if (moved) {
                        if (trackpad) movePointerBy(delta)
                        else if (dragging) move(toBitmap(point))
                        else if (!longPressed && zoom > 1f) { pan += delta; clampPan() }
                    }
                    detector.onTouchEvent(e)
                }
                last = point
            }
            MotionEvent.ACTION_POINTER_UP -> Unit // The classifier rebases on the next move; wait for all fingers.
            MotionEvent.ACTION_UP -> {
                if (multi) {
                    classifier?.let { perform(it.end(e.eventTime)) }
                    classifier = null
                    release()
                } else {
                    when {
                        dragging -> release()
                        doublePressed -> if (!moved) click(RemoteMouseButton.LEFT, count = 2, at = point)
                        longPressed -> if (!moved) click(RemoteMouseButton.RIGHT, at = point)
                    }
                    detector.onTouchEvent(e)
                }
            }
            MotionEvent.ACTION_CANCEL -> { release(); classifier = null; detector.onTouchEvent(e) }
        }
        return true
    }

    private fun center(e: MotionEvent): Offset = Offset(xs(e).average().toFloat(), ys(e).average().toFloat())
    private fun xs(e: MotionEvent) = FloatArray(e.pointerCount) { e.getX(it) }
    private fun ys(e: MotionEvent) = FloatArray(e.pointerCount) { e.getY(it) }

    companion object {
        const val MAX_VIEW_ZOOM = 5f
        const val FLING_FRAME_MS = 16L
        /** Per-frame velocity kept: a quick flick glides for well over a second, like a trackpad. */
        const val FLING_DECAY = 0.975f
        const val FLING_STOP_DP_PER_MS = 0.01f
        /** Keeps one frame from flooding the connection with wheel events. */
        const val MAX_FLING_NOTCHES = 40
        const val VIEW_ZOOM_STEP = 1.5f
        const val KEY_EQUAL = 0x003D
        const val KEY_MINUS = 0x002D
    }
}
