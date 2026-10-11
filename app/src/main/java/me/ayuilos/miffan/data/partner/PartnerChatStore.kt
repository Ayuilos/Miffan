package me.ayuilos.miffan.data.partner

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.data.db.entity.PartnerChatEntity
import me.ayuilos.miffan.data.db.entity.PartnerChatMemberEntity
import me.ayuilos.miffan.data.db.entity.PartnerChatMessageEntity
import me.ayuilos.miffan.data.model.PartnerChat
import me.ayuilos.miffan.data.model.PartnerChatKind
import me.ayuilos.miffan.data.model.PartnerChatMessage
import me.ayuilos.miffan.data.model.PartnerPair
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

internal data class ChatMember(val chatId: Uuid, val assistantId: Uuid,
    val segmentId: Uuid? = null, val syncedMessageId: Uuid? = null)
internal data class ChatMessage(val message: PartnerChatMessage, val chain: List<Uuid> = emptyList())
internal data class ChatSnapshot(val chats: List<PartnerChat>, val messages: List<ChatMessage>, val members: List<ChatMember> = emptyList())

internal interface PartnerChatStore {
    fun observeSnapshot(): Flow<ChatSnapshot>
    fun observeMessages(chatId: Uuid): Flow<List<ChatMessage>>
    suspend fun chat(chatId: Uuid): PartnerChat?
    suspend fun direct(a: Uuid, b: Uuid): PartnerChat
    suspend fun member(chatId: Uuid, assistantId: Uuid): ChatMember
    suspend fun updateMember(member: ChatMember)
    suspend fun messages(chatId: Uuid): List<ChatMessage>
    /** Atomically enforce the six-message budget (including the reserved reply). */
    suspend fun append(message: ChatMessage, reserveReply: Boolean = false): Boolean
    suspend fun exchangeUsage(exchangeId: Uuid): Int
    suspend fun abandonReply(messageId: Uuid)
    suspend fun ids(): Set<Uuid>
    suspend fun directChatsOf(assistantId: Uuid): List<Uuid>
    suspend fun saveGroup(chat: PartnerChat)
    suspend fun replaceMembers(chat: PartnerChat)
    suspend fun removePerspective(chatId: Uuid, assistantId: Uuid)
    suspend fun chatsOf(assistantId: Uuid): List<Uuid>
    suspend fun delete(chatId: Uuid)
}

internal class RoomPartnerChatStore(
    private val db: AppDatabase,
    private val conversations: () -> ConversationRepository,
) : PartnerChatStore {
    private val dao = db.partnerChatDao()
    private val reservedReplies = mutableMapOf<Uuid, Uuid>()
    override fun observeSnapshot(): Flow<ChatSnapshot> = combine(
        dao.observeChats(), dao.observeMembers(), dao.observeAllMessages(),
    ) { chats, members, messages ->
        ChatSnapshot(chats.map { it.model(members.filter { m -> m.chatId == it.id }) }, messages.map { it.model() }, members.map { it.model() })
    }
    override fun observeMessages(chatId: Uuid): Flow<List<ChatMessage>> =
        dao.observeMessages(chatId.toString()).map { rows -> rows.map { it.model() } }
    override suspend fun chat(chatId: Uuid): PartnerChat? =
        dao.getChat(chatId.toString())?.model(dao.getMembers(chatId.toString()))

    override suspend fun direct(a: Uuid, b: Uuid): PartnerChat = db.withTransaction {
        require(a != b)
        val pair = PartnerPair.of(a, b)
        // Stable UUID + primary key enforce uniqueness even across concurrent creators/process restarts.
        val id = Uuid.parse(java.util.UUID.nameUUIDFromBytes(
            "miffan:direct:${pair.first}:${pair.second}".toByteArray(Charsets.UTF_8)).toString())
        val now = Instant.now().toEpochMilli()
        dao.insert(PartnerChatEntity(id.toString(), "DIRECT", "", now))
        listOf(pair.first, pair.second).forEach { dao.insert(PartnerChatMemberEntity(id.toString(), it.toString(), now)) }
        requireNotNull(chat(id))
    }
    override suspend fun member(chatId: Uuid, assistantId: Uuid): ChatMember =
        requireNotNull(dao.getMembers(chatId.toString()).find { it.assistantId == assistantId.toString() }).let {
            ChatMember(chatId, assistantId, it.perspectiveSegmentId?.let(Uuid::parse), it.syncedMessageId?.let(Uuid::parse))
        }
    override suspend fun updateMember(member: ChatMember) {
        val row = dao.getMembers(member.chatId.toString()).first { it.assistantId == member.assistantId.toString() }
        dao.update(row.copy(perspectiveSegmentId = member.segmentId?.toString(), syncedMessageId = member.syncedMessageId?.toString()))
    }
    override suspend fun messages(chatId: Uuid): List<ChatMessage> = dao.getMessages(chatId.toString()).map { it.model() }
    override suspend fun append(message: ChatMessage, reserveReply: Boolean): Boolean = db.withTransaction {
        val m = message.message
        val count = m.exchangeId?.let { dao.exchangeCount(it.toString()) } ?: 0
        val reserved = reservedReplies.values.count { it == m.exchangeId }
        val consumesReservation = m.replyTo in reservedReplies
        if (dao.getChat(m.chatId.toString())?.kind != "GROUP" && m.exchangeId != null && count + reserved + (if (reserveReply) 2 else if (consumesReservation) 0 else 1) > 6) {
            return@withTransaction false
        }
        dao.insert(PartnerChatMessageEntity(m.id.toString(), m.chatId.toString(), m.senderId.toString(), m.text,
            m.createdAt.toEpochMilli(), m.exchangeId?.toString(), m.replyTo?.toString(), JsonInstant.encodeToString(message.chain), JsonInstant.encodeToString(m.mentions)))
        if (reserveReply) reservedReplies[m.id] = requireNotNull(m.exchangeId)
        if (consumesReservation) reservedReplies.remove(m.replyTo)
        true
    }
    override suspend fun exchangeUsage(exchangeId: Uuid): Int = db.withTransaction {
        dao.exchangeCount(exchangeId.toString()) + reservedReplies.values.count { it == exchangeId }
    }
    override suspend fun abandonReply(messageId: Uuid) { db.withTransaction { reservedReplies.remove(messageId) } }
    override suspend fun ids(): Set<Uuid> = dao.getIds().mapTo(HashSet(), Uuid::parse)
    override suspend fun directChatsOf(assistantId: Uuid): List<Uuid> = dao.directChatsOf(assistantId.toString()).map(Uuid::parse)
    override suspend fun saveGroup(chat: PartnerChat) = db.withTransaction {
        val row = PartnerChatEntity(chat.id.toString(), chat.kind.name, chat.title, chat.createdAt.toEpochMilli(), chat.hostId?.toString())
        dao.insert(row)
        dao.update(row)
        if (dao.getMembers(chat.id.toString()).isEmpty()) {
            chat.memberIds.forEach { dao.insert(PartnerChatMemberEntity(chat.id.toString(), it.toString(), chat.createdAt.toEpochMilli())) }
        }
    }
    override suspend fun replaceMembers(chat: PartnerChat) = db.withTransaction {
        val old = dao.getMembers(chat.id.toString()).associateBy { it.assistantId }
        val cursor = dao.getMessages(chat.id.toString()).lastOrNull()?.id
        val now = Instant.now().toEpochMilli()
        dao.deleteMembers(chat.id.toString())
        chat.memberIds.forEach { id ->
            dao.insert(old[id.toString()]
                ?: PartnerChatMemberEntity(chat.id.toString(), id.toString(), now, syncedMessageId = cursor))
        }
        saveGroup(chat)
    }
    override suspend fun chatsOf(assistantId: Uuid): List<Uuid> = dao.chatsOf(assistantId.toString()).map(Uuid::parse)
    override suspend fun removePerspective(chatId: Uuid, assistantId: Uuid) {
        deletePerspectives(db.conversationDao().getPartnerSegmentIds(chatId.toString(), assistantId.toString()))
    }
    private suspend fun deletePerspectives(ids: List<String>) {
        ids.forEach { id ->
            conversations().getConversationById(Uuid.parse(id))?.let { conversations().deleteConversation(it) }
        }
    }
    override suspend fun delete(chatId: Uuid) {
        // Include removed members' orphaned perspectives, even after an interrupted cleanup.
        deletePerspectives(db.conversationDao().getPartnerSegmentIds(chatId.toString()))
        dao.delete(chatId.toString())
    }

}

private fun PartnerChatEntity.model(members: List<PartnerChatMemberEntity>) = PartnerChat(
    Uuid.parse(id), PartnerChatKind.valueOf(kind), members.map { Uuid.parse(it.assistantId) }, title, Instant.ofEpochMilli(createdAt), hostId?.let(Uuid::parse))
private fun PartnerChatMessageEntity.model() = ChatMessage(PartnerChatMessage(Uuid.parse(id), Uuid.parse(chatId),
    Uuid.parse(senderId), text, Instant.ofEpochMilli(createdAt), exchangeId?.let(Uuid::parse), replyTo?.let(Uuid::parse), JsonInstant.decodeFromString(mentions)),
    JsonInstant.decodeFromString(chain))

private fun PartnerChatMemberEntity.model() = ChatMember(Uuid.parse(chatId), Uuid.parse(assistantId),
    perspectiveSegmentId?.let(Uuid::parse), syncedMessageId?.let(Uuid::parse))
