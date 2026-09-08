package me.ayuilos.miffan.ui.components.ui

import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.MiffanMotionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleGirlTimelineTest {
    @Test
    fun loopingClipsWrapAtTheAuthoredDurationWithoutAccumulatingDrift() {
        for (clip in WhaleGirlClip.entries.filter { it.looping }) {
            val timeline = WhaleGirlTimeline(clip)
            timeline.advance(3_999_999_999L)
            assertEquals(119, timeline.frameIndex)
            timeline.advance(1)
            assertEquals(0, timeline.frameIndex)
            timeline.advance(4_000_000_000L * 100 + 500_000_000L)
            assertEquals(15, timeline.frameIndex)
            assertFalse(timeline.finished)
        }
    }

    @Test
    fun oneShotsClampEvenAfterALargeClockDelta() {
        for (clip in WhaleGirlClip.entries.filterNot { it.looping }) {
            val timeline = WhaleGirlTimeline(clip)
            timeline.advance(750_000_000)
            assertEquals(22, timeline.frameIndex)
            timeline.advance(Long.MAX_VALUE)
            assertEquals(44, timeline.frameIndex)
            assertTrue(timeline.finished)
            timeline.advance(5_000_000_000)
            assertEquals(44, timeline.frameIndex)
        }
    }

    @Test
    fun aNewClipGetsItsOwnOriginAndNonPositiveDeltasCannotRewindIt() {
        val idle = WhaleGirlTimeline(WhaleGirlClip.IDLE)
        idle.advance(3_000_000_000)
        val happy = WhaleGirlTimeline(WhaleGirlClip.SUCCESS)
        assertEquals(0, happy.frameIndex)
        happy.advance(500_000_000)
        happy.advance(-2_000_000_000)
        happy.advance(0)
        assertEquals(15, happy.frameIndex)
    }

    @Test
    fun thirtyFpsAdvancesEverySecondSixtyHzVsyncWithoutSkippingFrames() {
        for (clip in WhaleGirlClip.entries) {
            val timeline = WhaleGirlTimeline(clip)
            var previousNanos = 0L
            val displayedFrames = mutableSetOf(0)
            // Ceil rational vsync timestamps instead of repeatedly adding a rounded interval:
            // a real 60 Hz clock reaches one full second, without accumulated rounding drift.
            for (vsync in 1..60) {
                val now = (vsync * 1_000_000_000L + 59L) / 60L
                timeline.advance(now - previousNanos)
                previousNanos = now
                assertEquals("${clip.name}, vsync $vsync", vsync / 2, timeline.frameIndex)
                displayedFrames += timeline.frameIndex
            }
            assertEquals((0..30).toSet(), displayedFrames)
            assertFalse(timeline.finished)
        }
    }

    @Test
    fun resumingPartwayThroughAFrameUsesItsRemainingTimeInsteadOfASecondSamplingPeriod() {
        val timeline = WhaleGirlTimeline(WhaleGirlClip.IDLE)
        timeline.advance(25_000_000L)
        assertEquals(0, timeline.frameIndex)
        // Background wall time is not supplied to the timeline. Only the remaining foreground
        // fraction is needed to cross a frame boundary after resuming.
        timeline.advance(8_333_333L)
        assertEquals(0, timeline.frameIndex)
        timeline.advance(1L)
        assertEquals(1, timeline.frameIndex)
    }

    @Test
    fun oneShotsShowTheirLastFrameBeforeCompletionAndNeverSelectBeyondTheAtlas() {
        for (clip in WhaleGirlClip.entries.filterNot { it.looping }) {
            val timeline = WhaleGirlTimeline(clip)
            timeline.advance(1_466_666_666L)
            assertEquals(43, timeline.frameIndex)
            timeline.advance(1L)
            assertEquals(44, timeline.frameIndex)
            assertFalse(timeline.finished)
            timeline.advance(33_333_332L)
            assertEquals(44, timeline.frameIndex)
            assertFalse(timeline.finished)
            timeline.advance(1L)
            assertEquals(44, timeline.frameIndex)
            assertTrue(timeline.finished)
        }
    }

    @Test
    fun legacyPersonalityValuesNeverChangeTheNewSuccessDuration() {
        for (profile in MiffanMotionProfile.entries) {
            assertEquals(1_700L, Avatar.WhaleGirl(profile).characterReplyHoldMillis())
        }
        assertEquals(900L, Avatar.Miffan().characterReplyHoldMillis())
    }
}
