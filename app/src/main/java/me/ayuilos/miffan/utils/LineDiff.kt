package me.ayuilos.miffan.utils

/**
 * Lines removed from [before] (false) and added in [after] (true), in order. A bounded LCS keeps unusually long texts
 * cheap: past [limit] lines the tails are listed as they are, removed then added.
 */
internal fun diffLines(before: List<String>, after: List<String>, limit: Int = 500): List<Pair<String, Boolean>> {
    val a = before.take(limit)
    val b = after.take(limit)
    val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
        lengths[i][j] = if (a[i] == b[j]) lengths[i + 1][j + 1] + 1 else maxOf(lengths[i + 1][j], lengths[i][j + 1])
    }
    return buildList {
        var i = 0; var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { i++; j++ }
                i < a.size && (j == b.size || lengths[i + 1][j] >= lengths[i][j + 1]) -> add(a[i++] to false)
                else -> add(b[j++] to true)
            }
        }
        before.drop(limit).forEach { add(it to false) }
        after.drop(limit).forEach { add(it to true) }
    }
}

/** Blank text has no lines, so an empty field never shows as a removed or added blank line. */
internal fun String?.textLines(): List<String> = if (isNullOrBlank()) emptyList() else lines()
