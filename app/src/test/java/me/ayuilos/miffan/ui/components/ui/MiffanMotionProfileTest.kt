package me.ayuilos.miffan.ui.components.ui

import me.ayuilos.miffan.data.model.MiffanMotionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.random.Random

class MiffanMotionProfileTest {
    @Test
    fun historicalAvatarsAndReducedMotionDoNotScheduleAmbientAnimations() {
        assertFalse(miffanRunsAmbientMotion(MiffanPresentation.Avatar, MiffanMascotState.Idle, false))
        assertTrue(miffanRunsAmbientMotion(MiffanPresentation.Avatar, MiffanMascotState.Thinking, false))
        assertTrue(miffanRunsAmbientMotion(MiffanPresentation.Scene, MiffanMascotState.Idle, false))
        for (state in MiffanMascotState.entries) {
            assertFalse(miffanRunsAmbientMotion(MiffanPresentation.Scene, state, true))
        }
        for (profile in MiffanMotionProfile.entries) {
            val scene = profile.miffanMotionTuning()
            val avatar = scene.forAvatar()
            assertTrue(avatar.thinkingBob < scene.thinkingBob)
            assertTrue(avatar.stateAmplitude < scene.stateAmplitude)
            assertEquals(scene.durationScale, avatar.durationScale)
        }
    }

    private val lively = MiffanMotionProfile.LIVELY.miffanMotionTuning()
    private val calm = MiffanMotionProfile.CALM.miffanMotionTuning()
    private val curious = MiffanMotionProfile.CURIOUS.miffanMotionTuning()

    @Test
    fun profilesHaveOrderedRhythms() {
        assertTrue(lively.duration(1_000) < curious.duration(1_000))
        assertTrue(curious.duration(1_000) < calm.duration(1_000))
    }

    @Test
    fun calmProfileHasTheLightestBodyResponse() {
        assertTrue(calm.tapSquash > curious.tapSquash)
        assertTrue(calm.tapOffset < curious.tapOffset)
        assertTrue(calm.stateAmplitude < curious.stateAmplitude)
    }

    @Test
    fun curiousProfileLeadsWithItsEyes() {
        assertEquals(0L, lively.attentionBodyDelayMillis)
        assertTrue(curious.attentionBodyDelayMillis > lively.attentionBodyDelayMillis)
        assertTrue(curious.gazeAmplitude > lively.gazeAmplitude)
        assertTrue(curious.attentionTiltDegrees > lively.attentionTiltDegrees)
    }

    @Test
    fun springsKeepTheProfileTempoWithoutAddingOvershootToTheFace() {
        assertTrue(lively.springSpec<Float>(360f).stiffness > curious.springSpec<Float>(360f).stiffness)
        assertTrue(curious.springSpec<Float>(360f).stiffness > calm.springSpec<Float>(360f).stiffness)
        assertEquals(1f, curious.springSpec<Float>(360f).dampingRatio, 0f)
    }

    @Test
    fun thinkingGazeLooksUpAndRestsBetweenIdeas() {
        val random = Random(17)
        val targets = (0..3).map {
            nextMiffanGaze(MiffanMascotState.Thinking, MiffanDayPhase.Noon, it, random)
        }
        assertTrue(targets[0].x < 0f)
        assertTrue(targets[2].x > 0f)
        assertTrue(targets.take(3).all { it.y < -2f && it.holdMillis >= 1_400 })
        assertEquals(0f, targets.last().x, 0f)
        assertEquals(0f, targets.last().y, 0f)
    }

    @Test
    fun idleGazeReturnsHomeAndNightStaysQuiet() {
        for (step in 0..50) {
            val target = nextMiffanGaze(MiffanMascotState.Idle, MiffanDayPhase.Night, step, Random(step))
            assertTrue(kotlin.math.abs(target.x) <= 1.8f)
            assertTrue(target.holdMillis >= 3_200)
            if (step % 3 == 0) {
                assertEquals(0f, target.x, 0f)
                assertEquals(0f, target.y, 0f)
            }
        }
    }
}
