package me.ayuilos.miffan.data.appsettings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.ayuilos.miffan.data.bugreport.redactDiagnosticText
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.onboarding.onboardingHint
import me.ayuilos.miffan.data.onboarding.withApiKey
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import java.net.URI
import kotlin.uuid.Uuid

const val CONNECT_PROVIDER_TOOL = "connect_provider"

/** Models named in a connect result; the rest are only counted. */
internal const val CONNECT_RESULT_MODELS = 60

private const val MAX_CUSTOM_NAME = 40

enum class CustomProviderApi(val id: String) {
    OPENAI("openai"), GOOGLE("google"), CLAUDE("claude");

    companion object {
        fun of(id: String?): CustomProviderApi? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/**
 * The one service a secure key card fills. The card shows [name] and [origin]; the key typed into it can only
 * be stored for this target, and only while the target still sends requests to [origin].
 */
sealed interface ConnectTarget {
    val name: String
    val origin: String

    /** A provider already in the settings (a preset or one the owner added); its address is never changed here. */
    data class Existing(
        val providerId: Uuid,
        override val name: String,
        override val origin: String,
        val hasKey: Boolean,
        val keyUrl: String?,
    ) : ConnectTarget

    /** A service the partner proposes by address; nothing exists until the owner's key passes or is saved. */
    data class Custom(
        override val name: String,
        val api: CustomProviderApi,
        val baseUrl: String,
        override val origin: String,
    ) : ConnectTarget
}

sealed interface ConnectTargetResult {
    data class Ok(val target: ConnectTarget) : ConnectTargetResult

    /** [reason] goes back to the partner; it never holds anything secret. */
    data class Invalid(val reason: String) : ConnectTargetResult
}

/** Reads a connect_provider call against the current settings. */
fun resolveConnectTarget(input: JsonElement, settings: Settings): ConnectTargetResult {
    val params = input as? JsonObject ?: return ConnectTargetResult.Invalid("Arguments must be an object.")
    val providerId = params.text("provider_id")
    val custom = params["custom"] as? JsonObject
    return when {
        providerId != null && custom != null -> ConnectTargetResult.Invalid("Give either provider_id or custom, not both.")
        providerId != null -> resolveExisting(providerId, settings)
        custom != null -> resolveCustom(custom)
        else -> ConnectTargetResult.Invalid("Give provider_id (from app_settings_catalog) or custom.")
    }
}

private fun resolveExisting(rawId: String, settings: Settings): ConnectTargetResult {
    val id = runCatching { Uuid.parse(rawId) }.getOrNull()
    val provider = id?.let { settings.providers.firstOrNull { it.id == id } }
        ?: return ConnectTargetResult.Invalid("No provider has this id. Call app_settings_catalog for the ids.")
    if (provider is ProviderSetting.OpenAI && provider.authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION) {
        return ConnectTargetResult.Invalid("This provider signs in with a ChatGPT account in the browser, not with a key. " +
            "Ask the owner to sign in on the provider's settings page.")
    }
    if (provider is ProviderSetting.Google && provider.vertexAI) {
        return ConnectTargetResult.Invalid("Vertex AI can only be set up on the provider's settings page.")
    }
    val hint = onboardingHint(provider.id)
    if (hint?.needsAddress == true) {
        return ConnectTargetResult.Invalid("This service runs at the owner's own address. Ask them to set it up on its settings page.")
    }
    val origin = provider.displayOrigin()
        ?: return ConnectTargetResult.Invalid("The provider's address is not valid. Ask the owner to fix it on its settings page.")
    return ConnectTargetResult.Ok(
        ConnectTarget.Existing(provider.id, provider.name, origin, hasKey = provider.keyStatus() == "set", keyUrl = hint?.keyUrl)
    )
}

private fun resolveCustom(custom: JsonObject): ConnectTargetResult {
    val name = custom.text("name")?.trim().orEmpty()
    if (name.isEmpty() || name.length > MAX_CUSTOM_NAME) {
        return ConnectTargetResult.Invalid("custom.name must be 1–$MAX_CUSTOM_NAME characters.")
    }
    val api = CustomProviderApi.of(custom.text("api"))
        ?: return ConnectTargetResult.Invalid("custom.api must be openai, google or claude.")
    val baseUrl = custom.text("base_url")?.trim()?.trimEnd('/').orEmpty()
    val uri = runCatching { URI(baseUrl) }.getOrNull()
    val problem = when {
        uri == null || uri.scheme?.lowercase() !in setOf("https", "http") -> "custom.base_url must start with https:// or http://."
        uri.host.isNullOrBlank() -> "custom.base_url has no host."
        uri.rawUserInfo != null -> "custom.base_url must not contain a user name or password."
        uri.rawQuery != null || uri.rawFragment != null -> "custom.base_url must not contain a query or fragment."
        else -> null
    }
    if (problem != null) return ConnectTargetResult.Invalid(problem)
    val origin = displayOriginOf(baseUrl) ?: return ConnectTargetResult.Invalid("custom.base_url is not a valid address.")
    return ConnectTargetResult.Ok(ConnectTarget.Custom(name, api, baseUrl, origin))
}

/** The provider a key is checked against: the saved one with [key], or the proposed custom service. */
fun ConnectTarget.providerWith(settings: Settings, key: String): ProviderSetting? = when (this) {
    is ConnectTarget.Existing -> settings.providers.firstOrNull { it.id == providerId }?.withApiKey(key)
    is ConnectTarget.Custom -> newCustomProvider(Uuid.random(), key)
}

private fun ConnectTarget.Custom.newCustomProvider(id: Uuid, key: String): ProviderSetting = when (api) {
    CustomProviderApi.OPENAI -> ProviderSetting.OpenAI(id = id, name = name, baseUrl = baseUrl, apiKey = key)
    CustomProviderApi.GOOGLE -> ProviderSetting.Google(id = id, name = name, baseUrl = baseUrl, apiKey = key)
    CustomProviderApi.CLAUDE -> ProviderSetting.Claude(id = id, name = name, baseUrl = baseUrl, apiKey = key)
}

sealed interface ConnectSave {
    data class Saved(val settings: Settings, val provider: ProviderSetting) : ConnectSave

    /** The provider was removed, or now sends requests elsewhere than the card showed; nothing was stored. */
    data object TargetChanged : ConnectSave
}

/**
 * Stores [key] for [target] only. An existing provider must still send requests to the origin the card showed;
 * its address, models and everything else stay as they are. A custom service is added, switched on, without models.
 */
fun Settings.withConnectedProvider(target: ConnectTarget, key: String, newId: Uuid = Uuid.random()): ConnectSave {
    val trimmed = key.trim()
    return when (target) {
        is ConnectTarget.Existing -> {
            val current = providers.firstOrNull { it.id == target.providerId }
            if (current == null || current.displayOrigin() != target.origin) return ConnectSave.TargetChanged
            val connected = current.withApiKey(trimmed).copyProvider(enabled = true)
            ConnectSave.Saved(copy(providers = providers.map { if (it.id == current.id) connected else it }), connected)
        }
        is ConnectTarget.Custom -> {
            val added = target.newCustomProvider(newId, trimmed)
            ConnectSave.Saved(copy(providers = providers + added), added)
        }
    }
}

/**
 * The only thing the partner learns from a secure key card. Built by the app from the saved provider and the
 * model list; the key never reaches it.
 */
fun connectResultJson(provider: ProviderSetting, checked: Boolean, models: List<Model>): String = buildJsonObject {
    put("status", if (checked) "connected" else "saved_unchecked")
    put("provider_id", provider.id.toString())
    put("provider_name", provider.name)
    if (checked) {
        put("model_count", models.size)
        putJsonArray("models") {
            models.take(CONNECT_RESULT_MODELS).forEach { model ->
                addJsonObject {
                    put("model_id", model.modelId)
                    put("name", model.displayName.ifBlank { model.modelId })
                }
            }
        }
    } else {
        put("note", "The owner saved the key without checking it because the service could not be reached.")
    }
    put("next", "No models were added to the provider. Tell the owner the service is connected; they can add models " +
        "on the provider's settings page.")
}.toString()

/** Error text from a service, safe to show on the card: the key and anything that looks like a credential removed. */
fun scrubServiceDetail(detail: String, key: String): String {
    val trimmed = key.trim()
    val withoutKey = if (trimmed.length >= 4) detail.replace(trimmed, "[已隐藏]") else detail
    return redactDiagnosticText(withoutKey).take(200)
}

/** "sk-7f3a••••••0c21": shown to the owner only, never to the partner. */
fun maskedKey(key: String): String {
    val trimmed = key.trim()
    return if (trimmed.length > 12) trimmed.take(4) + "••••••" + trimmed.takeLast(4) else "••••••"
}

private fun JsonObject.text(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)
    ?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
