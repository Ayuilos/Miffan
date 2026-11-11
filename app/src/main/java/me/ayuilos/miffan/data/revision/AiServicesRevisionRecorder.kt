package me.ayuilos.miffan.data.revision

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.ayuilos.miffan.data.appsettings.DefaultModelRole
import me.ayuilos.miffan.data.appsettings.displayOrigin
import me.ayuilos.miffan.data.datastore.DEFAULT_PROVIDERS
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

private const val TAG = "AiServicesRevisions"

/** The one subject id of [RevisionSubject.AI_SERVICES]: there is a single set of AI services. */
const val AI_SERVICES_SUBJECT_ID = "app"

/**
 * Records the AI services (providers, their models, and the default models) whenever they change, so a partner's
 * change can be undone. Snapshots never hold a credential, a custom header or body, or a per-model provider override:
 * restoring keeps the ones the app has now.
 */
class AiServicesRevisionRecorder(private val revisions: RevisionRepository) {
    suspend fun onSettingsChanged(old: Settings, new: Settings) {
        if (old.providers == new.providers && DefaultModelRole.entries.all { it.get(old) == it.get(new) } &&
            old.selectedSearchId() == new.selectedSearchId()) return
        val before = aiServicesSnapshot(old)
        val after = aiServicesSnapshot(new)
        if (before == after) return
        try {
            revisions.record(RevisionSubject.AI_SERVICES, AI_SERVICES_SUBJECT_ID, before = before, after = after)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // History must never block saving the configuration itself.
            Log.e(TAG, "Unable to record AI services revision", e)
        }
    }
}

private val PROVIDER_SECRETS = setOf("apiKey", "privateKey", "serviceAccountEmail", "codexCredentials")
private val MODEL_PRIVATE = setOf("customHeaders", "customBodies", "providerOverwrite")

/**
 * One key per provider (`provider:<id>`, without models or secrets), per provider's model list (`models:<id>`), and
 * per default model slot (`default:<role>`), so undo can restore exactly what a change touched.
 */
fun aiServicesSnapshot(settings: Settings): String = JsonObject(buildMap {
    settings.providers.forEach { provider ->
        val json = JsonInstant.encodeToJsonElement(ProviderSetting.serializer(), provider).jsonObject
        put("provider:${provider.id}", JsonObject(json - PROVIDER_SECRETS - "models"))
        put("models:${provider.id}", JsonArray(provider.models.map { model ->
            JsonObject(JsonInstant.encodeToJsonElement(Model.serializer(), model).jsonObject - MODEL_PRIVATE)
        }))
    }
    DefaultModelRole.entries.forEach { role -> put("default:${role.id}", role.get(settings)?.let { JsonPrimitive(it.toString()) } ?: JsonNull) }
    put("search:selected", settings.selectedSearchId()?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
}).toString()

private fun Settings.selectedSearchId(): Uuid? = searchServices.getOrNull(searchServiceSelected)?.id

/**
 * Brings the AI services back to [snapshot], keeping today's keys, headers and overrides. A provider that would send
 * requests to a different address than it does now loses its key: a key is only ever sent where it was entered.
 */
fun Settings.restoreAiServices(snapshot: String): Settings {
    val values = JsonInstant.parseToJsonElement(snapshot).jsonObject
    val currentIds = providers.mapTo(HashSet()) { it.id.toString() }
    val kept = providers.mapNotNull { current ->
        val meta = values["provider:${current.id}"] as? JsonObject ?: return@mapNotNull null
        rebuildProvider(meta, values["models:${current.id}"], current)
    }
    val returning = values.keys.filter { it.startsWith("provider:") && it.removePrefix("provider:") !in currentIds }
        .mapNotNull { key -> rebuildProvider(values[key] as JsonObject, values["models:${key.removePrefix("provider:")}"], null) }
    var restored = copy(providers = kept + returning)
    DefaultModelRole.entries.forEach { role ->
        val value = values["default:${role.id}"] ?: return@forEach
        val id = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (id != null || role.optional) restored = role.set(restored, id)
    }
    (values["search:selected"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { id ->
        val index = restored.searchServices.indexOfFirst { it.id.toString() == id }
        if (index >= 0) restored = restored.copy(searchServiceSelected = index)
    }
    return restored
}

private fun rebuildProvider(meta: JsonObject, models: JsonElement?, current: ProviderSetting?): ProviderSetting? = runCatching {
    val decoded = JsonInstant.decodeFromJsonElement(ProviderSetting.serializer(), JsonObject(meta + ("models" to JsonArray(emptyList()))))
    val currentModels = current?.models.orEmpty().associateBy { it.id }
    val restoredModels = (models as? JsonArray).orEmpty().map { element ->
        val model = JsonInstant.decodeFromJsonElement(Model.serializer(), element)
        currentModels[model.id]?.let { now ->
            model.copy(customHeaders = now.customHeaders, customBodies = now.customBodies, providerOverwrite = now.providerOverwrite)
        } ?: model
    }
    // Composable descriptions are not serialized; take them from the provider as it is now, or its preset.
    val template = current ?: DEFAULT_PROVIDERS.firstOrNull { it.id == decoded.id }
    val rebuilt = decoded.copyProvider(
        models = restoredModels,
        builtIn = template?.builtIn ?: false,
        description = template?.description ?: {},
        shortDescription = template?.shortDescription ?: {},
    )
    rebuilt.withSecretsOf(current?.takeIf { it.displayOrigin() == rebuilt.displayOrigin() })
}.onFailure { Log.e(TAG, "Unable to restore provider", it) }.getOrNull()

/** [this] with the credentials of [other] (none when null or of another kind). */
internal fun ProviderSetting.withSecretsOf(other: ProviderSetting?): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> (other as? ProviderSetting.OpenAI).let { copy(apiKey = it?.apiKey.orEmpty(), codexCredentials = it?.codexCredentials) }
    is ProviderSetting.Google -> (other as? ProviderSetting.Google).let {
        copy(apiKey = it?.apiKey.orEmpty(), privateKey = it?.privateKey.orEmpty(), serviceAccountEmail = it?.serviceAccountEmail.orEmpty())
    }
    is ProviderSetting.Claude -> copy(apiKey = (other as? ProviderSetting.Claude)?.apiKey.orEmpty())
}
