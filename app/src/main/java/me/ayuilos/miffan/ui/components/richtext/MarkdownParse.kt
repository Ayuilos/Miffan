package me.ayuilos.miffan.ui.components.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

private val flavour by lazy {
    GFMFlavourDescriptor(
        makeHttpsAutoLinks = true, useSafeLinks = true
    )
}

private val parser by lazy {
    MarkdownParser(flavour)
}

// 前面紧跟反斜杠时是 \\ 换行，不是定界符，比如 \\[4pt] 里的 \[ 不是块级公式的开头
private val INLINE_LATEX_REGEX = Regex("(?<!\\\\)\\\\\\((.+?)\\\\\\)")
private val BLOCK_LATEX_REGEX = Regex("(?<!\\\\)\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL)
private val LATEX_BLOCK_LINE_BREAK_REGEX = Regex("""[ \t]*\r?\n[ \t]*""")

// 预处理markdown内容
private fun preProcess(rawContent: String): String {
    val content = linkifyBareUrls(normalizeMarkdownLineEndings(rawContent))
    // Most messages have no \( \) or \[ \] at all: skip the three regex passes over the whole text.
    if (!content.contains("\\(") && !content.contains("\\[")) return content
    // 先找出所有代码块的位置
    val codeBlocks = mutableListOf<IntRange>()
    CODE_BLOCK_REGEX.findAll(content).forEach { match ->
        codeBlocks.add(match.range)
    }

    // 检查位置是否在代码块内
    fun isInCodeBlock(position: Int): Boolean {
        return codeBlocks.any { range -> position in range }
    }

    // 替换行内公式 \( ... \) 到 $ ... $，但跳过代码块内的内容
    var result = INLINE_LATEX_REGEX.replace(content) { matchResult ->
        if (isInCodeBlock(matchResult.range.first)) {
            matchResult.value // 保持原样
        } else {
            "$" + matchResult.groupValues[1] + "$"
        }
    }

    // 替换块级公式 \[ ... \] 到 $$ ... $$，但跳过代码块内的内容
    result = BLOCK_LATEX_REGEX.replace(result) { matchResult ->
        if (isInCodeBlock(matchResult.range.first)) {
            matchResult.value // 保持原样
        } else {
            val formula = matchResult.groupValues[1]
                .trim()
                .replace(LATEX_BLOCK_LINE_BREAK_REGEX, " ")
            "$$" + formula + "$$"
        }
    }

    return result
}

/**
 * One top-level block of a message: a node and the text its offsets point into. Blocks of one message may point
 * into different texts, because a streamed reply keeps the blocks it already has and parses only its end again.
 * Keeping the same [node] and [content] instances is what lets Compose skip a block that did not change.
 */
internal class MarkdownTopBlock(val node: ASTNode, val content: String) {
    val hasHtml: Boolean = node.containsHtml()

    /** A line break or white space between blocks: nothing to show. */
    val blank: Boolean = node.type == MarkdownTokenTypes.EOL || node.type == MarkdownTokenTypes.WHITE_SPACE
    val text: String get() = content.substring(node.startOffset, node.endOffset)
}

internal class MarkdownParseResult(
    /** The raw text this was parsed from. */
    val source: String,
    val preprocessed: String,
    val blocks: List<MarkdownTopBlock>,
    /**
     * Where the last block starts, as a line start in [preprocessed], and how many [blocks] end before it. Text
     * appended to the message can change that block (a paragraph becomes a heading, a list gets another item), but
     * never the blocks before it. Zero when the whole text has to be parsed again.
     */
    private val tailStart: Int,
    private val stableBlocks: Int,
) {
    val hasHtml: Boolean = blocks.any { it.hasHtml }

    /**
     * Parses [source], reusing this result's blocks when [source] continues the same text: only the text from the
     * last block on is parsed again. The outcome is the same as [parseMarkdown]'s.
     */
    fun continuedBy(source: String): MarkdownParseResult {
        if (source == this.source) return this
        val preprocessed = preProcess(source)
        if (tailStart <= 0 || preprocessed.length < tailStart || !preprocessed.regionMatches(0, this.preprocessed, 0, tailStart)) {
            return parsePreprocessed(source, preprocessed)
        }
        val tail = preprocessed.substring(tailStart)
        val nodes = parser.buildMarkdownTreeFromString(tail).children
        // A link reference definition changes how the text before it reads: only a whole parse gets that right.
        if (nodes.any { it.type == MarkdownElementTypes.LINK_DEFINITION }) return parsePreprocessed(source, preprocessed)
        val kept = blocks.subList(0, stableBlocks)
        val added = nodes.map { MarkdownTopBlock(it, tail) }
        val (start, stable) = tailOf(nodes, tail)
        return MarkdownParseResult(
            source = source,
            preprocessed = preprocessed,
            blocks = kept + added,
            // The new tail may be the old one (its last block grew) or start further in (a new block began).
            tailStart = if (start > 0) tailStart + start else tailStart,
            stableBlocks = if (start > 0) kept.size + stable else kept.size,
        )
    }
}

private fun ASTNode.containsHtml(): Boolean {
    if (type == MarkdownElementTypes.HTML_BLOCK || type == MarkdownTokenTypes.HTML_TAG) return true
    return children.any { it.containsHtml() }
}

/**
 * The line start of the last block of [nodes] and the number of nodes that end before that line; 0 to 0 when
 * the text cannot be continued block by block (a single block, or link reference definitions).
 */
private fun tailOf(nodes: List<ASTNode>, text: String): Pair<Int, Int> {
    if (nodes.any { it.type == MarkdownElementTypes.LINK_DEFINITION }) return 0 to 0
    val blocks = nodes.filter { it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE }
    var last = blocks.lastOrNull() ?: return 0 to 0
    // The last block's first line may still be growing, and with it what the line is: after a list, "2" is a
    // paragraph until "2." makes it the list's next item; right under a table, "a" becomes a row with "a |"; right
    // under a paragraph, "#" is a heading until "#h" continues the paragraph. Only a block that a blank line has
    // ended, and that is not a list (lists go on across blank lines), is safe from what follows.
    blocks.getOrNull(blocks.size - 2)?.let { before ->
        val list = before.type == MarkdownElementTypes.ORDERED_LIST || before.type == MarkdownElementTypes.UNORDERED_LIST
        val lineBreaks = (before.endOffset until last.startOffset).count { text[it] == '\n' }
        if (list || lineBreaks < 2) last = before
    }
    // A block's indentation belongs to it (four spaces make code), so the tail starts at the block's line.
    val lineStart = if (last.startOffset == 0) 0 else text.lastIndexOf('\n', last.startOffset - 1) + 1
    if (lineStart <= 0) return 0 to 0
    val stable = nodes.indexOfFirst { it.endOffset > lineStart }.let { if (it < 0) nodes.size else it }
    return lineStart to stable
}

private fun parsePreprocessed(source: String, preprocessed: String): MarkdownParseResult {
    val nodes = parser.buildMarkdownTreeFromString(preprocessed).children
    val (start, stable) = tailOf(nodes, preprocessed)
    return MarkdownParseResult(source, preprocessed, nodes.map { MarkdownTopBlock(it, preprocessed) }, start, stable)
}

internal fun parseMarkdown(content: String): MarkdownParseResult = parsePreprocessed(content, preProcess(content))

// Rows of a long chat leave and re-enter composition while scrolling; parsing a message again each
// time it scrolls back into view costs frames. Only settled text is cached, not every streamed prefix.
// Bounded by characters: a table parses each of its cells, and a count of entries would let a few
// tables push whole messages out.
private val parseCache by lazy {
    object : android.util.LruCache<String, MarkdownParseResult>(PARSE_CACHE_CHARS) {
        override fun sizeOf(key: String, value: MarkdownParseResult): Int = key.length + PARSE_CACHE_ENTRY_CHARS
    }
}

private const val PARSE_CACHE_CHARS = 300_000
private const val PARSE_CACHE_ENTRY_CHARS = 200

internal fun cachedMarkdown(content: String): MarkdownParseResult? = parseCache.get(content)

internal fun parseMarkdownCached(content: String): MarkdownParseResult =
    parseCache.get(content) ?: parseMarkdown(content).also { parseCache.put(content, it) }
