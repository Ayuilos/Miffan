package me.ayuilos.miffan.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Who wrote a USER-role message when it was not the owner. Model APIs only know "user" and
 * "assistant", so another partner's words and system events enter a conversation as USER
 * messages; this marks them so they never carry the owner's authority (self-configuration,
 * permission grants, memory about the owner). A null origin on a node means the owner wrote it.
 */
@Serializable
sealed interface MessageOrigin {
    /** Another partner wrote this message. */
    @Serializable
    @SerialName("partner")
    data class Partner(
        val assistantId: Uuid,
        /** One back-and-forth between two partners. */
        val exchangeId: Uuid,
        /** Partners already on this call chain, oldest first; none of them may be contacted again. */
        val chain: List<Uuid> = emptyList(),
    ) : MessageOrigin

    /** Something happened that the partner should react to, such as a late reply from another partner. */
    @Serializable
    @SerialName("event")
    data class Event(
        /** Machine-readable kind, e.g. "partner_replied" or "debug". */
        val kind: String,
        /** The short line the owner sees in the timeline; the message text is what the model reads. */
        val summary: String,
        val sourceAssistantId: Uuid? = null,
        /** The multi-partner chat the event came from, if any. */
        val chatId: Uuid? = null,
    ) : MessageOrigin
}
