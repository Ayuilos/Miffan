package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ImPartnersTab(vm: ImHomeVM, innerPadding: PaddingValues) {
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + 16.dp,
            start = 12.dp,
            end = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
            ImTabTitle(R.string.im_tab_partners, modifier = Modifier.padding(start = 0.dp))
        }
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.im_partners_mine),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { navController.navigate(Screen.Assistant) }) {
                    Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.im_partners_add), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        items(settings.assistants, key = { it.id.toString() }) { assistant ->
            val openChat: () -> Unit = { navController.navigate(Screen.Thread(assistant.id.toString())) }
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .combinedClickable(
                        onClick = openChat,
                        onLongClick = { navController.navigate(Screen.AssistantDetail(assistant.id.toString())) },
                    )
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The mascot consumes taps for its own reactions, so it forwards the cell action.
                AssistantAvatar(
                    name = assistant.name,
                    value = assistant.avatar,
                    onClick = openChat,
                    modifier = Modifier.size(72.dp),
                )
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = stringResource(R.string.im_partners_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}
