package me.ayuilos.miffan.ui.components.ui

import me.ayuilos.miffan.data.model.MiffanKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MiffanKindBehaviorTest {
    @Test
    fun everyKindHasOneDistinctSignature() {
        val behaviors = MiffanKind.entries.map { it.miffanKindBehavior() }

        assertEquals(MiffanKind.entries.size, behaviors.map { it.signature }.toSet().size)
        assertTrue(behaviors.all { it.cycleMillis > 1_000 })
    }

    @Test
    fun activeStatesAreClearerThanOrdinaryIdle() {
        MiffanKind.entries.forEach { kind ->
            val behavior = kind.miffanKindBehavior()

            assertTrue(behavior.thinkingStrength > behavior.idleStrength)
            assertTrue(behavior.typingStrength > behavior.idleStrength)
            assertTrue(behavior.happyStrength > behavior.idleStrength)
            assertTrue(behavior.submitStrength > behavior.idleStrength)
            assertTrue(behavior.errorStrength < behavior.happyStrength)
        }
    }

    @Test
    fun updateAvailableIsAnActiveButRestrainedCharacterState() {
        MiffanKind.entries.forEach { kind ->
            val behavior = kind.miffanKindBehavior()
            val updateStrength = behavior.strengthFor(
                state = MiffanMascotState.UpdateAvailable,
                inputState = MiffanMascotInputState.Inactive,
                dayPhase = MiffanDayPhase.Noon,
                submitProgress = 0f,
            )

            assertTrue(updateStrength > behavior.idleStrength)
            assertTrue(updateStrength < behavior.happyStrength)
        }
    }

    @Test
    fun dumplingSignatureUsesAGentleCycle() {
        val behavior = MiffanKind.DUMPLING.miffanKindBehavior()

        assertTrue(behavior.cycleMillis >= 2_000)
        val pose = behavior.poseFor(
            phaseDegrees = 90f,
            strength = 1f,
            state = MiffanMascotState.Thinking,
            inputState = MiffanMascotInputState.Inactive,
        )
        assertTrue(abs(pose.offsetX) <= 3f)
        assertTrue(abs(pose.rotationDegrees) <= 2f)
    }

    @Test
    fun sceneMeaningSelectsStrengthWithoutSelectingAnimation() {
        val behavior = MiffanKind.DUMPLING.miffanKindBehavior()

        assertEquals(
            behavior.focusedStrength,
            behavior.strengthFor(
                state = MiffanMascotState.Idle,
                inputState = MiffanMascotInputState.Focused,
                dayPhase = MiffanDayPhase.Noon,
                submitProgress = 0f,
            ),
        )
        assertEquals(
            behavior.typingStrength,
            behavior.strengthFor(
                state = MiffanMascotState.Idle,
                inputState = MiffanMascotInputState.Typing,
                dayPhase = MiffanDayPhase.Noon,
                submitProgress = 0f,
            ),
        )
        assertEquals(
            behavior.submitStrength,
            behavior.strengthFor(
                state = MiffanMascotState.Idle,
                inputState = MiffanMascotInputState.Inactive,
                dayPhase = MiffanDayPhase.Noon,
                submitProgress = 0.5f,
            ),
            0.0001f,
        )
    }

    @Test
    fun onlyStargazerGetsANightIdleBoost() {
        MiffanKind.entries.forEach { kind ->
            val behavior = kind.miffanKindBehavior()
            val noon = behavior.strengthFor(
                state = MiffanMascotState.Idle,
                inputState = MiffanMascotInputState.Inactive,
                dayPhase = MiffanDayPhase.Noon,
                submitProgress = 0f,
            )
            val night = behavior.strengthFor(
                state = MiffanMascotState.Idle,
                inputState = MiffanMascotInputState.Inactive,
                dayPhase = MiffanDayPhase.Night,
                submitProgress = 0f,
            )

            if (kind == MiffanKind.STARGAZER) {
                assertTrue(night > noon)
            } else {
                assertEquals(noon, night)
            }
        }
    }

    @Test
    fun signaturesUseDifferentWholeBodyGestures() {
        val rice = MiffanKind.RICE.miffanKindBehavior().poseFor(
            phaseDegrees = 90f,
            strength = 1f,
            state = MiffanMascotState.Happy,
            inputState = MiffanMascotInputState.Inactive,
        )
        val sprout = MiffanKind.SPROUT.miffanKindBehavior().poseFor(
            phaseDegrees = 90f,
            strength = 1f,
            state = MiffanMascotState.Idle,
            inputState = MiffanMascotInputState.Focused,
        )
        val dumpling = MiffanKind.DUMPLING.miffanKindBehavior().poseFor(
            phaseDegrees = 45f,
            strength = 1f,
            state = MiffanMascotState.Thinking,
            inputState = MiffanMascotInputState.Inactive,
        )
        val stargazer = MiffanKind.STARGAZER.miffanKindBehavior().poseFor(
            phaseDegrees = 0f,
            strength = 1f,
            state = MiffanMascotState.Thinking,
            inputState = MiffanMascotInputState.Inactive,
        )

        assertTrue(rice.offsetY < 0f && rice.scaleX > 1f)
        assertTrue(sprout.offsetX > 0f && sprout.rotationDegrees > 0f)
        assertTrue(dumpling.offsetX > 0f && dumpling.rotationDegrees > 0f)
        assertTrue(stargazer.offsetY < 0f && stargazer.scaleY > 1f)
    }

    @Test
    fun errorStateSettlesWholeBodySignature() {
        MiffanKind.entries.forEach { kind ->
            val pose = kind.miffanKindBehavior().poseFor(
                phaseDegrees = 90f,
                strength = 1f,
                state = MiffanMascotState.Error,
                inputState = MiffanMascotInputState.Typing,
            )

            assertEquals(MiffanSignaturePose(), pose)
        }
    }

    @Test
    fun enteringErrorCanKeepTheCurrentPoseThenSettleGradually() {
        MiffanKind.entries.forEach { kind ->
            val behavior = kind.miffanKindBehavior()
            val active = behavior.poseFor(90f, 1f, MiffanMascotState.Thinking, MiffanMascotInputState.Inactive)
            val entering = behavior.poseFor(
                90f, 1f, MiffanMascotState.Error, MiffanMascotInputState.Inactive,
                settleProgress = 0f,
            )
            val midway = behavior.poseFor(
                90f, 1f, MiffanMascotState.Error, MiffanMascotInputState.Inactive,
                settleProgress = 0.5f,
            )
            assertEquals(active, entering)
            assertEquals(active.offsetY * 0.5f, midway.offsetY, 0.0001f)
            assertEquals(active.rotationDegrees * 0.5f, midway.rotationDegrees, 0.0001f)
            assertEquals(1f + (active.scaleY - 1f) * 0.5f, midway.scaleY, 0.0001f)
        }
    }

    @Test
    fun sproutListeningLeanUsesTransitionProgress() {
        val behavior = MiffanKind.SPROUT.miffanKindBehavior()
        val starting = behavior.poseFor(
            0f, 1f, MiffanMascotState.Idle, MiffanMascotInputState.Focused,
            listeningProgress = 0f,
        )
        val midway = behavior.poseFor(
            0f, 1f, MiffanMascotState.Idle, MiffanMascotInputState.Focused,
            listeningProgress = 0.5f,
        )
        assertEquals(0f, starting.offsetX, 0f)
        assertEquals(1.1f, midway.offsetX, 0.0001f)
        assertEquals(1.75f, midway.rotationDegrees, 0.0001f)
    }
}
