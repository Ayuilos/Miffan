package me.ayuilos.miffan.data.partner

import kotlinx.coroutines.flow.Flow
import me.ayuilos.miffan.data.model.PartnerChat
import me.ayuilos.miffan.data.model.PartnerChatMessage
import me.ayuilos.miffan.data.model.PartnerChatSummary
import kotlin.uuid.Uuid

/**
 * Chats with two or more partners (phase 3: two partners). Each partner speaks from its own
 * private perspective segment (a hidden [me.ayuilos.miffan.data.model.Conversation] with
 * `chatId` set), so tools, approvals and memory rules work as in its own thread.
 */
interface PartnerChatService {
    /** Every partner chat for the Chats list, newest activity first. */
    fun observeSummaries(): Flow<List<PartnerChatSummary>>

    fun observeChat(chatId: Uuid): Flow<PartnerChat?>

    /** Final messages, oldest first. */
    fun observeMessages(chatId: Uuid): Flow<List<PartnerChatMessage>>

    /** Partners whose turn in [chatId] is running. */
    fun observeTyping(chatId: Uuid): Flow<Set<Uuid>>

    /**
     * The owner speaks in [chatId]. The mentioned partners answer, in order; with no mention,
     * the partner who spoke last answers. Returns once the message is stored, not when answered.
     */
    suspend fun sendFromOwner(chatId: Uuid, text: String, mentions: List<Uuid> = emptyList())

    /** The pairs of partners the owner allowed to contact each other, involving [assistantId]. */
    fun observeContacts(assistantId: Uuid): Flow<List<Uuid>>

    /** Takes back the owner's permission for [a] and [b] to contact each other. */
    suspend fun revokeContact(a: Uuid, b: Uuid)
}
