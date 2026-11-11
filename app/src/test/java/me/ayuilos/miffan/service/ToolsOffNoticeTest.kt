package me.ayuilos.miffan.service

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ToolsOffNoticeTest {
    @Test
    fun onlyThisChatsToolsNoticeIsCleared() {
        val chat = Uuid.random()
        val other = Uuid.random()
        val notice = ChatError(error = IllegalStateException("no tools"), conversationId = chat, solution = ChatErrorSolution.EnableModelTools)
        val failure = ChatError(error = IllegalStateException("boom"), conversationId = chat)
        val elsewhere = ChatError(error = IllegalStateException("no tools"), conversationId = other, solution = ChatErrorSolution.EnableModelTools)
        assertEquals(listOf(failure, elsewhere), listOf(notice, failure, elsewhere, notice.copy(id = Uuid.random())).withoutToolsOffNotice(chat))
    }
}
