package me.ayuilos.miffan.ui.components.richtext

private val LINE_ENDING_REGEX = Regex("""\r\n?""")

// The Markdown parser recognizes LF; CR prevents closing code fences from matching.
internal fun normalizeMarkdownLineEndings(content: String): String =
    content.replace(LINE_ENDING_REGEX, "\n")
