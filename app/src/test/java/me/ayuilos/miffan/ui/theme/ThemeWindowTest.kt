package me.ayuilos.miffan.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewParent
import android.view.Window
import androidx.compose.ui.window.DialogWindowProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ThemeWindowTest {
    private interface DialogParent : ViewParent, DialogWindowProvider

    private val window = mockk<Window>()
    private val activity = mockk<Activity> { every { window } returns this@ThemeWindowTest.window }

    private fun view(context: Context, parent: ViewParent? = null) = mockk<View> {
        every { this@mockk.context } returns context
        every { this@mockk.parent } returns parent
    }

    @Test fun activityViewUsesActivityWindow() {
        assertSame(window, view(activity).findThemeWindow())
    }

    @Test fun themedContextCanWrapMultipleLayersWithoutAnActivityCast() {
        val wrapper = mockk<ContextWrapper> { every { baseContext } returns activity }
        val themed = mockk<ContextThemeWrapper> { every { baseContext } returns wrapper }
        assertSame(window, view(themed).findThemeWindow())
    }

    @Test fun dialogWindowTakesPriorityOverWrappedHostActivity() {
        val dialogWindow = mockk<Window>()
        val dialogParent = mockk<DialogParent> { every { window } returns dialogWindow }
        val intermediate = mockk<ViewParent> { every { parent } returns dialogParent }
        val themed = mockk<ContextThemeWrapper> { every { baseContext } returns activity }
        assertSame(dialogWindow, view(themed, intermediate).findThemeWindow())
    }

    @Test fun contextWithoutAnActivityOrWindowIsIgnored() {
        val applicationContext = mockk<Context>()
        val themed = mockk<ContextThemeWrapper> { every { baseContext } returns applicationContext }
        assertNull(view(themed).findThemeWindow())
    }
}
