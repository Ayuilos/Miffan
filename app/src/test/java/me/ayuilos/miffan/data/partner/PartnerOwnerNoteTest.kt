package me.ayuilos.miffan.data.partner

import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageOrigin
import me.ayuilos.miffan.data.thread.ThreadListSummaries
import me.rerere.ai.core.MessageRole
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class PartnerOwnerNoteTest {
    @Test fun eventAndAssistantNoteAreUnreadWithoutOwnerAuthority() {
        val origin = MessageOrigin.Event("partner_note", "来自和 小B 的私聊", Uuid.random(), Uuid.random())
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList()).withPartnerOwnerNote(origin, "note to owner")
        val nodes = conversation.currentMessageNodes
        assertEquals(2, nodes.size)
        assertEquals(MessageRole.USER, nodes[0].message.role); assertEquals(origin, nodes[0].origin)
        assertEquals(MessageRole.ASSISTANT, nodes[1].message.role); assertEquals("note to owner", nodes[1].message.toText())
        assertNull(conversation.ownerTrigger())
        val summary = ThreadListSummaries.summarize(nodes.reversed(), 0)
        assertEquals(1, summary.unread); assertEquals("note to owner", summary.preview); assertFalse(summary.fromUser)
    }
}
