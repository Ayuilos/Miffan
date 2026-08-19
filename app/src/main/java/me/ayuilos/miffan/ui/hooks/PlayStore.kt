package me.ayuilos.miffan.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import me.ayuilos.miffan.utils.PlayStoreUtil

@Composable
fun rememberIsPlayStoreVersion(): Boolean {
    val context = LocalContext.current
    return remember {
        PlayStoreUtil.isInstalledFromPlayStore(context)
    }
}