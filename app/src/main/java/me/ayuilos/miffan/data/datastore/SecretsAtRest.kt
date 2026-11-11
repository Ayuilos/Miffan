package me.ayuilos.miffan.data.datastore

import me.ayuilos.miffan.data.ai.mcp.McpServerConfig
import me.ayuilos.miffan.data.appsettings.transformSearchSecrets
import me.rerere.search.SearchServiceOptions

/*
 * Credentials besides provider keys, encrypted at rest with the same Keystore key: search service keys, MCP request
 * headers and OAuth tokens, and the keys of read-aloud and voice input services. Anything that can read the settings file (a workspace shell, a backup of the app's
 * files) then finds ciphertext only. Plain values written by older versions are read as they are and encrypted on
 * the next save.
 */

internal fun List<SearchServiceOptions>.transformSearchServiceSecrets(transform: (String) -> String): List<SearchServiceOptions> =
    map { it.transformSearchSecrets(transform) }

internal fun List<McpServerConfig>.transformMcpSecrets(transform: (String) -> String): List<McpServerConfig> = map { server ->
    val options = server.commonOptions
    server.clone(commonOptions = options.copy(
        headers = options.headers.map { (name, value) -> name to transform(value) },
        oauth = options.oauth?.let { oauth ->
            oauth.copy(
                clientSecret = oauth.clientSecret?.let(transform),
                accessToken = oauth.accessToken?.let(transform),
                refreshToken = oauth.refreshToken?.let(transform),
            )
        },
    ))
}
