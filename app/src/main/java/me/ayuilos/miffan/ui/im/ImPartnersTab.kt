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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import kotlinx.coroutines.launch
import me.ayuilos.miffan.data.model.Assistant
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

    val templates = imPartnerTemplates(LocalConfiguration.current.locales[0])
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
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
            // Cancel the grid's 12dp side padding so the title lines up with the other tabs.
            ImTabTitle(R.string.im_tab_partners, modifier = Modifier.offset(x = (-12).dp))
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
                TextButton(onClick = {
                    if (!adding) scope.launch {
                        adding = true
                        try {
                            val partner = Assistant(name = "Miffan")
                            vm.addPartner(partner)
                            navController.navigate(Screen.PartnerProfile(partner.id.toString()))
                        } finally { adding = false }
                    }
                }) {
                    Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.im_partners_add), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        items(settings.assistants, key = { it.id.toString() }) { assistant ->
            val openChat: () -> Unit = { navController.navigate(Screen.PartnerProfile(assistant.id.toString())) }
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .combinedClickable(
                        onClick = openChat,
                        onLongClick = { navController.navigate(Screen.PartnerProfile(assistant.id.toString())) },
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
        item(key = "recommendations", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                HorizontalDivider()
                Text(stringResource(R.string.im_p5_recommended), style = MaterialTheme.typography.titleLarge)
                templates.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { template ->
                            val added = settings.assistants.any { it.id == template.id }
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AssistantAvatar(template.name, template.avatar, Modifier.size(56.dp))
                                    Text(template.name, style = MaterialTheme.typography.titleMedium)
                                    Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.heightIn(min = 36.dp))
                                    OutlinedButton(enabled = !added && !adding, onClick = {
                                        scope.launch {
                                            adding = true
                                            try { vm.addPartner(template.assistant()); navController.navigate(Screen.PartnerProfile(template.id.toString())) }
                                            finally { adding = false }
                                        }
                                    }) { Text(stringResource(if (added) R.string.im_p5_added else R.string.im_partners_add)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
