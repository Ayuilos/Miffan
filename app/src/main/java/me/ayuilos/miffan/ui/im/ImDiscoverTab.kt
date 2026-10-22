package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.datastore.findModelById
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.utils.toLocalString
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Star
import me.rerere.hugeicons.stroke.Translate
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Things to do without picking a partner first (draw, translate) and what the owner kept: saved
 * messages and recent drawings, each a step away from its full page.
 */
@Composable
internal fun ImDiscoverTab(innerPadding: PaddingValues, vm: ImDiscoverVM = koinViewModel()) {
    val nav = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val favorites by vm.savedMessages.collectAsStateWithLifecycle()
    val drawings by vm.recentDrawings.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = innerPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("title") { ImTabTitle(R.string.im_tab_discover) }
        item("tools") {
            Row(Modifier.padding(horizontal = 16.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val drawReady = settings.findModelById(settings.imageGenerationModelId) != null
                ImToolCard(HugeIcons.PencilEdit01, stringResource(R.string.im_discover_image_gen), stringResource(R.string.im_discover_draw_desc),
                    colors.primaryContainer, colors.onPrimaryContainer, colors.primary, colors.onPrimary, { nav.navigate(Screen.ImDraw()) }) {
                    if (drawings.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        drawings.take(3).forEach { image ->
                            AsyncImage(File(image.filePath), null, Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)), contentScale = ContentScale.Crop)
                        }
                    } else if (!drawReady) Text(stringResource(R.string.im_discover_draw_off), style = MaterialTheme.typography.labelMedium)
                }
                ImToolCard(HugeIcons.Translate, stringResource(R.string.im_discover_translate), stringResource(R.string.im_discover_translate_desc2),
                    colors.secondaryContainer, colors.onSecondaryContainer, colors.secondary, colors.onSecondary, { nav.navigate(Screen.ImTranslate) })
            }
        }
        item("favorites") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                val saved = favorites.orEmpty()
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ImSectionLabel(stringResource(R.string.im_discover_favorites), Modifier.weight(1f).padding(bottom = 12.dp))
                    if (saved.isNotEmpty()) TextButton(onClick = { nav.navigate(Screen.ImFavorites) }) {
                        Text(pluralStringResource(R.plurals.im_discover_favorites_all, saved.size, saved.size))
                    }
                }
                if (favorites != null && saved.isEmpty()) ImCardGroup {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(HugeIcons.Star, null, Modifier.size(20.dp)) }
                        }
                        Text(stringResource(R.string.im_discover_favorites_empty), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                } else if (saved.isNotEmpty()) ImCardGroup {
                    saved.take(3).forEachIndexed { index, favorite ->
                        if (index > 0) ImCardDivider(start = 16.dp)
                        ImFavoriteRow(favorite, maxLines = 2, onClick = { nav.openFavorite(favorite) })
                    }
                }
            }
        }
        if (drawings.isNotEmpty()) item("drawings") {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ImSectionLabel(stringResource(R.string.im_discover_drawings), Modifier.weight(1f).padding(bottom = 12.dp))
                    TextButton(onClick = { nav.navigate(Screen.ImDraw(gallery = true)) }) { Text(stringResource(R.string.im_discover_album)) }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(drawings, key = { it.id }) { image ->
                        AsyncImage(File(image.filePath), image.prompt, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)).clickable { nav.navigate(Screen.ImDraw(gallery = true)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.ImToolCard(
    icon: ImageVector,
    title: String,
    description: String,
    container: Color,
    content: Color,
    accent: Color,
    onAccent: Color,
    onClick: () -> Unit,
    footer: @Composable () -> Unit = {},
) {
    Surface(onClick = onClick, color = container, contentColor = content, shape = RoundedCornerShape(22.dp),
        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 168.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(color = accent, contentColor = onAccent, shape = RoundedCornerShape(14.dp), modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
            }
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            footer()
        }
    }
}

/** A saved message: its partner, when it was saved, and the words. */
@Composable
internal fun ImFavoriteRow(favorite: ImFavorite, maxLines: Int, onClick: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            favorite.partner?.let { AssistantAvatar(it.name, it.avatar, Modifier.size(24.dp)) }
            Text(favorite.partner?.let { partnerName(it) } ?: stringResource(R.string.im_p5_unknown_source),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(favoriteDate(favorite.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Text(favorite.preview, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun favoriteDate(millis: Long): String {
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.im_time_today)
        today.minusDays(1) -> stringResource(R.string.im_time_yesterday)
        else -> date.toLocalString(includeYear = date.year != today.year)
    }
}

/** Opens the saved message where it was said; easy chat turns the conversation into its partner's thread. */
internal fun me.ayuilos.miffan.ui.context.Navigator.openFavorite(favorite: ImFavorite) {
    navigate(Screen.Chat(id = favorite.conversationId.toString(), nodeId = favorite.nodeId.toString()))
}
