package me.ayuilos.miffan.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.ayuilos.miffan.data.db.entity.PartnerChatEntity
import me.ayuilos.miffan.data.db.entity.PartnerChatMemberEntity
import me.ayuilos.miffan.data.db.entity.PartnerChatMessageEntity

@Dao
interface PartnerChatDAO {
    @Query("SELECT * FROM partner_chat ORDER BY created_at, rowid")
    fun observeChats(): Flow<List<PartnerChatEntity>>
    @Query("SELECT * FROM partner_chat_member ORDER BY joined_at, rowid")
    fun observeMembers(): Flow<List<PartnerChatMemberEntity>>
    @Query("SELECT * FROM partner_chat_message ORDER BY rowid")
    fun observeAllMessages(): Flow<List<PartnerChatMessageEntity>>
    @Query("SELECT * FROM partner_chat WHERE id = :id")
    suspend fun getChat(id: String): PartnerChatEntity?
    @Query("SELECT * FROM partner_chat_member WHERE chat_id = :id ORDER BY joined_at, rowid")
    suspend fun getMembers(id: String): List<PartnerChatMemberEntity>
    @Query("SELECT * FROM partner_chat_message WHERE chat_id = :id ORDER BY rowid")
    suspend fun getMessages(id: String): List<PartnerChatMessageEntity>
    @Query("SELECT * FROM partner_chat_message WHERE chat_id = :id ORDER BY rowid")
    fun observeMessages(id: String): Flow<List<PartnerChatMessageEntity>>
    @Query("SELECT COUNT(*) FROM partner_chat_message WHERE exchange_id = :exchangeId")
    suspend fun exchangeCount(exchangeId: String): Int
    @Query("SELECT id FROM partner_chat")
    suspend fun getIds(): List<String>
    @Query("SELECT c.id FROM partner_chat c JOIN partner_chat_member m ON c.id = m.chat_id WHERE c.kind = 'DIRECT' AND m.assistant_id = :assistantId")
    suspend fun directChatsOf(assistantId: String): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(chat: PartnerChatEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(member: PartnerChatMemberEntity)
    @Insert
    suspend fun insert(message: PartnerChatMessageEntity)
    @Update
    suspend fun update(member: PartnerChatMemberEntity)
    @Query("DELETE FROM partner_chat WHERE id = :id")
    suspend fun delete(id: String)
}
