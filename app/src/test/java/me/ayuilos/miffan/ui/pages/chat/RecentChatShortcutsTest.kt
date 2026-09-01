package me.ayuilos.miffan.ui.pages.chat

import java.time.Instant
import me.ayuilos.miffan.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class RecentChatShortcutsTest {
    @Test
    fun `shortcuts exclude current conversation and keep three most recently active chats`() {
        val current = conversation("2026-09-01T12:00:00Z")
        val oldest = conversation("2026-09-01T08:00:00Z")
        val third = conversation("2026-09-01T09:00:00Z")
        val newest = conversation("2026-09-01T11:00:00Z")
        val second = conversation("2026-09-01T10:00:00Z")

        val shortcuts = listOf(oldest, second, current, third, newest)
            .toRecentChatShortcuts(current.id)

        assertEquals(listOf(newest.id, second.id, third.id), shortcuts.map { it.id })
    }

    private fun conversation(updateAt: String) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = emptyList(),
        updateAt = Instant.parse(updateAt),
    )
}
