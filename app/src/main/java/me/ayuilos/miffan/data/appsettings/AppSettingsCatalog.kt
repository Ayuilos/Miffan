package me.ayuilos.miffan.data.appsettings

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.ayuilos.miffan.data.datastore.DEFAULT_PROVIDERS
import me.ayuilos.miffan.data.datastore.Settings
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import java.net.URI

/** Models listed per provider in the catalog; the rest are only counted. */
internal const val CATALOG_MODELS_PER_PROVIDER = 40

/**
 * What a partner may read of the app's AI services: names, where requests go, whether a key is there, and
 * the models. Never a credential, nor anything derived from one (no prefix, suffix, length or hash), and no
 * custom headers, request bodies, per-model provider overrides or account details.
 */
fun buildAppSettingsCatalog(settings: Settings): JsonObject = buildJsonObject {
    val presets = DEFAULT_PROVIDERS.mapTo(HashSet()) { it.id }
    putJsonArray("providers") {
        settings.providers.forEach { provider ->
            addJsonObject {
                put("id", provider.id.toString())
                put("name", provider.name)
                put("api", provider.apiType())
                put("preset", provider.id in presets)
                provider.displayOrigin()?.let { put("host", it) }
                put("enabled", provider.enabled)
                put("key", provider.keyStatus())
                put("model_count", provider.models.size)
                putJsonArray("models") {
                    provider.models.take(CATALOG_MODELS_PER_PROVIDER).forEach { model ->
                        addJsonObject {
                            put("id", model.id.toString())
                            put("model_id", model.modelId)
                            put("name", model.displayName.ifBlank { model.modelId })
                            put("type", model.type.name.lowercase())
                        }
                    }
                }
            }
        }
    }
    putJsonObject("default_models") {
        put("chat", settings.chatModelId.toString())
        put("fast", settings.fastModelId.toString())
    }
    putJsonArray("partners") {
        settings.assistants.forEach { assistant ->
            addJsonObject {
                put("id", assistant.id.toString())
                put("name", assistant.name)
                put("model", assistant.chatModelId?.toString() ?: "default")
            }
        }
    }
    putJsonArray("notes") {
        add("key is set, unset, signed_in or signed_out; the key itself is never readable.")
        add("A partner's model \"default\" means it follows default_models.chat.")
    }
}

internal fun ProviderSetting.apiType(): String = when (this) {
    is ProviderSetting.OpenAI -> "openai"
    is ProviderSetting.Google -> "google"
    is ProviderSetting.Claude -> "claude"
}

/** Whether the provider can authenticate, without revealing anything about the credential. */
internal fun ProviderSetting.keyStatus(): String = when (this) {
    is ProviderSetting.OpenAI -> if (authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION) {
        if (codexCredentials != null) "signed_in" else "signed_out"
    } else apiKey.setOrUnset()
    is ProviderSetting.Google -> if (vertexAI && useServiceAccount) privateKey.setOrUnset() else apiKey.setOrUnset()
    is ProviderSetting.Claude -> apiKey.setOrUnset()
}

private fun String.setOrUnset() = if (isNotBlank()) "set" else "unset"

/**
 * Where requests to this provider go, as the owner sees it on cards: the bare host for https on the
 * default port, otherwise `scheme://host[:port]`. Null when the address cannot be parsed.
 */
fun ProviderSetting.displayOrigin(): String? = when (this) {
    is ProviderSetting.OpenAI -> if (authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION) "chatgpt.com" else displayOriginOf(baseUrl)
    is ProviderSetting.Google -> if (vertexAI) "aiplatform.googleapis.com" else displayOriginOf(baseUrl)
    is ProviderSetting.Claude -> displayOriginOf(baseUrl)
}

fun displayOriginOf(url: String): String? = runCatching {
    val uri = URI(url.trim())
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "https" || it == "http" } ?: return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    if (scheme == "https" && (uri.port == -1 || uri.port == 443)) host
    else "$scheme://$host" + if (uri.port != -1) ":${uri.port}" else ""
}.getOrNull()
