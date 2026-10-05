package me.ayuilos.miffan.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    /** Epoch millis; 0 for memories created before sources were recorded. */
    @ColumnInfo("created_at", defaultValue = "0")
    val createdAt: Long = 0,
    @ColumnInfo("source_conversation_id", defaultValue = "")
    val sourceConversationId: String = "",
    @ColumnInfo("source_message_id", defaultValue = "")
    val sourceMessageId: String = "",
)
