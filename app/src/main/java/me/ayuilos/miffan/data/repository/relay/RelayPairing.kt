package me.ayuilos.miffan.data.repository.relay

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.workspace.relay.RelayEndpoint
import me.rerere.workspace.relay.RelayIds

/** Parsed output from `RelaySetupScript`, `miffan-connector pair`, or a raw 26-char `hostId`. */
data class ParsedRelayPairing(
    val hostId: String,
    val username: String? = null,
    val name: String? = null,
    val relayUrl: String? = null,
    val fingerprints: List<String> = emptyList(),
)

private val pairingJson = Json { ignoreUnknownKeys = true }
private val pairingUriRegex = Regex("""miffan-pair://v1\?[^\s"'<>]+""")
private val fingerprintRegex = Regex("""SHA256:([A-Za-z0-9+/]{43})={0,2}""")

@Serializable
private data class ConnectorPairJson(
    val hostId: String? = null,
    val relay: String? = null,
)

/**
 * Parses pairing text pasted or scanned from a computer:
 * 1. `miffan-pair://v1?host=...&user64=...&name64=...&relay64=...&fp=...`
 * 2. A JSON line from `miffan-connector pair` / `miffan-connector status` (`{"hostId":...}`)
 * 3. A bare 26-character base32 `hostId`
 */
fun parseRelayPairingInput(raw: String): ParsedRelayPairing? {
    pairingUriRegex.find(raw)?.let { match ->
        parsePairingUri(match.value)?.let { return it }
    }
    for (line in raw.lineSequence().toList().asReversed()) {
        val trimmed = line.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val candidate = trimmed.substring(start, end + 1)
            val decoded = runCatching { pairingJson.decodeFromString<ConnectorPairJson>(candidate) }.getOrNull()
            val hostId = decoded?.hostId?.trim()?.lowercase()?.takeIf(RelayIds::isHostId)
            if (hostId != null) {
                val relayUrl = decoded.relay?.trim()?.let(::validRelayUrlOrNull)
                return ParsedRelayPairing(hostId = hostId, relayUrl = relayUrl)
            }
        }
    }
    val bare = normalizeBareToken(raw).trim().lowercase()
    return if (RelayIds.isHostId(bare)) ParsedRelayPairing(hostId = bare) else null
}

/** Extracts `SHA256:<43-char base64>` from a bare fingerprint or `ssh-keygen -lf` line. */
fun parseSshHostFingerprint(raw: String): String? =
    fingerprintRegex.find(raw)?.let { "SHA256:${it.groupValues[1]}" }

/**
 * Reference CLI commands for users who installed `miffan-connector` manually.
 * Never embeds the secret access code into command-line text.
 */
fun manualConnectorCommands(phone: RelayPhoneIdentity, endpoint: RelayEndpoint): String {
    val safeName = phone.deviceName.replace(Regex("""["\\$`\r\n\u0000]"""), " ").trim().ifBlank { "Phone" }
    val initLine = if (endpoint.accessToken.isNullOrEmpty()) {
        "miffan-connector init --relay ${endpoint.baseUrl}"
    } else {
        "miffan-connector init --relay ${endpoint.baseUrl} --token-stdin"
    }
    val pairLine = "miffan-connector pair --device ${phone.devicePublicKeyB64u} --name \"$safeName\" --relay ${endpoint.baseUrl}"
    return "$initLine\n$pairLine"
}

private fun parsePairingUri(uri: String): ParsedRelayPairing? {
    val query = uri.substringAfter('?', "")
    if (query.isBlank()) return null
    val params = linkedMapOf<String, String>()
    for (part in query.split('&')) {
        if (part.isEmpty()) continue
        val eq = part.indexOf('=')
        val key = if (eq >= 0) part.substring(0, eq) else part
        val value = if (eq >= 0) part.substring(eq + 1) else ""
        params[key] = value
    }
    val hostId = params["host"]?.trim()?.lowercase()?.takeIf(RelayIds::isHostId) ?: return null
    val username = decodeParam64(params["user64"]) ?: decodeQueryValue(params["user"])
    val name = decodeParam64(params["name64"]) ?: decodeQueryValue(params["name"])
    val relayUrl = (decodeParam64(params["relay64"]) ?: decodeQueryValue(params["relay"]))
        ?.trim()?.let(::validRelayUrlOrNull)
    val fingerprints = params["fp"]
        ?.let(::decodeQueryValue)
        ?.split(',')
        ?.mapNotNull(::parseSshHostFingerprint)
        ?.distinct()
        .orEmpty()
    return ParsedRelayPairing(
        hostId = hostId,
        username = username?.trim()?.takeIf { it.isNotEmpty() },
        name = name?.trim()?.takeIf { it.isNotEmpty() },
        relayUrl = relayUrl,
        fingerprints = fingerprints,
    )
}

private fun decodeParam64(value: String?): String? {
    val clean = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val padded = when (clean.length % 4) {
        2 -> "$clean=="
        3 -> "$clean="
        else -> clean
    }
    val bytes = runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
        ?: runCatching { Base64.getUrlDecoder().decode(clean) }.getOrNull()
        ?: return null
    return String(bytes, StandardCharsets.UTF_8)
}

private fun decodeQueryValue(value: String?): String? {
    val raw = value ?: return null
    return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrNull()
}

private fun validRelayUrlOrNull(value: String): String? =
    runCatching { RelayEndpoint(value.trim().trimEnd('/')).baseUrl }.getOrNull()

private fun normalizeBareToken(text: String): String = buildString(text.length) {
    for (char in text) append(when (char) {
        '　' -> ' '
        in '０'..'９' -> '0' + (char - '０')
        else -> char
    })
}
