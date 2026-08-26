package me.ayuilos.miffan.ui.pages.extensions.workspace

internal data class DelimitedTextTable(
    val headers: List<String>,
    val rows: List<List<String>>,
    val truncated: Boolean,
)

/** Small RFC-4180-style parser used only for bounded workspace previews. */
internal fun parseDelimitedText(
    text: String,
    delimiter: Char,
    maxRows: Int = 100,
    maxColumns: Int = 30,
): DelimitedTextTable {
    require(maxRows > 0 && maxColumns > 0)
    val parsedRows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0
    var truncated = false

    fun finishField() {
        if (row.size < maxColumns) {
            row += field.toString()
        } else {
            truncated = true
        }
        field.clear()
    }

    fun finishRow() {
        finishField()
        if (parsedRows.size < maxRows + 1) {
            parsedRows += row
        } else {
            truncated = true
        }
        row = mutableListOf()
    }

    while (index < text.length) {
        val char = text[index]
        when {
            quoted && char == '"' && text.getOrNull(index + 1) == '"' -> {
                field.append('"')
                index++
            }
            char == '"' -> quoted = !quoted
            !quoted && char == delimiter -> finishField()
            !quoted && char == '\n' -> finishRow()
            !quoted && char == '\r' -> {
                if (text.getOrNull(index + 1) == '\n') index++
                finishRow()
            }
            else -> field.append(char)
        }
        index++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) finishRow()

    val headers = parsedRows.firstOrNull().orEmpty()
    return DelimitedTextTable(
        headers = headers,
        rows = parsedRows.drop(1).take(maxRows),
        truncated = truncated || parsedRows.size > maxRows + 1,
    )
}
