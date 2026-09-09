package me.ayuilos.miffan.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WhaleGirlSemanticTest {
    @Test
    fun inputOverridesRemindersAndSleepWithoutPretendingToReason() {
        for (state in listOf(MiffanMascotState.Idle, MiffanMascotState.UpdateAvailable)) {
            assertEquals(WhaleGirlClip.FOCUSED, resolveWhaleGirlClip(state,
                inputState = MiffanMascotInputState.Focused, sleeping = true))
            assertEquals(WhaleGirlClip.TYPING, resolveWhaleGirlClip(state,
                inputState = MiffanMascotInputState.Typing, sleeping = true))
        }
    }

    @Test
    fun submitAcknowledgesBeforeLoadingButNeverHidesErrorOrCompletion() {
        assertEquals(WhaleGirlClip.SUBMITTED, resolveWhaleGirlClip(
            MiffanMascotState.Thinking, submitted = true))
        assertEquals(WhaleGirlClip.EATING, resolveWhaleGirlClip(
            MiffanMascotState.Thinking, submitted = false))
        assertEquals(WhaleGirlClip.IDLE, resolveWhaleGirlClip(
            MiffanMascotState.Error, submitted = true))
        assertEquals(WhaleGirlClip.SUCCESS, resolveWhaleGirlClip(
            MiffanMascotState.Happy, submitted = true))
        for (phase in AssistantGenerationPhase.entries) {
            assertEquals(resolveWhaleGirlClip(MiffanMascotState.Thinking, phase),
                resolveWhaleGirlClip(MiffanMascotState.Thinking, phase,
                    MiffanMascotInputState.Typing, petting = true, sleeping = true))
        }
    }

    @Test
    fun inputPosesRemainMeaningfulAtRestAndSubmitSettles() {
        assertNotEquals(whaleActing(WhaleGirlClip.IDLE, 0.0), whaleActing(WhaleGirlClip.FOCUSED, 0.0))
        assertNotEquals(whaleActing(WhaleGirlClip.TYPING, 0.0), whaleActing(WhaleGirlClip.TYPING, .25))
        assertEquals(whaleActing(WhaleGirlClip.TYPING, 0.0), whaleActing(WhaleGirlClip.TYPING, 2.0))
        assertNotEquals(WhaleActing(), whaleActing(WhaleGirlClip.SUBMITTED, .3))
        assertEquals(WhaleActing(), whaleActing(WhaleGirlClip.SUBMITTED, 1.5))
    }
}
