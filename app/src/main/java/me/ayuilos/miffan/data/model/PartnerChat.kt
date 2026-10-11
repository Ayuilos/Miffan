package me.ayuilos.miffan.data.model

import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.uuid.Uuid

/** The owner's participant id in multi-partner chats; partners use their assistant id. */
val OWNER_PARTICIPANT_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")

enum class PartnerChatKind {
    /** Two partners talking to each other; the owner can read along and join in. */
    DIRECT,

    /** Several partners and the owner (phase 4). */
    GROUP,
}

/** A chat with two or more partners. One-to-one chats with the owner stay IM threads. */
data class PartnerChat(
    val id: Uuid,
    val kind: PartnerChatKind,
    /** Partner members in the order they joined; the owner is always implicitly present. */
    val memberIds: List<Uuid>,
    val title: String = "",
    val createdAt: Instant,
)

/** One message in a [PartnerChat], already final (replies appear once their turn finishes). */
data class PartnerChatMessage(
    val id: Uuid,
    val chatId: Uuid,
    /** A partner's assistant id, or [OWNER_PARTICIPANT_ID]. */
    val senderId: Uuid,
    val text: String,
    val createdAt: Instant,
    /** One back-and-forth started by a partner's send_message call, or null for the owner's messages. */
    val exchangeId: Uuid? = null,
    /** The message this one answers, when known. */
    val replyTo: Uuid? = null,
)

/** A Chats list row for a [PartnerChat]. */
data class PartnerChatSummary(
    val chat: PartnerChat,
    val last: PartnerChatMessage?,
    /** Partners whose turn in this chat is running. */
    val typing: Set<Uuid>,
    val lastActivity: Instant,
)

/** Two partners the owner allowed to contact each other; stored normalized so (a, b) == (b, a). */
@Serializable
data class PartnerPair(val first: Uuid, val second: Uuid) {
    companion object {
        fun of(a: Uuid, b: Uuid): PartnerPair = if (a.toString() <= b.toString()) PartnerPair(a, b) else PartnerPair(b, a)
    }
}

/**
 * The `send_message` tool a partner uses to contact another partner or the owner.
 * The UI reads the call arguments and the JSON result to draw the thread row and approval card.
 */
object PartnerMessageTool {
    const val NAME = "send_message"

    /** Argument: a contact id from `list_contacts` ("owner" for the owner). */
    const val ARG_TO = "to"
    const val ARG_TEXT = "text"
    const val OWNER = "owner"

    /** Result fields. */
    const val RESULT_STATUS = "status"
    const val RESULT_CHAT_ID = "chat_id"
    const val RESULT_PARTNER_ID = "partner_id"
    const val RESULT_REPLY = "reply"

    /** The other partner answered within the wait; [RESULT_REPLY] holds the answer. */
    const val STATUS_REPLIED = "replied"

    /** The other partner is still working; its answer will arrive later as an event. */
    const val STATUS_PENDING = "pending"

    /** Delivered to the owner's thread. */
    const val STATUS_DELIVERED = "delivered"

    /** Not sent: unknown contact, loop, depth or round limit. The result explains why. */
    const val STATUS_REFUSED = "refused"
}
