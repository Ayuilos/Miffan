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
    fun pin(chatId: Uuid): ChatOrganization {
        val current = retainChats((pinned + groups.flatMap { it.chatIds }).toSet())
        return if (chatId in current.pinned) current else current.copy(pinned = current.pinned + chatId)
    }

    fun unpin(chatId: Uuid): ChatOrganization {
        val current = retainChats((pinned + groups.flatMap { it.chatIds }).toSet())
        return if (chatId !in current.pinned) current
        else current.copy(pinned = current.pinned.filterNot { it == chatId })
    }

    /** Moves [chatId] into [groupId] (appended), or out of every group when [groupId] is null. */
    fun moveToGroup(chatId: Uuid, groupId: Uuid?): ChatOrganization {
        val current = retainChats((pinned + groups.flatMap { it.chatIds }).toSet())
        val target = current.groups.indexOfFirst { it.id == groupId }
        if (groupId != null && target < 0) return current
        val moved = current.groups.mapIndexed { index, group ->
            val members = group.chatIds.filterNot { it == chatId }
            group.copy(chatIds = if (index == target) members + chatId else members)
        }
        return if (moved == current.groups) current else current.copy(groups = moved)
    }

    /** Appends a group named [name] (trimmed, non-blank), optionally moving [chatId] into it. */
    fun createGroup(name: String, chatId: Uuid? = null, id: Uuid = Uuid.random()): ChatOrganization {
        val current = retainChats((pinned + groups.flatMap { it.chatIds }).toSet())
        val trimmed = name.trim().take(24)
        if (trimmed.isEmpty()) return current
        val created = current.copy(groups = current.groups + ChatGroup(id = id, name = trimmed))
        return if (chatId == null) created else created.moveToGroup(chatId, id)
    }

    fun renameGroup(groupId: Uuid, name: String): ChatOrganization {
        val current = retainChats((pinned + groups.flatMap { it.chatIds }).toSet())
        val trimmed = name.trim().take(24)
        if (trimmed.isEmpty()) return current
        val renamed = current.groups.map { if (it.id == groupId) it.copy(name = trimmed) else it }
        return if (renamed == current.groups) current else current.copy(groups = renamed)
    }

    /** Moves a group one place up (-1) or down (+1); out-of-range moves are no-ops. */
    fun moveGroup(groupId: Uuid, offset: Int): ChatOrganization {
        val current = retainChats((pinned + groups.flatMap { it.chatIds }).toSet())
        if (offset != -1 && offset != 1) return current
        val index = current.groups.indexOfFirst { it.id == groupId }
        val target = index + offset
        if (index < 0 || target !in current.groups.indices) return current
        val moved = current.groups.toMutableList()
        moved[target] = current.groups[index]
        moved[index] = current.groups[target]
        return current.copy(groups = moved)
    }

    /** Deletes the group only; its chats become ungrouped. */
    fun deleteGroup(groupId: Uuid): ChatOrganization {
        val current = retainChats((pinned + groups.flatMap { it.chatIds }).toSet())
        val kept = current.groups.filterNot { it.id == groupId }
        return if (kept == current.groups) current else current.copy(groups = kept)
    }

    /** Drops pins and group members whose chat no longer exists. Groups themselves are kept. */
    fun retainChats(existing: Set<Uuid>): ChatOrganization {
        val keptPins = pinned.filter { it in existing }.distinct()
        // The first group wins if imported data contains duplicate membership.
        val seen = HashSet<Uuid>()
        val keptGroups = groups.map { group ->
            group.copy(chatIds = group.chatIds.filter { it in existing && seen.add(it) })
        }
        return if (keptPins == pinned && keptGroups == groups) this
        else copy(pinned = keptPins, groups = keptGroups)
    }
}

@Serializable
data class ChatGroup(
    val id: Uuid = Uuid.random(),
    val name: String,
    /** Member chats in the order they were added. */
    val chatIds: List<Uuid> = emptyList(),
)
