package me.ayuilos.miffan.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One immutable version of an assistant configuration or a memory set. Versions form a linear
 * history per subject through [parentId]; restoring creates a new version pointing at [revertOf].
 */
@Entity(
    tableName = "revision",
    indices = [Index(value = ["subject_type", "subject_id", "created_at"])],
)
data class RevisionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("subject_type")
    val subjectType: String,
    @ColumnInfo("subject_id")
    val subjectId: String,
    @ColumnInfo("parent_id", defaultValue = "")
    val parentId: String,
    @ColumnInfo("author")
    val author: String,
    @ColumnInfo("summary", defaultValue = "")
    val summary: String,
    @ColumnInfo("snapshot")
    val snapshot: String,
    @ColumnInfo("trigger_conversation_id", defaultValue = "")
    val triggerConversationId: String,
    @ColumnInfo("trigger_message_id", defaultValue = "")
    val triggerMessageId: String,
    @ColumnInfo("trigger_tool_call_id", defaultValue = "")
    val triggerToolCallId: String,
    @ColumnInfo("revert_of", defaultValue = "")
    val revertOf: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
