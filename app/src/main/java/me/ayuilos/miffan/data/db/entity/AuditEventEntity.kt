package me.ayuilos.miffan.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Deliberately no foreign keys: audit history survives removal of its subjects. */
@Entity(tableName = "audit_event", indices = [
    Index(value = ["kind", "tool_call_id"], unique = true),
    Index(value = ["assistant_id", "at"]),
    Index(value = ["host_id", "at"]),
])
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val kind: String,
    @ColumnInfo(name = "assistant_id") val assistantId: String? = null,
    @ColumnInfo(name = "conversation_id") val conversationId: String? = null,
    @ColumnInfo(name = "message_id") val messageId: String? = null,
    @ColumnInfo(name = "tool_call_id") val toolCallId: String? = null,
    @ColumnInfo(name = "host_id") val hostId: String? = null,
    @ColumnInfo(name = "host_name") val hostName: String? = null,
    @ColumnInfo(name = "tool_name") val toolName: String? = null,
    val summary: String,
    val decision: String? = null,
    val via: String? = null,
    @ColumnInfo(name = "requested_at") val requestedAt: Long? = null,
)
