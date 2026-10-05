package me.ayuilos.miffan

import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry

/** The app's text for [id] in the device's current language, so UI tests pass in every locale. */
fun appString(@StringRes id: Int, vararg args: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
