package me.ayuilos.miffan.ui.pages.extensions.workspace

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.termux.terminal.TerminalColors
import me.ayuilos.miffan.R
import java.util.Properties

/** Shared look of the local and remote terminals: one font, size and calm dark palette. */
internal object TerminalStyle {
    val textSize = 13.sp
    val contentPadding = 8.dp

    const val BACKGROUND = 0xFF15171C.toInt()
    const val FOREGROUND = 0xFFD7DAE0.toInt()
    val background = Color(BACKGROUND)

    /** Key bar and overlays sit one step above the terminal background. */
    val chrome = Color(0xFF1D2026)

    // One Dark-like ANSI colors; the bright half is lifted so `ls` colors stay readable.
    private val ansi = listOf(
        "#1E2127", "#E06C75", "#98C379", "#E5C07B", "#61AFEF", "#C678DD", "#56B6C2", "#ABB2BF",
        "#5C6370", "#F28B94", "#B5D99C", "#F0D197", "#8CC7F5", "#D7A1E7", "#7FCBD4", "#E6E9EF",
    )

    private var typeface: Typeface? = null

    fun typeface(context: Context): Typeface = typeface
        ?: (ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE).also { typeface = it }

    /**
     * Emulators copy the global scheme when created or reset, so this must run before any
     * TerminalEmulator is constructed.
     */
    fun installColorScheme() = installed

    private val installed: Unit by lazy {
        TerminalColors.COLOR_SCHEME.updateWith(Properties().apply {
            ansi.forEachIndexed { index, color -> setProperty("color$index", color) }
            setProperty("background", "#15171C")
            setProperty("foreground", "#D7DAE0")
            setProperty("cursor", "#8CC7F5")
        })
    }
}
