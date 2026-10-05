package me.ayuilos.miffan.ui.components.ui

import androidx.compose.material3.Badge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.R

@Composable
internal fun WhaleThemeNewBadge() {
    Badge { Text(stringResource(R.string.whale_new_theme_badge)) }
}
