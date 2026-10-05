package me.ayuilos.miffan.ui.im

import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.isImMode
import me.ayuilos.miffan.ui.context.Navigator
import kotlin.uuid.Uuid

/** The root destination of the active shell. */
fun Settings.homeScreen(): Screen =
    if (isImMode) Screen.Home else Screen.Chat(Uuid.random().toString())

/** Clears the back stack to the active shell's root. */
fun Navigator.navigateHome(settings: Settings) = clearAndNavigate(settings.homeScreen())

/** Opens a fresh chat; in the IM shell, back from it returns to the home tabs. */
fun Navigator.openFreshChat(settings: Settings) {
    val chat = Screen.Chat(Uuid.random().toString())
    if (settings.isImMode) {
        clearAndNavigate(Screen.Home)
        navigate(chat)
    } else {
        clearAndNavigate(chat)
    }
}
