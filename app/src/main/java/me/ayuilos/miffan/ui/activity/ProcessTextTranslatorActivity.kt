package me.ayuilos.miffan.ui.activity

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import me.ayuilos.miffan.ui.pages.translator.ProcessTextTranslatorWindow
import me.ayuilos.miffan.ui.theme.MiffanTheme
import kotlin.math.roundToInt

/**
 * Handles Android's text-selection PROCESS_TEXT action in a compact, dialog-style window.
 * This is an Activity rather than an overlay, so it does not require draw-over-other-apps access.
 */
class ProcessTextTranslatorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedText = intent
            .takeIf { it.action == Intent.ACTION_PROCESS_TEXT }
            ?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (selectedText.isBlank()) {
            finish()
            return
        }

        setFinishOnTouchOutside(true)

        setContent {
            MiffanTheme {
                ProcessTextTranslatorWindow(
                    selectedText = selectedText,
                    onClose = ::finish,
                )
            }
        }

        val density = resources.displayMetrics.density
        val compactWidthDp = minOf(resources.configuration.screenWidthDp * 0.92f, 600f)
        window.setLayout(
            (compactWidthDp * density).roundToInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }
}
