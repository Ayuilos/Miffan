package me.ayuilos.miffan.data.repository.relay

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.workspace.relay.RelayInfoStore
import me.rerere.workspace.relay.StoredRelayInfo

/**
 * Keeps each relay's `/v1/info` across restarts (see [RelayInfoStore]). The info is public (edges
 * and policy), so plain preferences are enough; the relay's access code is never part of it.
 */
class RelayInfoPrefsStore(private val prefs: SharedPreferences) : RelayInfoStore {
    constructor(context: Context) : this(context.getSharedPreferences("relay_info", Context.MODE_PRIVATE))

    @Serializable
    private data class Entry(val at: Long, val info: String)

    override fun read(baseUrl: String): StoredRelayInfo? = runCatching {
        val entry = Json.decodeFromString<Entry>(prefs.getString(baseUrl, null) ?: return null)
        StoredRelayInfo(entry.at, entry.info)
    }.getOrNull()

    override fun write(baseUrl: String, info: StoredRelayInfo) {
        runCatching { prefs.edit().putString(baseUrl, Json.encodeToString(Entry(info.fetchedAtMillis, info.json))).apply() }
    }
}
