package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiTouchClassifierTest {
    private fun classifier() = MultiTouchClassifier(slop = 8f, density = 1f)

    private fun MultiTouchClassifier.down(vararg points: Pair<Float, Float>) =
        pointersDown(points.map { it.first }.toFloatArray(), points.map { it.second }.toFloatArray())

    private fun MultiTouchClassifier.moveTo(vararg points: Pair<Float, Float>, time: Long = 0L) =
        move(points.map { it.first }.toFloatArray(), points.map { it.second }.toFloatArray(), time)

    @Test fun twoFingersMovingUpScrollContentDownWithoutZooming() {
        val c = classifier()
        c.down(100f to 500f, 200f to 500f)
        val actions = (1..20).flatMap { step ->
            // Real fingers drift apart a little while scrolling: 100 px to 120 px over the gesture.
            val y = 500f - step * 10f
            c.moveTo(100f - step * 0.5f to y, 200f + step * 0.5f to y, time = step * 1000L)
        }
        assertEquals(MultiTouchKind.SCROLL, c.kind)
        assertTrue(actions.none { it is MultiTouchAction.Zoom })
        // Slow (10 px per second), so acceleration adds at most a notch or two.
        val vertical = actions.filterIsInstance<MultiTouchAction.Scroll>().sumOf { it.vertical }
        val plain = (200 / MultiTouchClassifier.SCROLL_STEP_DP).toInt()
        assertTrue("$vertical notches", vertical in plain..plain + 2)
    }

    @Test fun aScrollWhoseFingersSpreadAsTheyStartIsStillAScroll() {
        val c = classifier()
        c.down(100f to 500f, 200f to 500f)
        // The first sample past the slop: 12 px of travel while the spacing grew by 10 px.
        c.moveTo(95f to 488f, 205f to 488f, time = 16L)
        assertEquals(MultiTouchKind.SCROLL, c.kind)
    }

    @Test fun fasterSwipesScrollFurther() {
        val slow = classifier().apply { down(100f to 800f, 200f to 800f) }
        val fast = classifier().apply { down(100f to 800f, 200f to 800f) }
        val slowNotches = (1..10).flatMap { slow.moveTo(100f to 800f - it * 30f, 200f to 800f - it * 30f, time = it * 1000L) }
            .filterIsInstance<MultiTouchAction.Scroll>().sumOf { it.vertical }
        val fastNotches = (1..10).flatMap { fast.moveTo(100f to 800f - it * 30f, 200f to 800f - it * 30f, time = it * 16L) }
            .filterIsInstance<MultiTouchAction.Scroll>().sumOf { it.vertical }
        assertTrue("fast $fastNotches vs slow $slowNotches", fastNotches > slowNotches * 2)
    }

    @Test fun spreadingFingersInPlaceZoomsInAndPinchingZoomsOut() {
        val c = classifier()
        c.down(200f to 500f, 300f to 500f)
        val zoomIn = (1..10).flatMap { step -> c.moveTo(200f - step * 10f to 500f, 300f + step * 10f to 500f) }
        assertEquals(MultiTouchKind.PINCH, c.kind)
        // Span ~124 (where the pinch is recognized) -> 300: about five whole 20% steps.
        assertTrue("$zoomIn", zoomIn.size in 4..6 && zoomIn.all { it == MultiTouchAction.Zoom(true) })

        val zoomOut = (1..10).flatMap { step -> c.moveTo(100f + step * 10f to 500f, 400f - step * 10f to 500f) }
        assertTrue(zoomOut.isNotEmpty() && zoomOut.all { it == MultiTouchAction.Zoom(false) })
    }

    @Test fun aSmallSpacingChangeWaitsInsteadOfStartingAPinch() {
        val c = classifier()
        c.down(200f to 500f, 300f to 500f)
        // 16 px of spacing change with no travel is past the slop but not yet a clear pinch.
        c.moveTo(192f to 500f, 308f to 500f)
        assertEquals(MultiTouchKind.UNDECIDED, c.kind)
    }

    @Test fun aQuickScrollKeepsGoingAfterTheFingersLift() {
        val c = classifier()
        c.down(100f to 800f, 200f to 800f)
        // 30 px every 16 ms upwards: about 1.9 px/ms.
        (1..10).forEach { step -> c.moveTo(100f to 800f - step * 30f, 200f to 800f - step * 30f, time = step * 16L) }
        val fling = c.end(176L).single() as MultiTouchAction.Fling
        assertEquals(0f, fling.velocityX, 0.001f)
        assertEquals(-1.875f, fling.velocityY, 0.01f)
    }

    @Test fun momentumSurvivesOneFingerLiftingFirst() {
        val c = classifier()
        c.down(100f to 800f, 200f to 800f)
        (1..10).forEach { step -> c.moveTo(100f to 800f - step * 30f, 200f to 800f - step * 30f, time = step * 16L) }
        // One finger leaves a frame early; the other reports a last move on its own.
        c.moveTo(200f to 480f, time = 176L)
        val fling = c.end(184L).single() as MultiTouchAction.Fling
        assertEquals(-1.875f, fling.velocityY, 0.01f)
    }

    @Test fun restingBeforeLiftingHasNoMomentum() {
        val c = classifier()
        c.down(100f to 800f, 200f to 800f)
        (1..10).forEach { step -> c.moveTo(100f to 800f - step * 30f, 200f to 800f - step * 30f, time = step * 16L) }
        assertEquals(emptyList<MultiTouchAction>(), c.end(160L + MultiTouchClassifier.LIFT_PAUSE_MS + 50L))
    }

    @Test fun aSlowScrollStopsWhenTheFingersLift() {
        val c = classifier()
        c.down(100f to 800f, 200f to 800f)
        // 1 px per 16 ms is under the momentum threshold.
        (1..20).forEach { step -> c.moveTo(100f to 800f - step * 1f, 200f to 800f - step * 1f, time = step * 16L) }
        assertEquals(emptyList<MultiTouchAction>(), c.end(336L))
    }

    @Test fun twoFingerTapIsARightClick() {
        val c = classifier()
        c.down(100f to 100f, 160f to 100f)
        c.moveTo(102f to 101f, 161f to 99f)
        assertEquals(listOf(MultiTouchAction.RightClick), c.end())
    }

    @Test fun threeOrMoreFingersDoNothing() {
        val c = classifier()
        c.down(100f to 300f, 150f to 300f)
        c.down(100f to 300f, 150f to 300f, 200f to 300f)
        assertEquals(MultiTouchKind.IGNORED, c.kind)
        assertEquals(emptyList<MultiTouchAction>(), c.moveTo(150f to 300f, 200f to 300f, 250f to 300f))
        assertEquals(emptyList<MultiTouchAction>(), c.end())
    }
}
