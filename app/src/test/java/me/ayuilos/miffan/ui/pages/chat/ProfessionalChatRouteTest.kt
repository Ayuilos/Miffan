package me.ayuilos.miffan.ui.pages.chat

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import androidx.navigation3.runtime.NavKey
import me.ayuilos.miffan.ui.context.Navigator
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.db.dao.ConversationDAO
import me.ayuilos.miffan.data.db.dao.PartnerChatDAO
import org.junit.Assert.*
import org.junit.Test

class ProfessionalChatRouteTest {
    private val conversations = mockk<ConversationDAO>()
    private val chats = mockk<PartnerChatDAO>()

    @Test fun ordinaryAndFreshChatsPreserveDraftAndFocus() = runTest {
        val key = Screen.Chat("ordinary", text = "draft", nodeId = "node", messageId = "message")
        coEvery { conversations.getChatIdOf(any()) } returns null
        assertEquals(key, professionalChatDestination(key, conversations, chats))
        assertEquals(key, professionalStartupDestination(key, conversations))
        coVerify(exactly = 0) { chats.getChat(any()) }
    }

    @Test fun explicitPerspectiveOpenRoutesToItsPartnerChat() = runTest {
        val key = Screen.Chat("perspective", nodeId = "node")
        coEvery { conversations.getChatIdOf(key.id) } returns "partner-chat"
        coEvery { chats.getChat("partner-chat") } returns mockk()
        assertEquals(Screen.PartnerChat("partner-chat"), professionalChatDestination(key, conversations, chats))
    }

    @Test fun deletedPartnerChatNeverOpensOrphanPerspective() = runTest {
        val key = Screen.Chat("orphan")
        coEvery { conversations.getChatIdOf(key.id) } returns "deleted"
        coEvery { chats.getChat("deleted") } returns null
        val target = professionalChatDestination(key, conversations, chats)
        assertTrue(target is Screen.Chat)
        assertNotEquals(key.id, (target as Screen.Chat).id)
    }

    @Test fun startupSkipsPerspectiveAndUsesOrdinaryAssistantConversation() = runTest {
        coEvery { conversations.getChatIdOf("perspective") } returns "partner-chat"
        coEvery { conversations.getAssistantIdOf("perspective") } returns "assistant"
        coEvery { conversations.getRecentConversationsOfAssistant("assistant", 1) } returns listOf(mockk {
            coEvery { id } returns "ordinary"
        })
        assertEquals(Screen.Chat("ordinary"), professionalStartupDestination(Screen.Chat("perspective"), conversations))
    }

    @Test fun startupWithOnlyPerspectivesCreatesFreshDraft() = runTest {
        coEvery { conversations.getChatIdOf("perspective") } returns "partner-chat"
        coEvery { conversations.getAssistantIdOf("perspective") } returns "assistant"
        coEvery { conversations.getRecentConversationsOfAssistant("assistant", 1) } returns emptyList()
        assertNotEquals("perspective", professionalStartupDestination(Screen.Chat("perspective"), conversations).id)
    }
    @Test fun redirectPreservesExistingRootAndAddsOneOnlyForColdLaunch() {
        val root = Screen.Chat("root")
        val key = Screen.Chat("perspective")
        val destination = Screen.PartnerChat("chat")
        val stack = mutableListOf<NavKey>(root, key)
        Navigator(stack).replace(key, destination, Screen.Chat("unused"), preserveExistingRoot = true)
        assertEquals(listOf(root, destination), stack)
        val coldStack = mutableListOf<NavKey>(key)
        Navigator(coldStack).replace(key, destination, root, preserveExistingRoot = true)
        assertEquals(listOf(root, destination), coldStack)
    }

}
