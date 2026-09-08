package me.ayuilos.miffan.data.model

import kotlinx.serialization.encodeToString
import me.ayuilos.miffan.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleGirlAvatarTest {
    @Test
    fun whaleGirlDefaultsAndEveryMotionProfileSurvivePersistence() {
        assertEquals(Avatar.WhaleGirl(), JsonInstant.decodeFromString<Avatar>("""{"type":"whale_girl"}"""))
        MiffanMotionProfile.entries.forEach { profile ->
            val avatar: Avatar = Avatar.WhaleGirl(profile)
            assertEquals(avatar, JsonInstant.decodeFromString<Avatar>(JsonInstant.encodeToString(avatar)))
            val assistant = Assistant(avatar = avatar)
            assertEquals(assistant, JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(assistant)))
        }
    }

    @Test
    fun whaleGirlIsASeparateCharacterFamilyAndMotionChangesKeepIt() {
        val avatar: Avatar = Avatar.WhaleGirl()
        assertTrue(avatar.isCharacterAvatar())
        assertFalse(avatar.isMiffanAvatar())
        assertEquals(Avatar.WhaleGirl(MiffanMotionProfile.CALM), avatar.withCharacterMotionProfile(MiffanMotionProfile.CALM))
        assertFalse(Avatar.Image("avatar.png").isCharacterAvatar())
        assertFalse(Avatar.Emoji("🐋").isCharacterAvatar())
    }

    @Test
    fun existingAvatarsKeepTheirDecodingAndIdentity() {
        assertEquals(Avatar.Dummy, JsonInstant.decodeFromString<Avatar>("""{"type":"dummy"}"""))
        assertEquals(Avatar.Miffan(), JsonInstant.decodeFromString<Avatar>("""{"type":"miffan"}"""))
        assertEquals(Avatar.Emoji("🍚"), JsonInstant.decodeFromString<Avatar>("""{"type":"emoji","content":"🍚"}"""))
        assertEquals(Avatar.Image("https://example.com/avatar.png"), JsonInstant.decodeFromString<Avatar>(
            """{"type":"image","url":"https://example.com/avatar.png"}""",
        ))
        assertTrue(Avatar.Dummy.isCharacterAvatar())
        assertEquals(Avatar.Miffan(), Assistant().avatar)
    }
}
