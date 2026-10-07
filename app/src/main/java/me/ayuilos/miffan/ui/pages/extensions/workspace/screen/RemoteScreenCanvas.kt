package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import android.content.Context
import android.graphics.Bitmap
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun RemoteScreenCanvas(
    bitmap: Bitmap,
    frameVersion: State<Long>,
    trackpad: Boolean,
    vm: RemoteScreenVM,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val input = remember(bitmap.width, bitmap.height, trackpad, vm) {
        ScreenGestures(context, bitmap.width, bitmap.height, trackpad, vm)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(input, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) input.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); input.cancel() }
    }
    Canvas(modifier.onSizeChanged { input.resize(Size(it.width.toFloat(), it.height.toFloat())) }
        .pointerInteropFilter { input.event(it) }) {
        // The VM mutates the same Bitmap. Reading this State here invalidates drawing each frame
        // without recomposing the page or allocating another framebuffer.
        frameVersion.value
        val topLeft = input.topLeft
        val scale = input.scale
        drawImage(image, dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
            dstSize = IntSize(max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt())),
            filterQuality = FilterQuality.Medium)
        if (trackpad) {
            val point = input.toViewport(input.pointer)
            drawCircle(Color.Black, radius = 9.dp.toPx(), center = point)
            drawCircle(Color.White, radius = 6.dp.toPx(), center = point)
            drawLine(Color.Black, point - Offset(4.dp.toPx(), 0f), point + Offset(4.dp.toPx(), 0f), 1.dp.toPx())
            drawLine(Color.Black, point - Offset(0f, 4.dp.toPx()), point + Offset(0f, 4.dp.toPx()), 1.dp.toPx())
        }
    }
}

/** Android's tap timing plus an explicit multi-touch phase; one owner always releases drags. */
private class ScreenGestures(
    context: Context,
    private val width: Int,
    private val height: Int,
    private val trackpad: Boolean,
    private val vm: RemoteScreenVM,
) {
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
    private var multiMoved = false
    private var pinching = false
    private var multiStart = Offset.Zero
    private var centroid = Offset.Zero
    private var initialSpan = 0f
    private var previousSpan = 0f
    private var wheel = Offset.Zero

    val scale: Float get() = if (viewport == Size.Zero) 1f else min(viewport.width / width, viewport.height / height) * zoom
    val topLeft: Offset get() = Offset((viewport.width - width * scale) / 2f, (viewport.height - height * scale) / 2f) + pan
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
        zoom = (zoom * factor).coerceIn(1f, 5f)
        pan += anchor - toViewport(pixel)
        clampPan()
        if (trackpad) followPointer()
    }
    fun release() {
        if (dragging) vm.press(RemoteMouseButton.LEFT, false)
        dragging = false
    }

    fun cancel() {
        release()
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

    fun event(e: MotionEvent): Boolean {
        val point = Offset(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                release()
                down = point
                last = point
                longPressed = false
                doublePressed = false
                moved = false
                multi = false
                detector.onTouchEvent(e)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                release()
                if (!multi) {
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    detector.onTouchEvent(cancel)
                    cancel.recycle()
                    multi = true
                    multiMoved = moved
                    pinching = false
                    wheel = Offset.Zero
                    multiStart = center(e)
                    centroid = multiStart
                    if (trackpad) vm.movePointer(pointer.x, pointer.y)
                    else {
                        val target = toBitmap(centroid)
                        if (inside(target)) vm.movePointer(target.x, target.y)
                    }
                    initialSpan = span(e)
                    previousSpan = initialSpan
                } else multiMoved = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    if (e.pointerCount == 2) {
                        val current = center(e)
                        val distance = span(e)
                        if ((current - multiStart).getDistance() > slop || abs(distance - initialSpan) > slop) multiMoved = true
                        if (!pinching && initialSpan > 0f && abs(distance / initialSpan - 1f) > 0.035f && abs(distance - initialSpan) > slop) {
                            pinching = true
                            zoomBy(distance / initialSpan, if (trackpad) toViewport(pointer) else current)
                            wheel = Offset.Zero
                        } else if (pinching && previousSpan > 0f) {
                            zoomBy(distance / previousSpan, if (trackpad) toViewport(pointer) else current)
                        } else if (multiMoved) {
                            // Moving fingers up scrolls content down, matching phone scrolling.
                            wheel -= current - centroid
                            val step = 32f * density
                            val vertical = (wheel.y / step).toInt()
                            val horizontal = (wheel.x / step).toInt()
                            if (vertical != 0) vm.scroll(vertical)
                            if (horizontal != 0) vm.scroll(horizontal, horizontal = true)
                            wheel -= Offset(horizontal * step, vertical * step)
                        }
                        centroid = current
                        previousSpan = distance
                    }
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
                        if (trackpad) {
                            val speed = delta.getDistance() / max(1f, density)
                            val acceleration = (1f + speed / 24f).coerceAtMost(3f)
                            move(pointer + delta / scale * acceleration)
                            followPointer()
                        } else if (dragging) move(toBitmap(point))
                        else if (!longPressed && zoom > 1f) { pan += delta; clampPan() }
                    }
                    detector.onTouchEvent(e)
                }
                last = point
            }
            MotionEvent.ACTION_POINTER_UP -> Unit // Wait for all fingers before accepting a new gesture.
            MotionEvent.ACTION_UP -> {
                if (multi) {
                    if (trackpad && !multiMoved) click(RemoteMouseButton.RIGHT)
                } else {
                    when {
                        dragging -> release()
                        doublePressed -> if (!moved) click(RemoteMouseButton.LEFT, count = 2, at = point)
                        longPressed -> if (!moved) click(RemoteMouseButton.RIGHT, at = point)
                    }
                    detector.onTouchEvent(e)
                }
            }
            MotionEvent.ACTION_CANCEL -> { release(); detector.onTouchEvent(e) }
        }
        return true
    }

    private fun center(e: MotionEvent): Offset = Offset((e.getX(0) + e.getX(1)) / 2f, (e.getY(0) + e.getY(1)) / 2f)
    private fun span(e: MotionEvent): Float = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
}
