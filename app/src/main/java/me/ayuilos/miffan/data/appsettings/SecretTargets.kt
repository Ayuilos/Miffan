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
import kotlin.uuid.Uuid

const val CONNECT_SEARCH_TOOL = "connect_search_service"
const val SET_SECRET_HEADER_TOOL = "set_secret_header"

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
