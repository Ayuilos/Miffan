package me.ayuilos.miffan.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantAvatarStateTest {
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
