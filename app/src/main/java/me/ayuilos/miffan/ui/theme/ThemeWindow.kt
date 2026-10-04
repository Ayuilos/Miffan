package me.ayuilos.miffan.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import androidx.compose.ui.window.DialogWindowProvider

/** A Dialog has its own window even when its wrapped context belongs to the host Activity. */
internal fun View.findThemeWindow(): Window? {
    var ancestor = parent
    while (ancestor != null) {
        if (ancestor is DialogWindowProvider) return ancestor.window
        ancestor = ancestor.parent
    }

    var currentContext = context
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext.window
        val base = currentContext.baseContext
        if (base === currentContext) return null
        currentContext = base
    }
    return null
}
