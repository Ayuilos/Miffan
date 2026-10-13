package me.ayuilos.miffan.data.bugreport

import java.net.URI

/** Shared by persistence and public drafts. Never persist the unredacted input. */
fun redactDiagnosticText(text: String): String {
    var safe = text.replace(Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s<>\"']+")) { match ->
        runCatching {
            val uri = URI(match.value)
            val host = uri.host ?: return@runCatching "[已隐藏]"
            "${uri.scheme}://$host"
        }.getOrDefault("[已隐藏]")
    }
    val secrets = listOf(
        Regex("(?i)\\bsk-[a-z0-9_-]+"),
        Regex("(?i)\\bBearer\\s+[^\\s,;\"']+"),
        Regex("(?i)\\b(?:api[_-]?key|access[_-]?token|key|token|password|cookie|authorization)[\"']?\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s&;,]+)"),
        Regex("(?i)[a-z0-9.!#\$%&'*+/=?^_`{|}~-]+@[a-z0-9.-]+\\.[a-z]{2,}"),
        Regex("(?<![a-zA-Z0-9+/=_-])[a-zA-Z0-9+/_-]{24,}={0,2}(?![a-zA-Z0-9+/=_-])"),
    )
    secrets.forEach { safe = safe.replace(it, "[已隐藏]") }
    safe = safe.replace(Regex("(?<![0-9.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9.])"), "[IPv4]")
    safe = safe.replace(Regex("(?i)(?<![a-z0-9:])\\[?(?:[a-f0-9]{0,4}:){2,}[a-f0-9]{0,4}(?:%[a-z0-9]+)?\\]?(?![a-z0-9:])")) { match ->
        val address = match.value.trim('[', ']').substringBefore('%')
        // A clock such as 12:34:56 is not an IPv6 literal.
        if ("::" in address || address.count { it == ':' } == 7) "[IPv6]" else match.value
    }
    return safe
}
