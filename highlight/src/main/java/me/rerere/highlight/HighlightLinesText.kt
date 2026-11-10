package me.rerere.highlight

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Code this short is highlighted in one pass once it is complete, exactly as [CodeHighlightText] does. */
private const val WHOLE_PASS_CHARS = 4096

/** What one composition may highlight on the main thread; the rest follows from a background thread. */
private const val MAIN_THREAD_CHARS = 4096

/**
 * The pieces of one code text as it changes. A streamed code block grows a little many times a second: each time
 * only its new lines are highlighted, and the pieces before them stay the same objects.
 */
private class HighlightLinesState(private val highlighter: CodeHighlighter, private val language: String) {
    private var code = ""
    private var whole = false
    private var pieces: List<HighlightedLines> = emptyList()

    fun update(code: String, complete: Boolean): List<HighlightedLines> {
        val whole = complete && code.length <= WHOLE_PASS_CHARS
        if (code == this.code && whole == this.whole) return pieces
        // Rules that read across lines (a heredoc, a macro continued after a backslash) only see the whole text.
        val result = if (whole) highlighter.highlightWholeInLines(code, language)
        else highlighter.highlightLines(code, language, pieces, budget = MAIN_THREAD_CHARS)
        return result.also { keep(code, whole, it) }
    }

    fun finish(code: String, started: List<HighlightedLines>): List<HighlightedLines> =
        highlighter.highlightLines(code, language, started)

    fun keep(code: String, whole: Boolean, pieces: List<HighlightedLines>) {
        this.code = code
        this.whole = whole
        this.pieces = pieces
    }

    fun isFor(code: String): Boolean = code == this.code
}

/**
 * [code] highlighted and laid out in pieces of lines, so that a block that grows (a streamed reply) highlights,
 * measures and draws only its newest lines, and a long block is highlighted at all: its start at once, the rest
 * from a background thread. Looks the same as [CodeHighlightText] with the same arguments.
 *
 * [complete] is false while the code is still being written.
 */
@Composable
fun CodeHighlightLines(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    complete: Boolean = true,
    colors: HighlightTextColorPalette = HighlightTextColorPalette.Default,
    fontSize: TextUnit = 12.sp,
    fontFamily: FontFamily = FontFamily.Monospace,
    lineHeight: TextUnit = TextUnit.Unspecified,
    softWrap: Boolean = true,
) {
    val highlighter = LocalCodeHighlighter.current
    val state = remember(language, highlighter) { HighlightLinesState(highlighter, language) }
    val started = remember(code, complete, state) { state.update(code, complete) }
    var finished by remember(code, complete, state) { mutableStateOf<List<HighlightedLines>?>(null) }
    if (started.any { it.pending }) LaunchedEffect(code, complete, state) {
        val all = withContext(Dispatchers.Default) { state.finish(code, started) }
        if (state.isFor(code)) state.keep(code, whole = false, all)
        finished = all
    }
    val pieces = finished ?: started

    val style = LocalTextStyle.current.merge(
        TextStyle(
            fontSize = fontSize,
            fontFamily = fontFamily,
            fontStyle = FontStyle.Normal,
            fontWeight = FontWeight.Normal,
            lineHeight = lineHeight,
            fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0",
        )
    )
    // One text trims (or not) above its first line and below its last. The pieces are that one text cut at line
    // ends, so only the first piece may trim its top and only the last its bottom.
    val styles = remember(style) { PieceStyles(style) }
    // A piece keeps its closing line break, so that a selection across pieces copies it. Laid out, that is an
    // empty last line the next piece's first line takes the place of.
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val emptyLine = remember(styles, measurer) {
        measurer.measure("a\n", styles.middle).let { (it.getLineBottom(1) - it.getLineTop(1)).roundToInt() }
    }

    Column(modifier) {
        pieces.fastForEachIndexed { index, piece ->
            val last = index == pieces.lastIndex
            CodePiece(
                piece = piece,
                colors = colors,
                style = styles.of(first = index == 0, last = last),
                softWrap = softWrap,
                cut = if (!last && piece.text.endsWith('\n')) emptyLine else 0,
            )
        }
    }
}

private class PieceStyles(style: TextStyle) {
    private val whole = style.lineHeightStyle ?: LineHeightStyle.Default
    private val trimsTop = whole.trim == LineHeightStyle.Trim.Both || whole.trim == LineHeightStyle.Trim.FirstLineTop
    private val trimsBottom = whole.trim == LineHeightStyle.Trim.Both || whole.trim == LineHeightStyle.Trim.LastLineBottom

    private fun trimmed(top: Boolean, bottom: Boolean): TextStyle = TextStyle(
        lineHeightStyle = LineHeightStyle(
            alignment = whole.alignment,
            trim = when {
                top && bottom -> LineHeightStyle.Trim.Both
                top -> LineHeightStyle.Trim.FirstLineTop
                bottom -> LineHeightStyle.Trim.LastLineBottom
                else -> LineHeightStyle.Trim.None
            },
            mode = whole.mode,
        )
    )

    private val only = style.merge(trimmed(trimsTop, trimsBottom))
    private val first = style.merge(trimmed(trimsTop, false))
    private val last = style.merge(trimmed(false, trimsBottom))
    val middle = style.merge(trimmed(false, false))

    fun of(first: Boolean, last: Boolean): TextStyle = when {
        first && last -> only
        first -> this.first
        last -> this.last
        else -> middle
    }
}

@Composable
private fun CodePiece(
    piece: HighlightedLines,
    colors: HighlightTextColorPalette,
    style: TextStyle,
    softWrap: Boolean,
    cut: Int,
) {
    val text = remember(piece, colors) {
        buildAnnotatedString { piece.tokens.forEach { buildHighlightText(it, colors) } }
    }
    Text(
        text = text,
        // A layer of its own: while the block grows, the pieces that are done are not drawn again.
        modifier = if (cut == 0) Modifier.graphicsLayer() else Modifier.graphicsLayer().layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, (placeable.height - cut).coerceAtLeast(0)) { placeable.place(0, 0) }
        },
        style = style,
        softWrap = softWrap,
        overflow = TextOverflow.Visible,
    )
}
