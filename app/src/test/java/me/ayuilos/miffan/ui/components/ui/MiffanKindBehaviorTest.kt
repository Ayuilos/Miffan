package me.ayuilos.miffan.ui.components.ui

import me.ayuilos.miffan.data.model.MiffanKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
