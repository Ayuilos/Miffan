package me.ayuilos.miffan.data.ai.computer

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the last check of a computer found, so its page can say so without connecting. */
@Serializable
data class ComputerStatusRecord(
    val checkedAt: Long,
    /** Required items still to do; zero when partners can use the computer. */
    val pending: Int,
    /** A newer cua-driver release the computer may update to; null when it is current or unknown. */
    val updateTo: String? = null,
)

/**
 * The last check result per computer, on this phone only. Losing it costs nothing but the summary
 * line on the computer's page until the next check, so it lives in plain preferences.
 */
class ComputerStatusStore(context: Context) {
    private val preferences = context.getSharedPreferences("computer_status", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _records = MutableStateFlow(load())
    val records: StateFlow<Map<String, ComputerStatusRecord>> = _records.asStateFlow()

    fun put(hostId: String, record: ComputerStatusRecord) = save { it + (hostId to record) }

    /** Changes the saved record of [hostId], if there is one. */
    fun update(hostId: String, change: (ComputerStatusRecord) -> ComputerStatusRecord) =
        save { records -> records[hostId]?.let { records + (hostId to change(it)) } ?: records }

    fun remove(hostId: String) = save { it - hostId }

    private fun save(change: (Map<String, ComputerStatusRecord>) -> Map<String, ComputerStatusRecord>) {
        _records.update(change)
        preferences.edit { putString(KEY, json.encodeToString(_records.value)) }
    }

    private fun load(): Map<String, ComputerStatusRecord> =
        preferences.getString(KEY, null)?.let { runCatching { json.decodeFromString<Map<String, ComputerStatusRecord>>(it) }.getOrNull() }
            ?: emptyMap()

    private companion object {
        const val KEY = "records"
    }
}
