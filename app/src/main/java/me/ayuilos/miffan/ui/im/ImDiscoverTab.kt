package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Star
import me.rerere.hugeicons.stroke.Translate
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.ui.components.ui.CardGroup
import me.ayuilos.miffan.ui.context.LocalNavController

@Composable
internal fun ImDiscoverTab(innerPadding: PaddingValues) {
    val navController = LocalNavController.current
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = innerPadding) {
        item("title") { ImTabTitle(R.string.im_tab_discover) }
        item("entries") {
            CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                item(
                    onClick = { navController.navigate(Screen.ImageGen) },
                    leadingContent = { Icon(HugeIcons.Image01, null) },
                    headlineContent = { Text(stringResource(R.string.im_discover_image_gen)) },
                    supportingContent = { Text(stringResource(R.string.im_discover_image_gen_desc)) },
                )
                item(
                    onClick = { navController.navigate(Screen.Translator) },
                    leadingContent = { Icon(HugeIcons.Translate, null) },
                    headlineContent = { Text(stringResource(R.string.im_discover_translate)) },
                    supportingContent = { Text(stringResource(R.string.im_discover_translate_desc)) },
                )
                item(
                    onClick = { navController.navigate(Screen.Favorite) },
                    leadingContent = { Icon(HugeIcons.Star, null) },
                    headlineContent = { Text(stringResource(R.string.im_discover_favorites)) },
                    supportingContent = { Text(stringResource(R.string.im_discover_favorites_desc)) },
                )
                item(
                    onClick = { navController.navigate(Screen.Extensions) },
                    leadingContent = { Icon(HugeIcons.Puzzle, null) },
                    headlineContent = { Text(stringResource(R.string.im_discover_extensions)) },
                    supportingContent = { Text(stringResource(R.string.im_discover_extensions_desc)) },
                )
            }
        }
    }
}
