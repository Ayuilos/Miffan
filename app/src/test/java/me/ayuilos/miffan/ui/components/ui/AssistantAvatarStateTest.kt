package me.ayuilos.miffan.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantAvatarStateTest {
    @Test
    fun stoppingWithoutConfirmedSuccessDoesNotCelebrate() {
        assertEquals(MiffanMascotState.Idle, resolveAssistantMascotState(false, false, MiffanMascotState.Idle))
        assertEquals(MiffanMascotState.Error, resolveAssistantMascotState(false, true, MiffanMascotState.Error))
        assertEquals(MiffanMascotState.Thinking, resolveAssistantMascotState(true, true, MiffanMascotState.Error))
    }

    @Test
    fun semanticUpdateStateReachesIdleAssistantAvatar() {
        assertEquals(
            MiffanMascotState.UpdateAvailable,
            resolveAssistantMascotState(
                loading = false,
                showingCompletion = false,
                semanticState = MiffanMascotState.UpdateAvailable,
            ),
        )
    }

    @Test
    fun transientConversationStatesTakePriorityOverUpdateState() {
        assertEquals(
            MiffanMascotState.Thinking,
            resolveAssistantMascotState(
                loading = true,
                showingCompletion = false,
                semanticState = MiffanMascotState.UpdateAvailable,
            ),
        )
        assertEquals(
            MiffanMascotState.Happy,
            resolveAssistantMascotState(
                loading = false,
                showingCompletion = true,
                semanticState = MiffanMascotState.UpdateAvailable,
            ),
        )
    }
}
