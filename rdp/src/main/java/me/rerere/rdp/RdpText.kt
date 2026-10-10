package me.rerere.rdp

/** Direct input is deliberately small and layout-independent; other text belongs on cliprdr. */
object RdpText {
    const val MAX_DIRECT_UNITS = 1024
    const val MAX_CLIPBOARD_BYTES = 4 * 1024 * 1024
    fun canTypeDirectly(text: String): Boolean = text.length <= MAX_DIRECT_UNITS &&
        text.all { it in ' '..'~' || it == '\t' || it == '\n' || it == '\r' }
    fun requireClipboardSize(text: String) {
        require((text.length.toLong() + 1) * 2 <= MAX_CLIPBOARD_BYTES) { "Clipboard exceeds 4 MiB UTF-16 limit" }
        require('\u0000' !in text) { "Clipboard contains NUL" }
    }
}
