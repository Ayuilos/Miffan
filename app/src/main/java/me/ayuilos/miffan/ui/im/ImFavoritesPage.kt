package me.ayuilos.miffan.ui.im

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.MoreHorizontal
import me.rerere.hugeicons.stroke.Delete01
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Every saved message, newest first by month, narrowed by a search or by the partner who said it. */
@Composable
fun ImFavoritesPage(vm: ImDiscoverVM = koinViewModel()) {
    val nav = LocalNavController.current
    val favorites by vm.savedMessages.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val removed = stringResource(R.string.im_favorites_removed)
    var query by rememberSaveable { mutableStateOf("") }
    var partnerFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val all = favorites.orEmpty()
    val partners = all.mapNotNull { it.partner }.distinctBy { it.id }
    val shown = all.filter { favorite ->
        (partnerFilter == null || favorite.partner?.id?.toString() == partnerFilter) &&
            (query.isBlank() || favorite.preview.contains(query.trim(), ignoreCase = true))
    }
    val months = shown.groupBy { YearMonth.from(Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault())) }

    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_discover_favorites)) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("search") {
                ImSearchPill(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                    BasicTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, textStyle = style,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { field ->
                            Box {
                                if (query.isEmpty()) Text(stringResource(R.string.im_favorites_search), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                field()
                            }
                        })
                }
            }
            if (partners.size > 1) item("partners") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item("all") {
                        FilterChip(partnerFilter == null, { partnerFilter = null }, label = { Text(stringResource(R.string.im_favorites_all)) })
                    }
                    items(partners, key = { it.id.toString() }) { partner ->
                        val id = partner.id.toString()
                        FilterChip(partnerFilter == id, { partnerFilter = if (partnerFilter == id) null else id },
                            label = { Text(partnerName(partner)) },
                            leadingIcon = { AssistantAvatar(partner.name, partner.avatar, Modifier.size(20.dp)) })
                    }
                }
            }
            if (favorites != null && all.isEmpty()) item("empty") { ImEmpty(stringResource(R.string.im_discover_favorites_empty)) }
            else if (favorites != null && shown.isEmpty()) item("none") { ImEmpty(stringResource(R.string.im_favorites_no_match)) }
            months.forEach { (month, items) ->
                item("month:$month") {
                    ImSectionLabel(month.format(DateTimeFormatter.ofPattern(stringResource(R.string.im_favorites_month_pattern))),
                        Modifier.padding(horizontal = 16.dp))
                }
                items(items, key = { it.refKey }) { favorite ->
                    var menu by remember { mutableStateOf(false) }
                    ImCardGroup(Modifier.padding(horizontal = 16.dp)) {
                        ImFavoriteRow(favorite, maxLines = 8, onClick = { nav.openFavorite(favorite) }) {
                            Box {
                                IconButton(onClick = { menu = true }, Modifier.size(32.dp)) {
                                    Icon(HugeIcons.MoreHorizontal, stringResource(R.string.im_favorites_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                DropdownMenu(menu, { menu = false }) {
                                    ImPressMenuItem(HugeIcons.Copy01, stringResource(R.string.im_thread_copy)) {
                                        menu = false
                                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", favorite.preview))) }
                                    }
                                    ImPressMenuItem(HugeIcons.Delete01, stringResource(R.string.im_thread_unfavorite), destructive = true) {
                                        menu = false
                                        scope.launch { vm.removeFavorite(favorite.refKey); snackbar.showSnackbar(removed) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
