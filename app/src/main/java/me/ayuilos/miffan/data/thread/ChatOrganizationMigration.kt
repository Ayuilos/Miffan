package me.ayuilos.miffan.data.thread

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.awaitLoadedSettings
import kotlin.uuid.Uuid

/** Eagerly registered at application startup; never persists the placeholder Settings.dummy(). */
internal class ChatOrganizationMigration(
    private val settingsStore: SettingsStore,
    private val listState: ThreadListState,
) {
    private val mutex = Mutex()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                migrate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Legacy keys remain intact if persistence fails, so the next launch can retry.
                Log.e("ChatOrganizationMigration", "Unable to migrate chat organization", e)
            }
        }
    }

    internal suspend fun migrate() = mutex.withLock {
        while (true) {
            val current = settingsStore.settingsFlow.awaitLoadedSettings()
            val updated = current.withMigratedChatOrganization(listState.legacyPins())
            // Retry against the latest settings if a user edit or another migration won the race.
            if (updated === current || settingsStore.updateIfCurrent(current) { updated }) {
                listState.clearLegacyPins()
                break
            }
        }
    }
}

internal fun Settings.withMigratedChatOrganization(legacyPins: Set<String>): Settings {
    if (init) return this
    val existing = assistants.mapTo(HashSet()) { it.id }
    var organization = chatOrganization
    if (!organization.legacyPinsMigrated) {
        legacyPins.forEach { key ->
            val id = runCatching { Uuid.parse(key) }.getOrNull()
            if (id != null && id in existing) organization = organization.pin(id)
        }
        organization = organization.copy(legacyPinsMigrated = true)
    }
    organization = organization.retainChats(existing)
    return if (organization == chatOrganization) this else copy(chatOrganization = organization)
}
