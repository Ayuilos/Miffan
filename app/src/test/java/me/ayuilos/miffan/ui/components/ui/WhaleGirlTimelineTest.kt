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
            assertEquals(47, timeline.frameIndex)
            timeline.advance(1)
            assertEquals(0, timeline.frameIndex)
            timeline.advance(4_000_000_000L * 100 + 500_000_000L)
            assertEquals(6, timeline.frameIndex)
            assertFalse(timeline.finished)
        }
    }

    @Test
    fun oneShotsClampEvenAfterALargeClockDelta() {
        for (clip in WhaleGirlClip.entries.filterNot { it.looping }) {
            val timeline = WhaleGirlTimeline(clip)
            timeline.advance(750_000_000)
            assertEquals(18, timeline.frameIndex)
            timeline.advance(Long.MAX_VALUE)
            assertEquals(35, timeline.frameIndex)
            assertTrue(timeline.finished)
            timeline.advance(5_000_000_000)
            assertEquals(35, timeline.frameIndex)
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
        assertEquals(12, happy.frameIndex)
    }

    @Test
    fun legacyPersonalityValuesNeverChangeTheNewSuccessDuration() {
        for (profile in MiffanMotionProfile.entries) {
            assertEquals(1_700L, Avatar.WhaleGirl(profile).characterReplyHoldMillis())
        }
        assertEquals(900L, Avatar.Miffan().characterReplyHoldMillis())
    }

    @Test
    fun aSingleTransparentAssetServesEveryTheme() {
        assertEquals("whale_motion/thinking.webp", WhaleGirlClip.THINKING.assetPath())
        assertEquals(8, WhaleGirlClip.entries.map { it.assetPath() }.distinct().size)
    }
}
