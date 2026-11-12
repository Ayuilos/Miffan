package me.ayuilos.miffan.data.appsettings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.ai.mcp.McpServerConfig
import me.ayuilos.miffan.data.ai.mcp.serverUrl
import me.ayuilos.miffan.data.datastore.Settings
import me.rerere.ai.provider.CustomHeader
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

const val CONNECT_SEARCH_TOOL = "connect_search_service"
const val SET_SECRET_HEADER_TOOL = "set_secret_header"
const val CONNECT_VOICE_TOOL = "connect_voice_service"

enum class VoiceKind(val id: String) { TTS("tts"), ASR("asr") }

/**
 * A voice service's key: read aloud ([VoiceKind.TTS]) or voice input ([VoiceKind.ASR]), for a service already in the
 * settings ([serviceId]) or a new one of [type].
 */
data class VoiceTarget(
    val kind: VoiceKind,
    val serviceId: Uuid?,
    val type: String,
    override val name: String,
    override val origin: String,
    val keyUrl: String?,
    val hasKey: Boolean,
    /** OpenRouter only: the speech model to read with, set together with the key. */
    val model: String? = null,
    /** The voice to read with, set together with the key; checked by the sample like the key. */
    val voice: String? = null,
    /** The audio format to ask for, where the service has a choice. */
    val format: String? = null,
) : ConnectTarget

/** Why [format] cannot be asked of [setting] once on [model], or null when it can (or none was given). */
private fun invalidFormat(format: String?, setting: TTSProviderSetting, model: String?): String? {
    if (format == null) return null
    val formats = ttsFormatsFor(model?.let { setting.withTtsModel(it) } ?: setting)
    return when {
        formats.isEmpty() -> "This service (or model) has no audio format to pick."
        format !in formats -> "format must be one of ${formats.joinToString()}."
        else -> null
    }
}

/** [this] read-aloud service as [target] asks for it: on its model, in its format and voice. */
fun TTSProviderSetting.configuredFor(target: VoiceTarget): TTSProviderSetting {
    val onModel = target.model?.let { withTtsModel(it) } ?: this
    val inFormat = target.format?.takeIf { it in ttsFormatsFor(onModel) }?.let { onModel.withTtsFormat(it) } ?: onModel
    return target.voice?.let { inFormat.withVoice(it) } ?: inFormat
}

/** A search service's key, for a service already in the settings ([serviceId]) or a new one of [service]. */
data class SearchTarget(
    val serviceId: Uuid?,
    val service: SearchKeyService,
    override val name: String,
    override val origin: String,
    val hasKey: Boolean,
) : ConnectTarget

/** Where a secret header goes: an MCP server, or one model of a provider. */
sealed interface HeaderOwner {
    data class Mcp(val serverId: Uuid) : HeaderOwner
    data class Model(val providerId: Uuid, val modelId: Uuid) : HeaderOwner
}

/**
 * A request header whose value is secret (a token), written as [prefix] + the value the owner types. Requests carry it
 * only to [origin]; saving checks the owner still sends requests there.
 */
data class HeaderTarget(
    val owner: HeaderOwner,
    val header: String,
    val prefix: String,
    override val name: String,
    override val origin: String,
) : ConnectTarget

/** Reads any secure card call (see [SECRET_CARD_TOOLS]) against the current settings. */
fun resolveSecretTarget(toolName: String, input: JsonElement, settings: Settings): ConnectTargetResult = when (toolName) {
    CONNECT_PROVIDER_TOOL -> resolveConnectTarget(input, settings)
    CONNECT_SEARCH_TOOL -> resolveSearchTarget(input, settings)
    SET_SECRET_HEADER_TOOL -> resolveHeaderTarget(input, settings)
    CONNECT_VOICE_TOOL -> resolveVoiceTarget(input, settings)
    else -> ConnectTargetResult.Invalid("Not a secure card.")
}

fun resolveSearchTarget(input: JsonElement, settings: Settings): ConnectTargetResult {
    val params = input as? JsonObject ?: return ConnectTargetResult.Invalid("Arguments must be an object.")
    val serviceId = params.text("service_id")
    val type = params.text("type")
    if ((serviceId == null) == (type == null)) return ConnectTargetResult.Invalid("Give either service_id or type.")
    if (serviceId != null) {
        val id = runCatching { Uuid.parse(serviceId.trim()) }.getOrNull()
        val options = id?.let { settings.searchServices.firstOrNull { it.id == id } }
            ?: return ConnectTargetResult.Invalid("No search service has this service_id. Call app_settings_catalog for the ids.")
        val service = SearchKeyService.of(options)
            ?: return ConnectTargetResult.Invalid("This search service is set up on its settings page, not with a key card.")
        return ConnectTargetResult.Ok(SearchTarget(options.id, service, options.displayName, service.origin,
            hasKey = !options.searchApiKey().isNullOrBlank()))
    }
    val service = SearchKeyService.of(type)
        ?: return ConnectTargetResult.Invalid("type must be one of ${SearchKeyService.entries.joinToString { it.id }}.")
    // One of each kind is enough; replacing the key of the existing one avoids look-alike duplicates.
    settings.searchServices.firstOrNull { SearchKeyService.of(it) == service }?.let { existing ->
        return ConnectTargetResult.Ok(SearchTarget(existing.id, service, existing.displayName, service.origin,
            hasKey = !existing.searchApiKey().isNullOrBlank()))
    }
    return ConnectTargetResult.Ok(SearchTarget(null, service, service.create().displayName, service.origin, hasKey = false))
}

fun resolveVoiceTarget(input: JsonElement, settings: Settings): ConnectTargetResult {
    val params = input as? JsonObject ?: return ConnectTargetResult.Invalid("Arguments must be an object.")
    val kind = VoiceKind.entries.firstOrNull { it.id == params.text("kind")?.trim()?.lowercase() }
        ?: return ConnectTargetResult.Invalid("kind must be tts (read aloud) or asr (voice input).")
    val serviceId = params.text("service_id")
    val type = params.text("type")
    if ((serviceId == null) == (type == null)) return ConnectTargetResult.Invalid("Give either service_id or type.")
    val notByCard = "This service is set up on the speech settings page, not with a key card."
    val model = params.text("model")?.trim()?.takeIf { it.isNotEmpty() }
    val voice = params.text("voice")?.trim()?.takeIf { it.isNotEmpty() }
    val format = params.text("format")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (kind == VoiceKind.ASR && (model != null || voice != null || format != null)) {
        return ConnectTargetResult.Invalid("model, voice and format are only for tts.")
    }
    if (model != null && !validTtsModelId(model)) {
        return ConnectTargetResult.Invalid("model must be an OpenRouter speech model id, like fish-audio/s2-pro.")
    }
    if (voice != null && (voice.length > MAX_VOICE_ID || voice.any { it.isISOControl() })) {
        return ConnectTargetResult.Invalid("voice must be a voice id of the service.")
    }
    return when (kind) {
        VoiceKind.TTS -> {
            val existing = if (serviceId != null) {
                val id = runCatching { Uuid.parse(serviceId.trim()) }.getOrNull()
                val setting = id?.let { settings.ttsProviders.firstOrNull { it.id == id } }
                    ?: return ConnectTargetResult.Invalid("No read-aloud service has this service_id. Call app_settings_catalog for the ids.")
                if (TtsKeyService.of(setting) == null) return ConnectTargetResult.Invalid(notByCard)
                if (model != null && setting !is TTSProviderSetting.OpenRouter) {
                    return ConnectTargetResult.Invalid("model is only for openrouter; other services read with their own model.")
                }
                setting
            } else {
                val service = TtsKeyService.of(type)
                    ?: return ConnectTargetResult.Invalid("type must be one of ${TtsKeyService.entries.joinToString { it.id }} for tts.")
                // One of each kind is enough; replacing the key of the existing one avoids look-alike duplicates.
                if (model != null && service != TtsKeyService.OPENROUTER) {
                    return ConnectTargetResult.Invalid("model is only for openrouter; other services read with their own model.")
                }
                val sameKind = settings.ttsProviders.firstOrNull { TtsKeyService.of(it) == service }
                if (sameKind == null) invalidFormat(format, service.create(), model)?.let { return ConnectTargetResult.Invalid(it) }
                sameKind
                    ?: return ConnectTargetResult.Ok(VoiceTarget(kind, null, service.id, service.create().displayName(), service.origin,
                        service.keyUrl, hasKey = false, model = model, voice = voice, format = format))
            }
            val service = TtsKeyService.of(existing)!!
            invalidFormat(format, existing, model)?.let { return ConnectTargetResult.Invalid(it) }
            ConnectTargetResult.Ok(VoiceTarget(kind, existing.id, service.id, existing.displayName(), service.origin, service.keyUrl,
                hasKey = !existing.ttsApiKey().isNullOrBlank(), model = model, voice = voice, format = format))
        }
        VoiceKind.ASR -> {
            val existing = if (serviceId != null) {
                val id = runCatching { Uuid.parse(serviceId.trim()) }.getOrNull()
                val setting = id?.let { settings.asrProviders.firstOrNull { it.id == id } }
                    ?: return ConnectTargetResult.Invalid("No voice input service has this service_id. Call app_settings_catalog for the ids.")
                if (AsrKeyService.of(setting) == null) return ConnectTargetResult.Invalid(notByCard)
                setting
            } else {
                val service = AsrKeyService.of(type)
                    ?: return ConnectTargetResult.Invalid("type must be one of ${AsrKeyService.entries.joinToString { it.id }} for asr.")
                settings.asrProviders.firstOrNull { AsrKeyService.of(it) == service }
                    ?: return ConnectTargetResult.Ok(VoiceTarget(kind, null, service.id, service.create().displayName(), service.origin,
                        service.keyUrl, hasKey = false))
            }
            val service = AsrKeyService.of(existing)!!
            ConnectTargetResult.Ok(VoiceTarget(kind, existing.id, service.id, existing.displayName(), service.origin, service.keyUrl,
                hasKey = existing.asrApiKey().isNotBlank()))
        }
    }
}

private val HEADER_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,63}$")
private val RESERVED_HEADERS = setOf("host", "content-length", "content-type", "connection", "transfer-encoding", "cookie", "accept")

fun resolveHeaderTarget(input: JsonElement, settings: Settings): ConnectTargetResult {
    val params = input as? JsonObject ?: return ConnectTargetResult.Invalid("Arguments must be an object.")
    val header = params.text("header")?.trim() ?: "Authorization"
    if (!HEADER_NAME.matches(header) || header.lowercase() in RESERVED_HEADERS) {
        return ConnectTargetResult.Invalid("header must be a plain header name such as Authorization or X-Api-Key.")
    }
    val prefix = when (params.text("scheme")?.trim()?.lowercase() ?: if (header.equals("Authorization", true)) "bearer" else "raw") {
        "bearer" -> "Bearer "
        "raw" -> ""
        else -> return ConnectTargetResult.Invalid("scheme must be bearer or raw.")
    }
    val mcpId = params.text("mcp_server_id")
    val providerId = params.text("provider_id")
    if ((mcpId == null) == (providerId == null)) return ConnectTargetResult.Invalid("Give either mcp_server_id, or provider_id with model_id.")
    if (mcpId != null) {
        val id = runCatching { Uuid.parse(mcpId.trim()) }.getOrNull()
        val server = id?.let { settings.mcpServers.firstOrNull { it.id == id } }
            ?: return ConnectTargetResult.Invalid("No MCP server has this mcp_server_id. Call app_settings_catalog for the ids.")
        if (server.commonOptions.oauth?.enabled == true) {
            return ConnectTargetResult.Invalid("This MCP server signs in with OAuth; ask the owner to sign in on its settings page.")
        }
        val origin = server.origin() ?: return ConnectTargetResult.Invalid("The MCP server's address is not valid. Ask the owner to fix it.")
        return ConnectTargetResult.Ok(HeaderTarget(HeaderOwner.Mcp(server.id), header, prefix, server.commonOptions.name.ifBlank { origin }, origin))
    }
    val pid = runCatching { Uuid.parse(providerId!!.trim()) }.getOrNull()
    val provider = pid?.let { settings.providers.firstOrNull { it.id == pid } }
        ?: return ConnectTargetResult.Invalid("No provider has this provider_id. Call app_settings_catalog for the ids.")
    val modelId = params.text("model_id")?.trim() ?: return ConnectTargetResult.Invalid("model_id is required with provider_id.")
    val model = provider.models.firstOrNull { it.modelId == modelId }
        ?: return ConnectTargetResult.Invalid("${provider.name} has no model \"$modelId\".")
    // A per-model override sends requests somewhere else than the provider's address the card would show.
    if (model.providerOverwrite != null) return ConnectTargetResult.Invalid("This model is set up on its settings page.")
    val origin = provider.displayOrigin() ?: return ConnectTargetResult.Invalid("The provider's address is not valid.")
    return ConnectTargetResult.Ok(HeaderTarget(HeaderOwner.Model(provider.id, model.id), header, prefix,
        model.displayName.ifBlank { model.modelId }, origin))
}

private fun McpServerConfig.origin(): String? = displayOriginOf(serverUrl)

sealed interface SecretSave {
    data class Saved(val settings: Settings, val result: String) : SecretSave

    /** The service or server was removed, or now sends requests elsewhere than the card showed. */
    data object TargetChanged : SecretSave
}

/** Stores [key] for a search service: the existing entry's key is replaced, or a new entry is added (not selected). */
fun Settings.withSearchKey(target: SearchTarget, key: String, checked: Boolean, newId: Uuid = Uuid.random()): SecretSave {
    val trimmed = key.trim()
    val updated = if (target.serviceId != null) {
        val current = searchServices.firstOrNull { it.id == target.serviceId }
        if (current == null || SearchKeyService.of(current) != target.service) return SecretSave.TargetChanged
        copy(searchServices = searchServices.map { if (it.id == current.id) it.withSearchApiKey(trimmed) else it })
    } else {
        copy(searchServices = searchServices + target.service.newOptions(newId, trimmed))
    }
    val id = target.serviceId ?: newId
    val selected = updated.searchServices.getOrNull(updated.searchServiceSelected)?.id == id
    return SecretSave.Saved(updated, buildJsonObject {
        put("status", if (checked) "connected" else "saved_unchecked")
        put("search_service_id", id.toString())
        put("name", target.name)
        put("in_use", selected)
        if (!checked) put("note", "The owner saved the key without a test search because the service could not be reached.")
        if (!selected) put("next", "It is not the search service in use. To switch to it, propose select_search_service with " +
            "$CHANGE_APP_SETTINGS_TOOL if the owner wants that.")
    }.toString())
}

/**
 * Stores [key] for a voice service: the existing entry's key is replaced, or a new entry is added. Not put in use:
 * that is a change the owner confirms on its own card.
 */
fun Settings.withVoiceKey(target: VoiceTarget, key: String, checked: Boolean, newId: Uuid = Uuid.random()): SecretSave {
    val trimmed = key.trim()
    val id = target.serviceId ?: newId
    val updated = when (target.kind) {
        VoiceKind.TTS -> {
            val service = TtsKeyService.of(target.type) ?: return SecretSave.TargetChanged
            if (target.serviceId != null) {
                val current = ttsProviders.firstOrNull { it.id == target.serviceId }
                if (current == null || TtsKeyService.of(current) != service) return SecretSave.TargetChanged
                copy(ttsProviders = ttsProviders.map { if (it.id == current.id) it.transformTtsSecret { trimmed }.configuredFor(target) else it })
            } else copy(ttsProviders = ttsProviders + service.newSetting(newId, trimmed).configuredFor(target))
        }
        VoiceKind.ASR -> {
            val service = AsrKeyService.of(target.type) ?: return SecretSave.TargetChanged
            if (target.serviceId != null) {
                val current = asrProviders.firstOrNull { it.id == target.serviceId }
                if (current == null || AsrKeyService.of(current) != service) return SecretSave.TargetChanged
                copy(asrProviders = asrProviders.map { if (it.id == current.id) it.transformAsrSecret { trimmed } else it })
            } else copy(asrProviders = asrProviders + service.newSetting(newId, trimmed))
        }
    }
    val inUse = when (target.kind) {
        VoiceKind.TTS -> updated.selectedTTSProviderId == id
        VoiceKind.ASR -> updated.selectedASRProviderId == id
    }
    val select = if (target.kind == VoiceKind.TTS) "select_tts_service" else "select_asr_service"
    return SecretSave.Saved(updated, buildJsonObject {
        put("status", when {
            target.kind == VoiceKind.ASR -> "saved"
            checked -> "connected"
            else -> "saved_unchecked"
        })
        put("kind", target.kind.id)
        put("voice_service_id", id.toString())
        put("name", target.name)
        target.model?.let { put("model", it) }
        target.voice?.let { put("voice", it) }
        target.format?.let { put("format", it) }
        put("in_use", inUse)
        when {
            target.kind == VoiceKind.ASR -> put("note", "There is no way to check a voice input key here; ask the owner to try voice input once it is in use.")
            checked -> put("note", "The owner heard a sample read with this key.")
            else -> put("note", "The owner saved the key without a sample because the service could not be reached.")
        }
        if (!inUse) put("next", "It is not in use yet. To use it, propose $select with $CHANGE_APP_SETTINGS_TOOL if the owner wants that.")
    }.toString())
}

/** Stores the header for its owner, replacing a header of the same name; only while requests still go to the shown origin. */
fun Settings.withSecretHeader(target: HeaderTarget, key: String): SecretSave {
    val value = target.prefix + key.trim()
    val updated = when (val owner = target.owner) {
        is HeaderOwner.Mcp -> {
            val server = mcpServers.firstOrNull { it.id == owner.serverId }
            if (server == null || server.origin() != target.origin) return SecretSave.TargetChanged
            val headers = server.commonOptions.headers.filterNot { it.first.equals(target.header, true) } + (target.header to value)
            copy(mcpServers = mcpServers.map { if (it.id == server.id) it.clone(commonOptions = it.commonOptions.copy(headers = headers)) else it })
        }
        is HeaderOwner.Model -> {
            val provider = providers.firstOrNull { it.id == owner.providerId }
            val model = provider?.models?.firstOrNull { it.id == owner.modelId }
            if (provider == null || model == null || provider.displayOrigin() != target.origin || model.providerOverwrite != null) {
                return SecretSave.TargetChanged
            }
            val headers = model.customHeaders.filterNot { it.name.equals(target.header, true) } + CustomHeader(target.header, value)
            copy(providers = providers.map { p ->
                if (p.id != provider.id) p else p.copyProvider(models = p.models.map { if (it.id == model.id) it.copy(customHeaders = headers) else it })
            })
        }
    }
    return SecretSave.Saved(updated, buildJsonObject {
        put("status", "saved")
        put("name", target.name)
        put("header", target.header)
    }.toString())
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
