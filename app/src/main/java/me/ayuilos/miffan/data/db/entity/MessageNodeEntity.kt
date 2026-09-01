package me.ayuilos.miffan.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_node",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversation_id"),
        Index(value = ["conversation_id", "parent_id"]),
    ]
)
data class MessageNodeEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("node_index")
    val nodeIndex: Int,
    @ColumnInfo("parent_id", defaultValue = "")
    val parentId: String,
    @ColumnInfo("selected_child_id", defaultValue = "")
    val selectedChildId: String,
    @ColumnInfo("message")
    val message: String, // JSON serialized UIMessage
    @ColumnInfo("revision", defaultValue = "0")
    val revision: Long,
)
