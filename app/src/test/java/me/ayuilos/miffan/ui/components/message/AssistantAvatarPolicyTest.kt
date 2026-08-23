package me.ayuilos.miffan.ui.components.message

import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantAvatarPolicyTest {
    @Test
    fun dummyAvatarAlwaysUsesAssistantIdentity() {
        val assistant = Assistant(
            avatar = Avatar.Dummy,
            useAssistantAvatar = false,
        )

        assertTrue(shouldUseAssistantIdentity(assistant))
    }

    @Test
    fun customAvatarStillRespectsAssistantAvatarPreference() {
        val customAvatar = Avatar.Emoji("🍚")

        assertFalse(
            shouldUseAssistantIdentity(
                Assistant(avatar = customAvatar, useAssistantAvatar = false)
            )
        )
        assertTrue(
            shouldUseAssistantIdentity(
                Assistant(avatar = customAvatar, useAssistantAvatar = true)
            )
        )
    }
}
