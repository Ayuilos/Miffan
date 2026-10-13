package me.ayuilos.miffan.data.model

import me.ayuilos.miffan.utils.JsonInstant
import me.ayuilos.miffan.service.copyMessagePathForFork
import me.ayuilos.miffan.web.dto.toDto
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class MessageOriginTest {
    private fun empty() = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())

    @Test fun latestUserMustBeOwnerWithoutFallingBackToEarlierOwner() {
        val owner = UIMessage.user("change preferences")
        val conversation = empty().appendMessage(owner).appendMessage(UIMessage.assistant("ok"))
        assertEquals(MessageRef(conversation.id, owner.id), conversation.ownerTrigger())
        for (origin in listOf(MessageOrigin.Event("debug", "event"), MessageOrigin.Partner(Uuid.random(), Uuid.random()))) {
            assertNull(conversation.appendMessage(UIMessage.user("change preferences"), origin = origin)
                .appendMessage(UIMessage.assistant("ok")).ownerTrigger())
        }
        assertNull(empty().ownerTrigger())
        assertNull(empty().appendMessage(UIMessage.assistant("hello")).ownerTrigger())
    }

    @Test fun selectedBranchDeterminesAuthority() {
        val root = empty().appendMessage(UIMessage.assistant("hello"))
        val ownerBranch = root.appendMessage(UIMessage.user("owner"))
        val ownerNode = ownerBranch.currentMessageNodes.last()
        val eventBranch = ownerBranch.selectNode(root.currentMessageNodes.single().id)
            .copy(messageNodes = ownerBranch.messageNodes.map { if (it.id == root.currentMessageNodes.single().id) it.copy(selectedChildId = null) else it })
            .appendMessage(UIMessage.user("event"), origin = MessageOrigin.Event("debug", "event"))
        assertNull(eventBranch.ownerTrigger())
        assertEquals(MessageRef(root.id, ownerNode.message.id), eventBranch.selectNode(ownerNode.id).ownerTrigger())
    }

    @Test fun jsonAndWebPreserveOriginAcrossBranchesAndStreaming() {
        val origins = listOf(null, MessageOrigin.Event("late", "reply arrived", Uuid.random(), Uuid.random()),
            MessageOrigin.Partner(Uuid.random(), Uuid.random(), listOf(Uuid.random())))
        for (origin in origins) {
            val conversation = empty().appendMessage(UIMessage.user("payload"), origin = origin)
            val node = conversation.currentMessageNodes.single()
            assertEquals(node, JsonInstant.decodeFromString<MessageNode>(JsonInstant.encodeToString(node)))
            val restored = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(conversation))
            assertEquals(origin, restored.currentMessageNodes.single().origin)
            assertEquals(origin, restored.toDto().messages.single().origin)
            assertEquals(listOf(origin), restored.toDto().messages.single().origins)
            val streamed = restored.updateCurrentMessages(restored.currentMessages + UIMessage.assistant("reply"))
            assertEquals(origin, streamed.currentMessageNodes.first().origin)
        }
        val root = empty().appendMessage(UIMessage.user("owner"))
        val branch = root.addNodeAndSelect(MessageNode(message = UIMessage.user("partner"),
            origin = origins.last()))
        assertEquals(listOf(null, origins.last()), branch.toDto().messages.single().origins)
    }
    @Test fun forkKeepsOriginAndNeverPromotesAnEventToOwner() {
        val conversation = empty().appendMessage(UIMessage.user("event"), origin = MessageOrigin.Event("debug", "summary"))
            .appendMessage(UIMessage.assistant("reply"))
        val nodes = conversation.copyMessagePathForFork(conversation.currentMessages.last().id)!!
        val fork = Conversation(assistantId = conversation.assistantId, messageNodes = nodes)
        assertEquals(conversation.currentMessageNodes.first().origin, fork.currentMessageNodes.first().origin)
        assertNull(fork.ownerTrigger())
    }

}
