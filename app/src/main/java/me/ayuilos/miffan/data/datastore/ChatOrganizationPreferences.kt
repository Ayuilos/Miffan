package me.ayuilos.miffan.data.datastore

import me.ayuilos.miffan.data.model.ChatOrganization
import me.ayuilos.miffan.utils.JsonInstant

/** A damaged organization must not prevent the rest of the settings from loading. */
internal fun decodeChatOrganization(json: String?): ChatOrganization =
    json?.let { runCatching { JsonInstant.decodeFromString<ChatOrganization>(it) }.getOrNull() }
        ?: ChatOrganization()
