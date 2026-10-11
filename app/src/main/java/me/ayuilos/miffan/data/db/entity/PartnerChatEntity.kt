package me.ayuilos.miffan.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "partner_chat")
data class PartnerChatEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    @ColumnInfo("created_at") val createdAt: Long,
)

@Entity(tableName = "partner_chat_member", primaryKeys = ["chat_id", "assistant_id"],
    foreignKeys = [ForeignKey(entity = PartnerChatEntity::class, parentColumns = ["id"],
        childColumns = ["chat_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("assistant_id")])
data class PartnerChatMemberEntity(
    @ColumnInfo("chat_id") val chatId: String,
    @ColumnInfo("assistant_id") val assistantId: String,
    @ColumnInfo("joined_at") val joinedAt: Long,
    @ColumnInfo("perspective_segment_id") val perspectiveSegmentId: String? = null,
    @ColumnInfo("synced_message_id") val syncedMessageId: String? = null,
)

@Entity(tableName = "partner_chat_message",
    foreignKeys = [ForeignKey(entity = PartnerChatEntity::class, parentColumns = ["id"],
        childColumns = ["chat_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chat_id"), Index("exchange_id")])
data class PartnerChatMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("chat_id") val chatId: String,
    @ColumnInfo("sender_id") val senderId: String,
    val text: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("exchange_id") val exchangeId: String? = null,
    @ColumnInfo("reply_to") val replyTo: String? = null,
    /** Call provenance must survive process death and backup, without changing the UI contract. */
    @ColumnInfo(defaultValue = "'[]'") val chain: String = "[]",
)
