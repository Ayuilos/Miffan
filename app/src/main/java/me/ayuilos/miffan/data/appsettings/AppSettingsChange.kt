package me.ayuilos.miffan.data.appsettings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.ayuilos.miffan.data.datastore.DEFAULT_PROVIDERS
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.onboarding.withApiKey
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelCatalog
import me.rerere.ai.registry.ModelRegistry
import kotlin.uuid.Uuid

const val CHANGE_APP_SETTINGS_TOOL = "change_app_settings"

internal const val MAX_CHANGES = 30
private const val MAX_MODELS_PER_CHANGE = 50
private const val MAX_PROVIDER_NAME = 40
private const val MAX_MODEL_ID = 200
private const val MAX_MODEL_NAME = 80

/** The app-wide model slots a partner may set; [optional] ones can be cleared to follow the fast model. */
enum class DefaultModelRole(val id: String, val optional: Boolean = false) {
    CHAT("chat"), FAST("fast"), TITLE("title", optional = true), SUGGESTION("suggestion", optional = true),
    TRANSLATE("translate"), OCR("ocr"), COMPRESS("compress");

    fun get(settings: Settings): Uuid? = when (this) {
        CHAT -> settings.chatModelId
        FAST -> settings.fastModelId
        TITLE -> settings.titleModelId
        SUGGESTION -> settings.suggestionModelId
        TRANSLATE -> settings.translateModeId
        OCR -> settings.ocrModelId
        COMPRESS -> settings.compressModelId
    }

    /** [id] null clears an optional slot; required slots are never given null. */
    fun set(settings: Settings, id: Uuid?): Settings = when (this) {
        CHAT -> settings.copy(chatModelId = requireNotNull(id))
        FAST -> settings.copy(fastModelId = requireNotNull(id))
        TITLE -> settings.copy(titleModelId = id)
        SUGGESTION -> settings.copy(suggestionModelId = id)
        TRANSLATE -> settings.copy(translateModeId = requireNotNull(id))
        OCR -> settings.copy(ocrModelId = requireNotNull(id))
        COMPRESS -> settings.copy(compressModelId = requireNotNull(id))
    }

    companion object {
        fun of(id: String?): DefaultModelRole? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/**
 * One change as the owner reads it on the card and in the record. Names are captured when the change is planned,
 * so the record still reads right after later renames. Also part of the tool result, so nothing secret goes here.
 */
@Serializable
sealed interface AppSettingsLine {
    @Serializable @SerialName("provider_enabled")
    data class ProviderEnabled(val provider: String, val enabled: Boolean) : AppSettingsLine

    @Serializable @SerialName("provider_renamed")
    data class ProviderRenamed(val from: String, val to: String) : AppSettingsLine

    /** [clearsKey]: the saved key is removed, because it was meant for the old address. */
    @Serializable @SerialName("provider_address")
    data class ProviderAddress(val provider: String, val origin: String, val clearsKey: Boolean) : AppSettingsLine

    @Serializable @SerialName("models_added")
    data class ModelsAdded(val provider: String, val models: List<String>) : AppSettingsLine

    @Serializable @SerialName("models_removed")
    data class ModelsRemoved(val provider: String, val models: List<String>) : AppSettingsLine

    /** Null fields stay as they are. */
    @Serializable @SerialName("model_updated")
    data class ModelUpdated(
        val provider: String,
        val model: String,
        val name: String? = null,
        val tools: Boolean? = null,
        val reasoning: Boolean? = null,
        val imageInput: Boolean? = null,
    ) : AppSettingsLine

    /** [model] null: the partner follows the default chat model. */
    @Serializable @SerialName("partner_model")
    data class PartnerModel(val partnerId: String, val partner: String, val model: String? = null, val provider: String? = null) : AppSettingsLine

    /** [model] null: an optional slot follows the fast model again. */
    @Serializable @SerialName("default_model")
    data class DefaultModel(val role: String, val model: String? = null, val provider: String? = null) : AppSettingsLine
}

val AppSettingsLineJson = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = true
    explicitNulls = false
}

sealed interface AppSettingsPlan {
    data class Ok(val settings: Settings, val lines: List<AppSettingsLine>) : AppSettingsPlan

    /** [reason] goes back to the partner. */
    data class Invalid(val reason: String) : AppSettingsPlan
}

private class PlanError(message: String) : Exception(message)

private fun fail(message: String): Nothing = throw PlanError(message)

/**
 * Applies a change_app_settings call to [settings] in order, so a later change can use a model an earlier one added.
 * Never touches a key, except that moving a custom service to another address clears the key saved for the old one.
 * Preset addresses, credentials, headers and per-model provider overrides are out of reach.
 */
fun planAppSettingsChange(
    input: JsonElement,
    settings: Settings,
    catalog: ModelCatalog = ModelCatalog.EMPTY,
    newId: () -> Uuid = Uuid::random,
): AppSettingsPlan = try {
    val changes = ((input as? JsonObject)?.get("changes") as? JsonArray) ?: fail("changes must be an array.")
    if (changes.isEmpty()) fail("changes is empty.")
    if (changes.size > MAX_CHANGES) fail("At most $MAX_CHANGES changes at a time.")
    var working = settings
    val lines = mutableListOf<AppSettingsLine>()
    val assigned = mutableSetOf<Uuid>()
    val disabled = mutableSetOf<Uuid>()
    changes.forEachIndexed { index, element ->
        val change = element as? JsonObject ?: fail("changes[$index] must be an object.")
        try {
            val step = applyOne(change, working, catalog, newId)
            working = step.settings
            step.line?.let(lines::add)
            step.assigned?.let(assigned::add)
            step.disabled?.let(disabled::add)
        } catch (e: PlanError) {
            fail("changes[$index]: ${e.message}")
        }
    }
    if (lines.isEmpty()) fail("Nothing would change: the settings already look like this.")
    checkReferences(settings, working, assigned, disabled)
    AppSettingsPlan.Ok(working.copy(favoriteModels = working.favoriteModels.filter { working.hasModel(it) }), lines)
} catch (e: PlanError) {
    AppSettingsPlan.Invalid(e.message.orEmpty())
}

private class Step(val settings: Settings, val line: AppSettingsLine?, val assigned: Uuid? = null, val disabled: Uuid? = null)

private fun applyOne(change: JsonObject, settings: Settings, catalog: ModelCatalog, newId: () -> Uuid): Step {
    return when (val type = change.text("type")) {
        "set_provider_enabled" -> {
            val provider = settings.provider(change)
            val enabled = change.bool("enabled") ?: fail("enabled (true or false) is required.")
            if (provider.enabled == enabled) Step(settings, null)
            else Step(settings.replace(provider.copyProvider(enabled = enabled)), AppSettingsLine.ProviderEnabled(provider.name, enabled),
                disabled = provider.id.takeIf { !enabled })
        }
        "rename_provider" -> {
            val provider = settings.provider(change)
            val name = change.text("name")?.trim().orEmpty()
            if (name.isEmpty() || name.length > MAX_PROVIDER_NAME) fail("name must be 1–$MAX_PROVIDER_NAME characters.")
            if (name == provider.name) Step(settings, null)
            else Step(settings.replace(provider.copyProvider(name = name)), AppSettingsLine.ProviderRenamed(provider.name, name))
        }
        "set_provider_address" -> setAddress(change, settings)
        "add_models" -> addModels(change, settings, catalog, newId)
        "remove_models" -> {
            val provider = settings.provider(change)
            val ids = (change["model_ids"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull?.trim().orEmpty() }
                ?.takeIf { it.isNotEmpty() } ?: fail("model_ids must be a non-empty array.")
            val removed = ids.distinct().map { id -> provider.models.firstOrNull { it.modelId == id } ?: fail("${provider.name} has no model \"$id\".") }
            Step(settings.replace(provider.copyProvider(models = provider.models - removed.toSet())),
                AppSettingsLine.ModelsRemoved(provider.name, removed.map { it.label() }))
        }
        "update_model" -> updateModel(change, settings)
        "set_partner_model" -> {
            val partnerId = change.text("partner_id")?.let { runCatching { Uuid.parse(it.trim()) }.getOrNull() }
            val partner = partnerId?.let { id -> settings.assistants.firstOrNull { it.id == id } }
                ?: fail("No partner has this partner_id. Call app_settings_catalog for the ids.")
            val ref = modelRef(change, allowNull = "default")
            val model = ref?.let { settings.chatModel(it) }
            if (partner.chatModelId == model?.second?.id) return Step(settings, null)
            Step(settings.copy(assistants = settings.assistants.map { if (it.id == partner.id) it.copy(chatModelId = model?.second?.id) else it }),
                AppSettingsLine.PartnerModel(partner.id.toString(), partner.name, model?.second?.label(), model?.first?.name),
                assigned = model?.second?.id)
        }
        "set_default_model" -> {
            val role = DefaultModelRole.of(change.text("role"))
                ?: fail("role must be one of ${DefaultModelRole.entries.joinToString { it.id }}.")
            val ref = modelRef(change, allowNull = "follow_fast".takeIf { role.optional })
            val model = ref?.let { settings.chatModel(it) }
            if (role.get(settings) == model?.second?.id) return Step(settings, null)
            Step(role.set(settings, model?.second?.id), AppSettingsLine.DefaultModel(role.id, model?.second?.label(), model?.first?.name),
                assigned = model?.second?.id)
        }
        null -> fail("type is required.")
        else -> fail("Unknown type \"$type\".")
    }
}

private fun setAddress(change: JsonObject, settings: Settings): Step {
    val provider = settings.provider(change)
    if (provider.isPreset()) fail("${provider.name} is a built-in service; its address can only be changed by the owner on its settings page.")
    if (provider is ProviderSetting.OpenAI && provider.authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION) fail("This provider has no address to change.")
    if (provider is ProviderSetting.Google && provider.vertexAI) fail("Vertex AI can only be set up on the provider's settings page.")
    val baseUrl = change.text("base_url")?.trim()?.trimEnd('/').orEmpty()
    baseUrlProblem(baseUrl, "base_url")?.let(::fail)
    if (baseUrl == provider.baseUrl()?.trim()?.trimEnd('/')) return Step(settings, null)
    val clearsKey = provider.keyStatus() == "set"
    val moved = provider.withBaseUrl(baseUrl).let { if (clearsKey) it.withApiKey("") else it }
    return Step(settings.replace(moved), AppSettingsLine.ProviderAddress(provider.name, displayOriginOf(baseUrl).orEmpty(), clearsKey))
}

private fun addModels(change: JsonObject, settings: Settings, catalog: ModelCatalog, newId: () -> Uuid): Step {
    val provider = settings.provider(change)
    val items = (change["models"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: fail("models must be a non-empty array.")
    if (items.size > MAX_MODELS_PER_CHANGE) fail("At most $MAX_MODELS_PER_CHANGE models at a time.")
    val added = mutableListOf<Model>()
    items.forEachIndexed { index, element ->
        val item = element as? JsonObject ?: fail("models[$index] must be an object.")
        val modelId = item.text("model_id")?.trim().orEmpty()
        if (modelId.isEmpty() || modelId.length > MAX_MODEL_ID || modelId.any { it.isWhitespace() }) {
            fail("models[$index].model_id must be the service's model id, without spaces.")
        }
        if (provider.models.any { it.modelId == modelId } || added.any { it.modelId == modelId }) return@forEachIndexed
        val name = item.text("name")?.trim()?.take(MAX_MODEL_NAME)
        val inferred = ModelRegistry.resolveCapabilities(Model(modelId = modelId, displayName = name ?: modelId, id = newId()), catalog)
        added += inferred.withAbilities(item.bool("tools"), item.bool("reasoning"), item.bool("image_input"))
    }
    if (added.isEmpty()) return Step(settings, null)
    return Step(settings.replace(provider.copyProvider(models = provider.models + added)),
        AppSettingsLine.ModelsAdded(provider.name, added.map { it.label() }))
}

private fun updateModel(change: JsonObject, settings: Settings): Step {
    val provider = settings.provider(change)
    val modelId = change.text("model_id")?.trim() ?: fail("model_id is required.")
    val model = provider.models.firstOrNull { it.modelId == modelId } ?: fail("${provider.name} has no model \"$modelId\".")
    val name = change.text("name")?.trim()?.take(MAX_MODEL_NAME)
    val tools = change.bool("tools")
    val reasoning = change.bool("reasoning")
    val imageInput = change.bool("image_input")
    if (name == null && tools == null && reasoning == null && imageInput == null) fail("Give name, tools, reasoning or image_input.")
    val updated = model.withAbilities(tools, reasoning, imageInput).let { if (name != null) it.copy(displayName = name) else it }
    if (updated == model) return Step(settings, null)
    return Step(settings.replace(provider.copyProvider(models = provider.models.map { if (it.id == model.id) updated else it })),
        AppSettingsLine.ModelUpdated(provider.name, model.label(),
            name = name?.takeIf { it != model.displayName },
            tools = tools?.takeIf { it != (ModelAbility.TOOL in model.abilities) },
            reasoning = reasoning?.takeIf { it != (ModelAbility.REASONING in model.abilities) },
            imageInput = imageInput?.takeIf { it != (Modality.IMAGE in model.inputModalities) }))
}

/**
 * A model this change assigns must be on a service that is on, and a removed or switched-off model must not stay in
 * use: either would leave a partner or a feature unable to reply.
 */
private fun checkReferences(before: Settings, after: Settings, assigned: Set<Uuid>, disabled: Set<Uuid>) {
    val inUse = buildList {
        DefaultModelRole.entries.forEach { role -> role.get(after)?.let { add(it to role.id + " default model") } }
        after.imageGenerationModelId.let { add(it to "image generation model") }
        after.imageToolModelId?.let { add(it to "image tool model") }
        after.assistants.forEach { assistant -> assistant.chatModelId?.let { add(it to "partner ${assistant.name}") } }
    }
    val removed = before.providers.flatMap { it.models }.map { it.id }.filterNot { after.hasModel(it) }.toSet()
    inUse.firstOrNull { it.first in removed }?.let { (_, user) ->
        fail("A removed model is still used as the $user. Change that first, earlier in the same changes.")
    }
    inUse.forEach { (id, user) ->
        val provider = after.providers.firstOrNull { p -> p.models.any { it.id == id } } ?: return@forEach
        if (provider.enabled) return@forEach
        if (id in assigned) fail("${provider.name} is switched off; switch it on first (set_provider_enabled) or pick another model.")
        if (provider.id in disabled) fail("${provider.name} is still used as the $user. Change that first, earlier in the same changes.")
    }
}

/** Reads `model`: `{provider_id, model_id}`, or the [allowNull] word for "no model of its own". */
private fun modelRef(change: JsonObject, allowNull: String?): Pair<Uuid, String>? {
    val value = change["model"]
    if (value is JsonPrimitive && value.isString && allowNull != null && value.content.trim() == allowNull) return null
    val obj = value as? JsonObject
        ?: fail(if (allowNull != null) "model must be {provider_id, model_id} or \"$allowNull\"." else "model must be {provider_id, model_id}.")
    val providerId = obj.text("provider_id")?.let { runCatching { Uuid.parse(it.trim()) }.getOrNull() }
        ?: fail("model.provider_id is not a provider id. Call app_settings_catalog for the ids.")
    val modelId = obj.text("model_id")?.trim() ?: fail("model.model_id is required.")
    return providerId to modelId
}

private fun Settings.chatModel(ref: Pair<Uuid, String>): Pair<ProviderSetting, Model> {
    val provider = providers.firstOrNull { it.id == ref.first } ?: fail("No provider has this id. Call app_settings_catalog for the ids.")
    val model = provider.models.firstOrNull { it.modelId == ref.second }
        ?: fail("${provider.name} has no model \"${ref.second}\". Add it first with add_models, earlier in the same changes.")
    if (model.type != ModelType.CHAT) fail("\"${ref.second}\" is not a chat model.")
    return provider to model
}

private fun Settings.provider(change: JsonObject): ProviderSetting {
    val id = change.text("provider_id")?.let { runCatching { Uuid.parse(it.trim()) }.getOrNull() }
    return id?.let { providers.firstOrNull { it.id == id } } ?: fail("No provider has this provider_id. Call app_settings_catalog for the ids.")
}

private fun Settings.replace(provider: ProviderSetting) = copy(providers = providers.map { if (it.id == provider.id) provider else it })

internal fun Settings.hasModel(id: Uuid) = providers.any { provider -> provider.models.any { it.id == id } }

private fun ProviderSetting.isPreset() = DEFAULT_PROVIDERS.any { it.id == id }

private fun Model.label() = displayName.ifBlank { modelId }

private fun Model.withAbilities(tools: Boolean?, reasoning: Boolean?, imageInput: Boolean?): Model {
    if (tools == null && reasoning == null && imageInput == null) return this
    fun List<ModelAbility>.with(ability: ModelAbility, on: Boolean?) = when (on) {
        null -> this
        true -> if (ability in this) this else this + ability
        false -> this - ability
    }
    val modalities = when (imageInput) {
        null -> inputModalities
        true -> if (Modality.IMAGE in inputModalities) inputModalities else inputModalities + Modality.IMAGE
        false -> inputModalities - Modality.IMAGE
    }
    return copy(abilities = abilities.with(ModelAbility.TOOL, tools).with(ModelAbility.REASONING, reasoning),
        inputModalities = modalities, capabilitiesEdited = true)
}

internal fun ProviderSetting.baseUrl(): String? = when (this) {
    is ProviderSetting.OpenAI -> baseUrl
    is ProviderSetting.Google -> baseUrl
    is ProviderSetting.Claude -> baseUrl
}

private fun ProviderSetting.withBaseUrl(url: String): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(baseUrl = url)
    is ProviderSetting.Google -> copy(baseUrl = url)
    is ProviderSetting.Claude -> copy(baseUrl = url)
}

/** Why [url] cannot be a service address, or null when it can: http(s), a host, and no credentials, query or fragment. */
internal fun baseUrlProblem(url: String, field: String): String? {
    val uri = runCatching { java.net.URI(url) }.getOrNull()
    return when {
        uri == null || uri.scheme?.lowercase() !in setOf("https", "http") -> "$field must start with https:// or http://."
        uri.host.isNullOrBlank() -> "$field has no host."
        uri.rawUserInfo != null -> "$field must not contain a user name or password."
        uri.rawQuery != null || uri.rawFragment != null -> "$field must not contain a query or fragment."
        displayOriginOf(url) == null -> "$field is not a valid address."
        else -> null
    }
}

/** The result the partner gets after the owner approved: what changed, in the app's words. */
fun appSettingsResultJson(lines: List<AppSettingsLine>): String = kotlinx.serialization.json.buildJsonObject {
    put("success", JsonPrimitive(true))
    put("applied", AppSettingsLineJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(AppSettingsLine.serializer()), lines))
    val cleared = lines.filterIsInstance<AppSettingsLine.ProviderAddress>().filter { it.clearsKey }
    if (cleared.isNotEmpty()) {
        put("note", JsonPrimitive("The key of ${cleared.joinToString { it.provider }} was cleared because its address changed. " +
            "If the owner wants to use it, call connect_provider so they can enter a key for the new address."))
    }
}.toString()

/** Decodes the lines of an applied change from its tool result. */
fun appSettingsResultLines(output: String): List<AppSettingsLine>? = runCatching {
    val applied = (AppSettingsLineJson.parseToJsonElement(output) as JsonObject)["applied"] ?: return null
    AppSettingsLineJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(AppSettingsLine.serializer()), applied)
}.getOrNull()

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { if (it.isString) it.content.toBooleanStrictOrNull() else it.booleanOrNull }
