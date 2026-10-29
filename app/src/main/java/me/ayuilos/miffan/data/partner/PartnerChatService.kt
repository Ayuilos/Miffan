package me.ayuilos.miffan.data.partner

import kotlinx.coroutines.flow.Flow
import me.ayuilos.miffan.data.model.PartnerChat
import me.ayuilos.miffan.data.model.PartnerChatApproval
import me.ayuilos.miffan.data.model.PartnerChatMessage
import me.ayuilos.miffan.data.model.PartnerChatSummary
import kotlin.uuid.Uuid

/**
 * Chats with two or more partners: DIRECT chats between two partners and owner-made GROUP chats. Each partner speaks from its own
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

    /** The typing partners of [chatId] whose model has answered the request; the others are still connecting. */
    fun observeConnected(chatId: Uuid): Flow<Set<Uuid>>

    /**
     * The owner speaks in [chatId]. DIRECT mentions answer in order, or the last speaker answers.
     * GROUP mentions answer concurrently, or the host answers when nobody is mentioned.
     * Returns once the message is stored, not when answered.
     */
    suspend fun sendFromOwner(chatId: Uuid, text: String, mentions: List<Uuid> = emptyList())

    /** The pairs of partners the owner allowed to contact each other, involving [assistantId]. */
    fun observeContacts(assistantId: Uuid): Flow<List<Uuid>>

    /** Takes back the owner's permission for [a] and [b] to contact each other. */
    suspend fun revokeContact(a: Uuid, b: Uuid)

    /** Tool calls by members of [chatId] waiting for the owner, oldest first. */
    fun observeApprovals(chatId: Uuid): Flow<List<PartnerChatApproval>>

    /** The owner answers a member's pending tool call inside [chatId]. */
    suspend fun answerApproval(chatId: Uuid, memberId: Uuid, toolCallId: String, approved: Boolean)

    /** Creates a group of [memberIds] (2–6 partners) hosted by the first member; returns its id. */
    suspend fun createGroup(memberIds: List<Uuid>, title: String): Uuid

    suspend fun renameGroup(chatId: Uuid, title: String)

    /** Replaces the partner members (2–6); a removed host passes hosting to the first member. */
    suspend fun setMembers(chatId: Uuid, memberIds: List<Uuid>)

    /** Agent invitations merge with current members under the management lock, never removing perspectives. */
    suspend fun setMembers(chatId: Uuid, memberIds: List<Uuid>, access: me.ayuilos.miffan.data.ai.AgentAccess)

    suspend fun setHost(chatId: Uuid, assistantId: Uuid)

    /** Deletes a group with its messages and perspective segments. */
    suspend fun deleteGroup(chatId: Uuid)
}
