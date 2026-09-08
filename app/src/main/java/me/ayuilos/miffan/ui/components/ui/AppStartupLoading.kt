package me.ayuilos.miffan.ui.components.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.utils.AppStartupAppearance

/** A lightweight still while settings load; starting the animation atlas here delays startup. */
@Composable
internal fun AppStartupLoading(appearance: AppStartupAppearance) {
    Column(
        modifier = Modifier.fillMaxSize().background(colorResource(appearance.backgroundRes)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        Image(
            painter = painterResource(appearance.portraitRes),
            contentDescription = if (appearance.whale) "蓝色大肥鱼，正在加载" else "Miffan，正在加载",
            modifier = Modifier.size(128.dp),
        )
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
    }
}
