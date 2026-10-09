package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/** A two-finger gesture, decided once and kept until every finger lifts. */
internal enum class MultiTouchKind { UNDECIDED, SCROLL, PINCH, IGNORED }

internal sealed interface MultiTouchAction {
    /** Wheel notches; positive [vertical] scrolls content down (fingers moved up). */
    data class Scroll(val vertical: Int, val horizontal: Int) : MultiTouchAction
    /** One app zoom step on the computer, like a pinch on a Mac trackpad. */
    data class Zoom(val zoomIn: Boolean) : MultiTouchAction
    data object RightClick : MultiTouchAction
    /** The fingers left the glass while scrolling; keep scrolling at this finger velocity (px/ms). */
    data class Fling(val velocityX: Float, val velocityY: Float) : MultiTouchAction
}

/**
 * Turns two-finger positions into trackpad-style actions without touching Android input types, so
 * the rules can be tested on the JVM. Scrolling is what people mean most of the time: fingers that
 * travel together scroll at once, and only a clear change in finger distance with little travel
 * becomes a pinch. Three or more fingers do nothing; phone systems often claim those gestures.
 */
internal class MultiTouchClassifier(
    private val slop: Float,
    private val density: Float,
) {
    var kind = MultiTouchKind.UNDECIDED
        private set
    private var fingers = 0
    private var count = 0
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private var startSpan = 0f
    private var zoomBase = 0f
    private var wheelX = 0f
    private var wheelY = 0f
    private var moved = false
    /** Recent centroid samples (time ms, x, y) for the scroll's lift-off velocity. */
    private val samples = ArrayDeque<Triple<Long, Float, Float>>()

    /** Every finger is down: [xs]/[ys] are the current positions. Call again when more land. */
    fun pointersDown(xs: FloatArray, ys: FloatArray, timeMillis: Long = 0L) {
        if (xs.size > fingers) {
            fingers = xs.size
            kind = if (fingers > 2) MultiTouchKind.IGNORED else MultiTouchKind.UNDECIDED
            moved = false
            wheelX = 0f
            wheelY = 0f
            samples.clear()
        }
        rebase(xs, ys, timeMillis)
    }

    fun move(xs: FloatArray, ys: FloatArray, timeMillis: Long = 0L): List<MultiTouchAction> {
        // A lifted finger shifts the centroid; continue from the new one instead of jumping. The
        // velocity measured with both fingers down is kept: fingers rarely lift in the same frame.
        if (xs.size != count) { rebase(xs, ys, timeMillis, keepStart = true); return emptyList() }
        // The last finger left behind does nothing until it lifts too.
        if (xs.size < 2 || kind == MultiTouchKind.IGNORED) return emptyList()
        val x = xs.average().toFloat()
        val y = ys.average().toFloat()
        val actions = mutableListOf<MultiTouchAction>()
        if (kind == MultiTouchKind.UNDECIDED) {
            val translation = hypot(x - startX, y - startY)
            val spanChange = abs(span(xs, ys) - startSpan)
            if (translation > slop || spanChange > slop) moved = true
            kind = when {
                translation > slop && translation * PINCH_DOMINANCE >= spanChange -> MultiTouchKind.SCROLL
                spanChange > slop * PINCH_SLOP_FACTOR && spanChange > translation * PINCH_DOMINANCE -> MultiTouchKind.PINCH
                else -> MultiTouchKind.UNDECIDED
            }
            if (kind == MultiTouchKind.PINCH) zoomBase = span(xs, ys)
        }
        when (kind) {
            MultiTouchKind.SCROLL -> {
                samples.addLast(Triple(timeMillis, x, y))
                while (samples.size > 2 && timeMillis - samples.first().first > VELOCITY_WINDOW_MS) samples.removeFirst()
                // Faster swipes travel further per finger distance, as macOS scroll acceleration does.
                val elapsed = (timeMillis - lastTime).coerceAtLeast(1L)
                val acceleration = acceleration(hypot(x - lastX, y - lastY) / elapsed, density)
                wheelX -= (x - lastX) * acceleration
                wheelY -= (y - lastY) * acceleration
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
            else -> Unit
        }
        lastX = x
        lastY = y
        lastTime = timeMillis
        return actions
    }

    /** All fingers lifted at [timeMillis]. */
    fun end(timeMillis: Long = 0L): List<MultiTouchAction> = when {
        kind == MultiTouchKind.SCROLL -> listOfNotNull(fling(timeMillis))
        fingers == 2 && !moved -> listOf(MultiTouchAction.RightClick)
        else -> emptyList()
    }

    /** Momentum only when the fingers were still moving as they lifted, as on a trackpad. */
    private fun fling(liftMillis: Long): MultiTouchAction.Fling? {
        if (samples.size < 2 || liftMillis - samples.last().first > LIFT_PAUSE_MS) return null
        val (t0, x0, y0) = samples.first()
        val (t1, x1, y1) = samples.last()
        val elapsed = (t1 - t0).coerceAtLeast(1L).toFloat()
        val vx = (x1 - x0) / elapsed
        val vy = (y1 - y0) / elapsed
        return if (hypot(vx, vy) >= FLING_MIN_DP_PER_MS * density) MultiTouchAction.Fling(vx, vy) else null
    }

    private fun rebase(xs: FloatArray, ys: FloatArray, timeMillis: Long, keepStart: Boolean = false) {
        count = xs.size
        lastX = xs.average().toFloat()
        lastY = ys.average().toFloat()
        lastTime = timeMillis
        if (!keepStart) {
            startX = lastX
            startY = lastY
            startSpan = span(xs, ys)
        } else if (kind == MultiTouchKind.PINCH) zoomBase = span(xs, ys)
    }

    private fun span(xs: FloatArray, ys: FloatArray): Float =
        if (xs.size < 2) 0f else hypot(xs[1] - xs[0], ys[1] - ys[0])

    companion object {
        /** One wheel notch per this much finger travel; a Mac scrolls only a few points per notch. */
        const val SCROLL_STEP_DP = 2f
        const val ACCELERATION_SPEED_DP_PER_MS = 0.8f
        const val MAX_ACCELERATION = 3f
        /** A pinch needs its distance change to be this many times the fingers' shared travel. */
        const val PINCH_DOMINANCE = 2f
        const val PINCH_SLOP_FACTOR = 3f
        /** Each 20% change in finger distance sends one zoom step. */
        const val ZOOM_STEP = 1.2f
        private val ZOOM_STEP_LOG = ln(ZOOM_STEP)
        const val VELOCITY_WINDOW_MS = 100L
        const val FLING_MIN_DP_PER_MS = 0.1f
        /** Fingers that rested this long before lifting meant to stop. */
        const val LIFT_PAUSE_MS = 80L

        /** Scroll gain for a finger speed in px/ms; momentum uses it too, so a flick keeps its pace. */
        fun acceleration(speedPxPerMs: Float, density: Float): Float =
            (1f + speedPxPerMs / density / ACCELERATION_SPEED_DP_PER_MS).coerceAtMost(MAX_ACCELERATION)
    }
}
