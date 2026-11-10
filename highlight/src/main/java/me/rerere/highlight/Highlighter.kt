package me.rerere.highlight

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import me.rerere.highlight.core.HighlightEngine
import me.rerere.highlight.languages.builtinLanguages

private const val MAX_CODE_LENGTH = 4096

val LocalCodeHighlighter = staticCompositionLocalOf { CodeHighlighter() }

/**
 * Some whole lines of a longer code text, highlighted in the context of the lines before them.
 *
 * [tokens] spell out [text]. An unchanged instance means unchanged lines, which is what lets a growing text
 * (a streamed reply) lay out and draw only the lines that are new.
 */
class HighlightedLines internal constructor(
    /** The lines as written, each with its line break; the last line of the whole text may have none. */
    val text: String,
    val tokens: List<HighlightToken>,
    /** Not highlighted yet: [tokens] is the plain text. */
    val pending: Boolean,
    /** Where the lines stopped, for the piece after them; null at the start state. */
    internal val end: HighlightEngine.Continuation?,
    /** The piece after these lines can go on from [end]; false for a piece of a one-pass result. */
    internal val continues: Boolean = true,
)

/**
 * A pure Kotlin syntax highlighter.
 *
 * Grammars are ported from highlight.js 11.11.1 and run on [HighlightEngine], a port of its mode
 * stack parser. An unsupported language is returned unhighlighted.
 */
class CodeHighlighter {
    private val engine = HighlightEngine(builtinLanguages())

    // A compiled grammar keeps its scanning position in its matchers, so only one text can be highlighted at a
    // time. Held per piece, not per text, so the main thread never waits for a long text highlighted elsewhere.
    private val lock = Any()

    fun highlight(code: String, language: String): List<HighlightToken> {
        if (code.isEmpty()) return emptyList()

        return synchronized(lock) { engine.highlight(code, language) }
            ?: listOf(HighlightToken.Plain(code))
    }

    /**
     * Highlights [code] in pieces of [LINES_PER_PIECE] lines, each continuing where the one before it stopped.
     *
     * Pieces of [previous] that still hold the same lines are returned as they are, so highlighting a text that
     * grew costs only its new lines. After [budget] characters of new work the remaining pieces come back
     * [HighlightedLines.pending] (plain); passing the result back as [previous] finishes them.
     *
     * A piece cannot see the lines after it. The few rules that need to (a Ruby heredoc or a Markdown setext
     * heading is one match over several lines; a C macro or a Dockerfile RUN goes on after a backslash) lose their
     * color where such a construct crosses from one piece into the next. An embedded language that is detected
     * rather than named (the script and style of an HTML page) is detected from the part in the first piece it
     * appears in. [highlightWholeInLines] has neither limit and is the one to use for a text that is complete
     * and short enough.
     */
    fun highlightLines(
        code: String,
        language: String,
        previous: List<HighlightedLines> = emptyList(),
        budget: Int = Int.MAX_VALUE,
        /** Asked before each piece; once false, the remaining pieces come back pending. */
        keepGoing: () -> Boolean = { true },
    ): List<HighlightedLines> = highlightLines(code, language, previous, budget, LINES_PER_PIECE, keepGoing)

    internal fun highlightLines(
        code: String,
        language: String,
        previous: List<HighlightedLines>,
        budget: Int,
        linesPerPiece: Int,
        keepGoing: () -> Boolean = { true },
    ): List<HighlightedLines> {
        if (code.isEmpty()) return emptyList()
        val supported = supports(language)
        val pieces = ArrayList<HighlightedLines>(code.length / 400 + 1)
        var reusing = true
        var spent = 0
        var postponed = false
        var continuation: HighlightEngine.Continuation? = null
        var start = 0
        while (start < code.length) {
            val end = endOfLines(code, start, linesPerPiece)
            val old = previous.getOrNull(pieces.size)
            if (reusing && old != null && !old.pending && old.continues &&
                old.text.length == end - start && code.regionMatches(start, old.text, 0, old.text.length)
            ) {
                pieces += old
                continuation = old.end
            } else {
                reusing = false
                val text = code.substring(start, end)
                val plain = listOf(HighlightToken.Plain(text))
                when {
                    !supported -> pieces += HighlightedLines(text, plain, pending = false, end = null)
                    // A single line this long is minified code or data; scanning it is slow and tells little.
                    text.length > MAX_PIECE_CHARS -> pieces += HighlightedLines(text, plain, pending = false, end = continuation)
                    postponed || (spent > 0 && spent + text.length > budget) || !keepGoing() -> {
                        // Without the pieces before it, a piece has no context to be highlighted in.
                        postponed = true
                        pieces += HighlightedLines(text, plain, pending = true, end = null)
                    }
                    else -> {
                        val piece = synchronized(lock) { engine.highlightPiece(text, language, continuation) }!!
                        continuation = piece.continuation
                        spent += text.length
                        pieces += HighlightedLines(text, piece.tokens, pending = false, end = continuation)
                    }
                }
            }
            start = end
        }
        return pieces
    }

    /**
     * Highlights [code] in one pass, as [highlight] does, and hands the result out in the same pieces of lines as
     * [highlightLines]. It costs the whole text every time; its pieces are not a starting point for a longer text.
     */
    fun highlightWholeInLines(code: String, language: String): List<HighlightedLines> {
        if (code.isEmpty()) return emptyList()
        val tokens = highlight(code, language)
        val pieces = ArrayList<HighlightedLines>(code.length / 400 + 1)
        var token = 0
        // How much of tokens[token] earlier pieces have taken.
        var taken = 0
        var start = 0
        while (start < code.length) {
            val end = endOfLines(code, start, LINES_PER_PIECE)
            val piece = ArrayList<HighlightToken>()
            var need = end - start
            while (need > 0) {
                val current = tokens[token]
                val rest = current.content.length - taken
                if (rest <= need) {
                    piece += if (taken == 0) current else current.part(taken, current.content.length)
                    need -= rest
                    token++
                    taken = 0
                } else {
                    piece += current.part(taken, taken + need)
                    taken += need
                    need = 0
                }
            }
            pieces += HighlightedLines(code.substring(start, end), piece, pending = false, end = null, continues = false)
            start = end
        }
        return pieces
    }

    private fun HighlightToken.part(from: Int, to: Int): HighlightToken = when (this) {
        is HighlightToken.Plain -> HighlightToken.Plain(content.substring(from, to))
        is HighlightToken.Styled -> HighlightToken.Styled(content.substring(from, to), type)
    }

    /** The index after [lines] lines of [code] from [start], or the end of [code]. */
    private fun endOfLines(code: String, start: Int, lines: Int): Int {
        var end = start
        repeat(lines) {
            val lineBreak = code.indexOf('\n', end)
            if (lineBreak < 0) return code.length
            end = lineBreak + 1
        }
        return end
    }

    fun supports(language: String): Boolean = engine.supports(language)

    companion object {
        /** Short enough that the piece being written is cheap to highlight and lay out again on every change. */
        const val LINES_PER_PIECE = 16
        private const val MAX_PIECE_CHARS = 8192
    }
}

@Composable
fun CodeHighlightText(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    colors: HighlightTextColorPalette = HighlightTextColorPalette.Default,
    fontSize: TextUnit = 12.sp,
    fontFamily: FontFamily = FontFamily.Monospace,
    fontStyle: FontStyle = FontStyle.Normal,
    fontWeight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val highlighter = LocalCodeHighlighter.current
    val annotatedString = remember(code, language, colors, highlighter) {
        if (code.length > MAX_CODE_LENGTH) {
            AnnotatedString(code)
        } else {
            buildAnnotatedString {
                highlighter.highlight(code, language).forEach { token ->
                    buildHighlightText(token, colors)
                }
            }
        }
    }

    Text(
        modifier = modifier,
        text = annotatedString,
        style = LocalTextStyle.current.copy(fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0"),
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
}
