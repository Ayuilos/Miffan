package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiTouchClassifierTest {
    private fun classifier(drag: Boolean = true, swipe: Boolean = true) =
        MultiTouchClassifier(slop = 8f, density = 1f, threeFingerDrag = drag, fourFingerSwipe = swipe)

    private fun MultiTouchClassifier.down(vararg points: Pair<Float, Float>) =
        pointersDown(points.map { it.first }.toFloatArray(), points.map { it.second }.toFloatArray())

    private fun MultiTouchClassifier.moveTo(vararg points: Pair<Float, Float>) =
        move(points.map { it.first }.toFloatArray(), points.map { it.second }.toFloatArray())

    @Test fun twoFingersMovingUpScrollContentDownWithoutZooming() {
        val c = classifier()
        c.down(100f to 500f, 200f to 500f)
        val actions = (1..20).flatMap { step ->
            // Real fingers drift apart a little while scrolling: 100 px to 120 px over the gesture.
            val y = 500f - step * 10f
            c.moveTo(100f - step * 0.5f to y, 200f + step * 0.5f to y)
        }
        assertEquals(MultiTouchKind.SCROLL, c.kind)
        assertTrue(actions.none { it is MultiTouchAction.Zoom })
        val vertical = actions.filterIsInstance<MultiTouchAction.Scroll>().sumOf { it.vertical }
        assertEquals(200 / 32, vertical)
    }

    @Test fun spreadingFingersZoomsInAndPinchingZoomsOut() {
        val c = classifier()
        c.down(200f to 500f, 300f to 500f)
        val zoomIn = (1..10).flatMap { step -> c.moveTo(200f - step * 10f to 500f, 300f + step * 10f to 500f) }
        assertEquals(MultiTouchKind.PINCH, c.kind)
        // Span 100 -> 300: ln(3) / ln(1.2) = 6 whole steps.
        assertEquals(List(6) { MultiTouchAction.Zoom(true) }, zoomIn)
        assertTrue(zoomIn.none { it is MultiTouchAction.Scroll })

        val zoomOut = (1..10).flatMap { step -> c.moveTo(100f + step * 10f to 500f, 400f - step * 10f to 500f) }
        assertTrue(zoomOut.isNotEmpty() && zoomOut.all { it == MultiTouchAction.Zoom(false) })
    }

    @Test fun twoFingerTapIsARightClick() {
        val c = classifier()
        c.down(100f to 100f, 160f to 100f)
        c.moveTo(102f to 101f, 161f to 99f)
        assertEquals(listOf(MultiTouchAction.RightClick), c.end())
    }

    @Test fun scrollingDoesNotEndWithARightClick() {
        val c = classifier()
        c.down(100f to 300f, 160f to 300f)
        c.moveTo(100f to 250f, 160f to 250f)
        assertEquals(emptyList<MultiTouchAction>(), c.end())
    }

    @Test fun threeFingersDragWithTheLeftButtonHeld() {
        val c = classifier()
        c.down(100f to 300f, 150f to 300f)
        c.down(100f to 300f, 150f to 300f, 200f to 300f)
        val actions = c.moveTo(130f to 300f, 180f to 300f, 230f to 300f)
        assertEquals(MultiTouchAction.DragStart, actions.first())
        assertEquals(MultiTouchAction.DragMove(30f, 0f), actions.last())
        assertEquals(listOf(MultiTouchAction.DragEnd), c.end())
    }

    @Test fun threeFingersDoNothingWithoutTouchpadDrag() {
        val c = classifier(drag = false)
        c.down(100f to 300f, 150f to 300f, 200f to 300f)
        assertEquals(emptyList<MultiTouchAction>(), c.moveTo(150f to 300f, 200f to 300f, 250f to 300f))
        assertEquals(emptyList<MultiTouchAction>(), c.end())
    }

    @Test fun liftingOneFingerDuringADragDoesNotJumpThePointer() {
        val c = classifier()
        c.down(100f to 300f, 150f to 300f, 200f to 300f)
        c.moveTo(120f to 300f, 170f to 300f, 220f to 300f)
        // The third finger lifts: the centroid moves from x=170 to x=145 without any finger moving.
        assertEquals(emptyList<MultiTouchAction>(), c.moveTo(120f to 300f, 170f to 300f))
        assertEquals(listOf(MultiTouchAction.DragMove(10f, 0f)), c.moveTo(130f to 300f, 180f to 300f))
    }

    @Test fun fourFingerSwipeFiresOnceInItsDirection() {
        val c = classifier()
        c.down(100f to 300f, 150f to 300f, 200f to 300f, 250f to 300f)
        val actions = (1..10).flatMap { step ->
            val dx = -step * 10f
            c.moveTo(100f + dx to 300f, 150f + dx to 300f, 200f + dx to 300f, 250f + dx to 300f)
        }
        assertEquals(listOf(MultiTouchAction.Swipe(SwipeDirection.LEFT)), actions)
    }

    @Test fun fourFingersDoNothingWhenSwipesAreOff() {
        val c = classifier(swipe = false)
        c.down(100f to 300f, 150f to 300f, 200f to 300f, 250f to 300f)
        assertEquals(emptyList<MultiTouchAction>(), c.moveTo(100f to 200f, 150f to 200f, 200f to 200f, 250f to 200f))
    }
}
