package me.ayuilos.miffan.ui.components.ui

import me.ayuilos.miffan.data.model.MiffanMotionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiffanMotionProfileTest {
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
}
