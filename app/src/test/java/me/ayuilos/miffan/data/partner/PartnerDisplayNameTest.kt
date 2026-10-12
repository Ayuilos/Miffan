package me.ayuilos.miffan.data.partner

import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class PartnerDisplayNameTest {
    private val blank = Assistant(name = " ", systemPrompt = "介绍")
    private val named = Assistant(name = "蓝鲸")
    private val settings = Settings(assistants = listOf(blank, named))

    @Test fun namedBlankAndDeletedPartnersHaveStableNames() {
        assertEquals("蓝鲸", partnerDisplayName(named))
        assertEquals("默认助手", partnerDisplayName(blank))
        assertEquals("默认助手", partnerDisplayName(blank.copy(name = "")))
        assertEquals("已删除的伙伴", partnerDisplayName(null))
    }

    @Test fun newlySyncedMessagesUseDisplayNameWithoutRewritingOldPrefixes() {
        val chat = Uuid.random()
        val old = Conversation(assistantId = named.id, chatId = chat, messageNodes = emptyList())
            .appendMessage(me.rerere.ai.ui.UIMessage.user("[伙伴] old"))
        val message = PartnerChatMessage(Uuid.random(), chat, blank.id, "new", Instant.EPOCH, Uuid.random())
        val synced = synchronizePerspective(old, listOf(ChatMessage(message, listOf(blank.id))), null, settings)
        assertEquals("[伙伴] old", synced.currentMessages.first().toText())
        assertEquals("[默认助手] new", synced.currentMessages.last().toText())
    }

    @Test fun privateGroupContextTitleAndHostUseDisplayName() {
        val direct = PartnerChat(Uuid.random(), PartnerChatKind.DIRECT, listOf(named.id, blank.id), createdAt = Instant.EPOCH)
        assertTrue(perspectiveContext(direct, named.id, settings).contains("你和 默认助手 的私聊"))
        val group = direct.copy(kind = PartnerChatKind.GROUP, hostId = blank.id)
        val context = perspectiveContext(group, named.id, settings)
        assertTrue(context.contains("蓝鲸 · 默认助手"))
        assertTrue(context.contains("默认助手：介绍"))
        assertTrue(context.contains("主持是 默认助手"))
        assertEquals("蓝鲸 · 默认助手", partnerChatTitle(group, settings))
    }

    @Test fun groupMentionsRecognizeEveryBlankPartnerAliasAndIgnoreSelf() {
        val group = PartnerChat(Uuid.random(), PartnerChatKind.GROUP, listOf(named.id, blank.id), createdAt = Instant.EPOCH)
        for (alias in BLANK_PARTNER_NAMES) {
            assertEquals(listOf(blank.id), parseGroupMentions("@$alias 请回答", group, named.id, settings))
            assertTrue(parseGroupMentions("@$alias 请回答", group, blank.id, settings).isEmpty())
        }
        assertEquals(listOf(blank.id), parseGroupMentions("@默认助手 @Default Assistant", group, named.id, settings))
    }
}
