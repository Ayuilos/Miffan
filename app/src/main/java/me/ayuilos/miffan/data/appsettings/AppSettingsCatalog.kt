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
import me.ayuilos.miffan.data.datastore.findModelById
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.ayuilos.miffan.data.ai.mcp.serverUrl
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
fun buildAppSettingsCatalog(settings: Settings, appearance: Appearance? = null, selfId: kotlin.uuid.Uuid? = null): JsonObject = buildJsonObject {
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
                            put("model_id", model.modelId)
                            put("name", model.displayName.ifBlank { model.modelId })
                            put("type", model.type.name.lowercase())
                            if (model.type == ModelType.CHAT) {
                                put("tools", ModelAbility.TOOL in model.abilities)
                                put("reasoning", ModelAbility.REASONING in model.abilities)
                                put("image_input", Modality.IMAGE in model.inputModalities)
                            }
                        }
                    }
                }
            }
        }
    }
    putJsonObject("default_models") {
        DefaultModelRole.entries.forEach { role ->
            put(role.id, settings.modelRef(role.get(settings)) ?: JsonPrimitive(if (role.optional) role.clearWord else "unset"))
        }
    }
    settings.findModelById(settings.fastModelId)?.takeIf { ThinkTier.QUICK.levelFor(it) != null || ThinkTier.MORE.levelFor(it) != null }
        ?.let { put("fast_thinking", ThinkTier.of(settings.fastModelReasoningLevel, it).id) }
    putJsonObject("display") {
        val display = settings.displaySetting
        put("font_size", FontSize.nearest(display.fontSizeRatio).id)
        put("latex", display.enableLatexRendering)
        put("code_wrap", display.codeBlockAutoWrap)
        put("code_collapse", display.codeBlockAutoCollapse)
        put("crop_before_send", !display.skipCropImage)
    }
    putJsonObject("theme") {
        put("current", if (settings.dynamicColor) WALLPAPER_THEME else settings.themeId)
        putJsonArray("choices") {
            add(WALLPAPER_THEME)
            themeChoices(settings).forEach { (id, name) -> if (name == null) add(id) else addJsonObject { put("id", id); put("name", name) } }
        }
        appearance?.let {
            put("color_mode", it.colorMode.lowercase())
            put("pure_black", it.amoled)
        }
    }
    putJsonObject("notifications") {
        put("reply_done", settings.displaySetting.enableNotificationOnMessageGeneration)
        put("live_progress", settings.displaySetting.enableLiveUpdateNotification)
    }
    put("owner_name", settings.displaySetting.userNickname)
    putJsonObject("backup_reminder") {
        val config = settings.backupReminderConfig
        put("enabled", config.enabled)
        put("interval_days", config.intervalDays)
        val days = config.lastBackupTime.takeIf { it > 0 }?.let { ((System.currentTimeMillis() - it) / 86_400_000L).coerceAtLeast(0) }
        put("days_since_backup", days?.let { JsonPrimitive(it) } ?: JsonPrimitive("never"))
    }
    putJsonArray("partners") {
        settings.assistants.forEach { assistant ->
            addJsonObject {
                put("id", assistant.id.toString())
                put("name", assistant.displayName())
                if (assistant.id == selfId) put("is_you", true)
                put("model", assistant.chatModelId?.let { settings.modelRef(it) } ?: JsonPrimitive("default"))
                putJsonObject("settings") {
                    settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                        ?.takeIf { ModelAbility.REASONING in it.abilities }
                        ?.let { put("thinking", ThinkTier.of(assistant.reasoningLevel, it).id) }
                    put("reply_limit", assistant.maxTokens?.let { JsonPrimitive(it) } ?: JsonPrimitive("none"))
                    put("context_messages", assistant.contextMessageLimit.takeIf { it > 0 }?.let { JsonPrimitive(it) } ?: JsonPrimitive("all"))
                    put("temperature", assistant.temperature?.let { JsonPrimitive(it) } ?: JsonPrimitive("default"))
                    put("memory", assistant.enableMemory)
                    put("shared_memory", assistant.useGlobalMemory)
                }
            }
        }
    }
    putJsonObject("voice") {
        putJsonObject("tts") {
            put("auto_read", settings.displaySetting.autoPlayTTSAfterGeneration)
            put("speed", settings.defaultTTSPlaybackSpeed)
            put("only_quoted", settings.displaySetting.ttsOnlyReadQuoted)
            put("skip_brackets", settings.displaySetting.ttsOnlyReadOutsideBrackets)
            putJsonArray("services") {
                settings.ttsProviders.forEach { service ->
                    addJsonObject {
                        put("id", service.id.toString())
                        put("name", service.displayName())
                        put("type", service.typeId())
                        service.address()?.let(::voiceOriginOf)?.let { put("host", it) }
                        service.ttsApiKey()?.let { put("key", it.setOrUnset()) }
                        put("in_use", service.id == settings.selectedTTSProviderId)
                        put("key_card", TtsKeyService.of(service) != null)
                        service.voice()?.let { put("voice", it) }
                        if (service is me.rerere.tts.provider.TTSProviderSetting.SystemTTS) put("pitch", service.pitch)
                        voicesFor(service).takeIf { it.isNotEmpty() }?.let { voices ->
                            putJsonArray("voices") {
                                voices.forEach { (id, about) -> addJsonObject { put("id", id); about?.let { put("about", it) } } }
                            }
                        }
                    }
                }
            }
            putJsonArray("can_connect") { TtsKeyService.entries.forEach { add(it.id) } }
        }
        putJsonObject("asr") {
            putJsonArray("services") {
                settings.asrProviders.forEach { service ->
                    addJsonObject {
                        put("id", service.id.toString())
                        put("name", service.displayName())
                        put("type", service.typeId())
                        voiceOriginOf(service.address())?.let { put("host", it) }
                        put("key", service.asrApiKey().setOrUnset())
                        put("in_use", service.id == settings.selectedASRProviderId)
                        put("key_card", AsrKeyService.of(service) != null)
                    }
                }
            }
            putJsonArray("can_connect") { AsrKeyService.entries.forEach { add(it.id) } }
        }
    }
    putJsonArray("search_services") {
        settings.searchServices.forEachIndexed { index, options ->
            addJsonObject {
                put("id", options.id.toString())
                put("name", options.displayName)
                put("in_use", index == settings.searchServiceSelected)
                options.searchApiKey()?.let { put("key", it.setOrUnset()) }
                SearchKeyService.of(options)?.let { put("type", it.id); put("host", it.origin) }
                put("key_card", SearchKeyService.of(options) != null)
            }
        }
    }
    putJsonArray("mcp_servers") {
        settings.mcpServers.forEach { server ->
            addJsonObject {
                put("id", server.id.toString())
                put("name", server.commonOptions.name)
                displayOriginOf(server.serverUrl)?.let { put("host", it) }
                put("enabled", server.commonOptions.enable)
                // Header names only; the values often are tokens.
                putJsonArray("headers") { server.commonOptions.headers.forEach { add(it.first) } }
                if (server.commonOptions.oauth?.enabled == true) put("oauth", true)
            }
        }
    }
    putJsonArray("notes") {
        add("key is set, unset, signed_in or signed_out; the key itself is never readable.")
        add("A partner's model \"default\" means it follows default_models.chat; \"follow_fast\" follows default_models.fast.")
        add("partners[].settings: thinking is how long it thinks before replying (absent when its model cannot think); " +
            "reply_limit caps a reply in tokens and cuts it off mid-way, so for shorter answers change how you reply instead; " +
            "context_messages is how many recent messages it is sent; temperature \"default\" leaves it to the model; " +
            "shared_memory means it uses the memory all partners share instead of its own.")
        add("Search services with key_card true take a key through connect_search_service; other kinds are set up on their settings page.")
        add("voice.tts reads replies aloud (type system is the phone's own voice, no key); voice.asr turns the owner's voice into " +
            "text. Services with key_card true, and the types in can_connect, take a key through connect_voice_service. A voice " +
            "outside a service's voices list may not work. speed applies to every read-aloud service.")
        add("Models are referred to as {provider_id, model_id}; only the first $CATALOG_MODELS_PER_PROVIDER of a provider are listed.")
    }
}

/** `{provider_id, model_id, name}` for a model in the settings, or null when [id] names none. */
private fun Settings.modelRef(id: kotlin.uuid.Uuid?): JsonElement? {
    if (id == null) return null
    providers.forEach { provider ->
        provider.models.firstOrNull { it.id == id }?.let { model ->
            return buildJsonObject {
                put("provider_id", provider.id.toString())
                put("model_id", model.modelId)
                put("name", model.displayName.ifBlank { model.modelId })
            }
        }
    }
    return null
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
