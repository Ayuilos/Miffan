package me.ayuilos.miffan.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * How the easy-mode Chats list is organized: pinned chats and named groups. A chat id is the
 * partner's assistant id for a one-to-one thread (and later a multi-partner chat's id).
 * Stored in settings so it travels with backups; collapsed groups stay per device.
 */
@Serializable
data class ChatOrganization(
    /** Pinned chats in pin order, oldest first. */
    val pinned: List<Uuid> = emptyList(),
    /** Groups in display order. A chat belongs to at most one group. */
    val groups: List<ChatGroup> = emptyList(),
    /** The device-local pins of 4.0 have been moved here. */
    val legacyPinsMigrated: Boolean = false,
) {
    fun isPinned(chatId: Uuid): Boolean = chatId in pinned

    fun groupOf(chatId: Uuid): ChatGroup? = groups.firstOrNull { chatId in it.chatIds }

    /** Pins [chatId] after the existing pins; a no-op when already pinned. */
    fun pin(chatId: Uuid): ChatOrganization = TODO()

    fun unpin(chatId: Uuid): ChatOrganization = TODO()

    /** Moves [chatId] into [groupId] (appended), or out of every group when [groupId] is null. */
    fun moveToGroup(chatId: Uuid, groupId: Uuid?): ChatOrganization = TODO()

    /** Appends a group named [name] (trimmed, non-blank), optionally moving [chatId] into it. */
    fun createGroup(name: String, chatId: Uuid? = null, id: Uuid = Uuid.random()): ChatOrganization = TODO()

    fun renameGroup(groupId: Uuid, name: String): ChatOrganization = TODO()

    /** Moves a group one place up (-1) or down (+1); out-of-range moves are no-ops. */
    fun moveGroup(groupId: Uuid, offset: Int): ChatOrganization = TODO()

    /** Deletes the group only; its chats become ungrouped. */
    fun deleteGroup(groupId: Uuid): ChatOrganization = TODO()

    /** Drops pins and group members whose chat no longer exists. Groups themselves are kept. */
    fun retainChats(existing: Set<Uuid>): ChatOrganization = TODO()
}

@Serializable
data class ChatGroup(
    val id: Uuid = Uuid.random(),
    val name: String,
    /** Member chats in the order they were added. */
    val chatIds: List<Uuid> = emptyList(),
)
