package me.ayuilos.miffan.testutils

import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry

fun workspaceUiText(@StringRes id: Int, vararg args: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
