package me.rerere.workspace

/** Safe, language-independent errors. Never retain key material or provider exception causes. */
class SshKeyException(val reason: Reason) : IllegalArgumentException(reason.name) {
    enum class Reason { EMPTY_PASSPHRASE, EXPORT_FAILED, INVALID_SIZE, IMPORT_FAILED }
}
