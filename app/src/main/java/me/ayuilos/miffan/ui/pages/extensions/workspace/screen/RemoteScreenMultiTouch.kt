package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/** A gesture made with two or more fingers, decided once and kept until every finger lifts. */
internal enum class MultiTouchKind { UNDECIDED, SCROLL, PINCH, DRAG, SWIPE, IGNORED }

internal enum class SwipeDirection { LEFT, RIGHT, UP, DOWN }

internal sealed interface MultiTouchAction {
    /** Wheel notches; positive [vertical] scrolls content down (fingers moved up). */
    data class Scroll(val vertical: Int, val horizontal: Int) : MultiTouchAction
    /** One app zoom step on the computer, like a pinch on a Mac trackpad. */
    data class Zoom(val zoomIn: Boolean) : MultiTouchAction
    data object RightClick : MultiTouchAction
    /** Three-finger drag: the left button is held while the fingers move by ([dx], [dy]) px. */
    data object DragStart : MultiTouchAction
    data class DragMove(val dx: Float, val dy: Float) : MultiTouchAction
    data object DragEnd : MultiTouchAction
    /** Four-finger swipe, in the direction the fingers moved. */
    data class Swipe(val direction: SwipeDirection) : MultiTouchAction
}

/**
 * Turns finger positions into trackpad-style actions without touching Android input types, so the
 * rules can be tested on the JVM. Two fingers either scroll or pinch, whichever moves first past
 * the touch slop; a pinch never starts in the middle of a scroll. Three fingers drag (touchpad
 * mode only) and four fingers swipe between desktops (Mac only).
 */
internal class MultiTouchClassifier(
    private val slop: Float,
    private val density: Float,
    private val threeFingerDrag: Boolean,
    private val fourFingerSwipe: Boolean,
) {
    var kind = MultiTouchKind.UNDECIDED
        private set
    private var fingers = 0
    private var count = 0
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startSpan = 0f
    private var zoomBase = 0f
    private var wheelX = 0f
    private var wheelY = 0f
    private var moved = false

    /** Every finger is down: [xs]/[ys] are the current positions. Call again when more land. */
    fun pointersDown(xs: FloatArray, ys: FloatArray): List<MultiTouchAction> {
        val actions = mutableListOf<MultiTouchAction>()
        if (xs.size > fingers) {
            // More fingers turn the gesture into another one; end a drag the old count started.
            if (kind == MultiTouchKind.DRAG) actions += MultiTouchAction.DragEnd
            fingers = xs.size
            kind = MultiTouchKind.UNDECIDED
            moved = false
            wheelX = 0f
            wheelY = 0f
        }
        rebase(xs, ys)
        return actions
    }

    fun move(xs: FloatArray, ys: FloatArray): List<MultiTouchAction> {
        // A lifted finger shifts the centroid; continue from the new one instead of jumping.
        if (xs.size != count) { rebase(xs, ys, keepStart = true); return emptyList() }
        // The last finger left behind does nothing until it lifts too.
        if (xs.size < 2) return emptyList()
        val x = xs.average().toFloat()
        val y = ys.average().toFloat()
        val translation = hypot(x - startX, y - startY)
        val actions = mutableListOf<MultiTouchAction>()
        if (kind == MultiTouchKind.UNDECIDED) {
            val spanChange = if (fingers == 2) abs(span(xs, ys) - startSpan) else 0f
            if (translation > slop || spanChange > slop) moved = true
            kind = when {
                !moved -> MultiTouchKind.UNDECIDED
                fingers == 2 -> if (spanChange > translation) MultiTouchKind.PINCH else MultiTouchKind.SCROLL
                fingers == 3 && threeFingerDrag -> MultiTouchKind.DRAG.also { actions += MultiTouchAction.DragStart }
                fingers >= 4 && fourFingerSwipe -> MultiTouchKind.SWIPE
                else -> MultiTouchKind.IGNORED
            }
            if (kind == MultiTouchKind.PINCH) zoomBase = startSpan
        }
        when (kind) {
            MultiTouchKind.SCROLL -> {
                wheelX -= x - lastX
                wheelY -= y - lastY
                val step = SCROLL_STEP_DP * density
                val vertical = (wheelY / step).toInt()
                val horizontal = (wheelX / step).toInt()
                if (vertical != 0 || horizontal != 0) actions += MultiTouchAction.Scroll(vertical, horizontal)
                wheelX -= horizontal * step
                wheelY -= vertical * step
            }
            MultiTouchKind.PINCH -> {
                val current = span(xs, ys)
                if (zoomBase > 0f && current > 0f) {
                    var ratio = ln(current / zoomBase)
                    while (abs(ratio) >= ZOOM_STEP_LOG) {
                        val zoomIn = ratio > 0
                        actions += MultiTouchAction.Zoom(zoomIn)
                        zoomBase *= if (zoomIn) ZOOM_STEP else 1f / ZOOM_STEP
                        ratio = ln(current / zoomBase)
                    }
                }
            }
            MultiTouchKind.DRAG -> if (x != lastX || y != lastY) actions += MultiTouchAction.DragMove(x - lastX, y - lastY)
            MultiTouchKind.SWIPE -> if (translation > SWIPE_DISTANCE_DP * density) {
                val dx = x - startX
                val dy = y - startY
                actions += MultiTouchAction.Swipe(
                    if (abs(dx) >= abs(dy)) { if (dx < 0) SwipeDirection.LEFT else SwipeDirection.RIGHT }
                    else if (dy < 0) SwipeDirection.UP else SwipeDirection.DOWN
                )
                kind = MultiTouchKind.IGNORED
            }
            else -> Unit
        }
        lastX = x
        lastY = y
        return actions
    }

    /** All fingers lifted. */
    fun end(): List<MultiTouchAction> = when {
        kind == MultiTouchKind.DRAG -> listOf(MultiTouchAction.DragEnd)
        fingers == 2 && !moved -> listOf(MultiTouchAction.RightClick)
        else -> emptyList()
    }

    private fun rebase(xs: FloatArray, ys: FloatArray, keepStart: Boolean = false) {
        count = xs.size
        lastX = xs.average().toFloat()
        lastY = ys.average().toFloat()
        if (!keepStart) {
            startX = lastX
            startY = lastY
            startSpan = span(xs, ys)
        } else if (kind == MultiTouchKind.PINCH) zoomBase = span(xs, ys)
    }

    private fun span(xs: FloatArray, ys: FloatArray): Float =
        if (xs.size < 2) 0f else hypot(xs[1] - xs[0], ys[1] - ys[0])

    companion object {
        const val SCROLL_STEP_DP = 32f
        /** Each 20% change in finger distance sends one zoom step. */
        const val ZOOM_STEP = 1.2f
        private val ZOOM_STEP_LOG = ln(ZOOM_STEP)
        const val SWIPE_DISTANCE_DP = 48f
    }
}
