package me.ayuilos.miffan.data.partner

import me.ayuilos.miffan.data.model.*

internal fun interface PartnerGroupNotifications {
    suspend fun onMessage(chat: PartnerChat, message: PartnerChatMessage, trigger: PartnerChatMessage)
}

internal fun shouldNotifyGroupMessage(chat: PartnerChat, message: PartnerChatMessage, trigger: PartnerChatMessage, foreground: Boolean = false, enabled: Boolean = true): Boolean =
    !foreground && enabled && chat.kind == PartnerChatKind.GROUP && message.senderId != OWNER_PARTICIPANT_ID &&
        (OWNER_PARTICIPANT_ID in message.mentions ||
            (message.replyTo == trigger.id && trigger.senderId == OWNER_PARTICIPANT_ID))
