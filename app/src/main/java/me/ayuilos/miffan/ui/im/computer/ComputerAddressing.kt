package me.ayuilos.miffan.ui.im.computer

/** Adds [publicKey] to ~/.ssh/authorized_keys unless it is there already; runs in sh, bash, zsh and fish. */
internal fun authorizeKeyCommand(publicKey: String): String {
    val quoted = "'" + publicKey.trim().replace("'", "'\\''") + "'"
    return "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && " +
        "grep -qxF $quoted ~/.ssh/authorized_keys || printf '\\n%s\\n' $quoted >> ~/.ssh/authorized_keys"
}

/** What the user runs by hand: [authorizeKeyCommand], then the account name to fill in. */
internal fun computerPublicKeyCommand(publicKey: String): String =
    authorizeKeyCommand(publicKey) + " && echo 'Miffan OK, user:' && whoami"

/** An SSH target as pasted from a terminal or an SSH config; null parts were not given. */
internal data class SshTarget(val host: String, val username: String? = null, val port: Int? = null)

/** ssh options that take a value, so the value is not mistaken for the destination. */
private const val SSH_OPTIONS_WITH_VALUE = "BbcDEeFIiJLlmOoPpQRSWw"

/**
 * Reads "user@host", "user@host:2222", "host:2222", "ssh://user@host:2222" and
 * "ssh -p 2222 user@host" (also "-l user"). Null when [input] is just a host, or not a target.
 */
internal fun parseSshTarget(input: String): SshTarget? {
    var text = input.trim()
    var username: String? = null
    var port: Int? = null
    val words = text.split(Regex("\\s+"))
    if (words.size > 1) {
        if (words.first() != "ssh") return null
        var destination: String? = null
        var index = 1
        while (index < words.size) {
            val word = words[index]
            val value = words.getOrNull(index + 1)
            when {
                word.length == 2 && word[0] == '-' && word[1] in SSH_OPTIONS_WITH_VALUE -> {
                    when (word[1]) {
                        'p' -> port = value?.toIntOrNull()
                        'l' -> username = value
                    }
                    index++
                }
                word.startsWith("-p") && word.length > 2 -> port = word.drop(2).toIntOrNull()
                word.startsWith("-") -> Unit
                destination == null -> destination = word
            }
            index++
        }
        text = destination ?: return null
    }
    text = text.removePrefix("ssh://").trimEnd('/')
    if (text.contains('@')) {
        username = text.substringBeforeLast('@').ifBlank { null }
        text = text.substringAfterLast('@')
    }
    val host = when {
        text.startsWith("[") -> text.substringAfter('[').substringBefore(']').also {
            text.substringAfter(']', "").removePrefix(":").toIntOrNull()?.let { port = it }
        }
        text.count { it == ':' } == 1 -> text.substringBefore(':').also { text.substringAfter(':').toIntOrNull()?.let { port = it } }
        else -> text
    }
    if (host.isBlank() || (username == null && port == null)) return null
    return SshTarget(host, username, port?.takeIf { it in 1..65535 })
}

/** The name a computer gets when the user leaves it empty: "macbook" for macbook.local, an IP as is. */
internal fun defaultComputerName(address: String): String {
    val host = address.trim()
    val numeric = host.contains(':') || host.split('.').all { it.toIntOrNull() != null }
    return if (numeric) host else host.substringBefore('.')
}
