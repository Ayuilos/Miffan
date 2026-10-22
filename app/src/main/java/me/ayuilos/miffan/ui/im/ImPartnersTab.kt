package me.ayuilos.miffan.ui.im

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.im.computer.rememberPartnerComputer
import me.ayuilos.miffan.ui.im.thread.threadAssistantName
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Search01
import kotlin.uuid.Uuid

/**
 * The owner's partners as a contact list: one line about each, its working light, and its groups,
 * which fold. A search narrows the list; templates not yet added sit in a short strip at the end.
 */
@Composable
internal fun ImPartnersTab(vm: ImHomeVM, innerPadding: PaddingValues) {
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val threads by vm.partnerThreads.collectAsStateWithLifecycle()
    val templates = imPartnerTemplates(LocalConfiguration.current.locales[0])
    var query by rememberSaveable { mutableStateOf("") }
    // Folded groups, by id; separate from the Chats tab's, where a group also holds partner chats.
    var folded by rememberSaveable { mutableStateOf(listOf<String>()) }
    var moving by remember { mutableStateOf<Assistant?>(null) }

    val organization = settings.chatOrganization
    val taglines = settings.assistants.associate { it.id to partnerTagline(it, templates) }
    val searching = query.isNotBlank()
    val matches: (Assistant) -> Boolean = { partner ->
        !searching || partner.name.contains(query.trim(), ignoreCase = true) ||
            taglines[partner.id]?.contains(query.trim(), ignoreCase = true) == true
    }
    val grouped = organization.groups.flatMapTo(HashSet()) { it.chatIds }
    val ungrouped = settings.assistants.filter { it.id !in grouped && matches(it) }
    val groups = organization.groups.mapNotNull { group ->
        group.chatIds.mapNotNull { id -> settings.assistants.firstOrNull { it.id == id } }.filter(matches)
            .takeIf { it.isNotEmpty() }?.let { group to it }
    }
    val row: @Composable (Assistant) -> Unit = { partner ->
        ImPartnerRow(partner, taglines[partner.id], threads[partner.id], organization.isPinned(partner.id), vm) { moving = partner }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = innerPadding) {
        item("title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ImTabTitle(R.string.im_tab_partners, Modifier.weight(1f))
                Surface(onClick = { navController.navigate(Screen.AddPartner()) }, shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(end = 16.dp).size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(HugeIcons.Add01, stringResource(R.string.im_partners_add)) }
                }
            }
        }
        item("search") {
            ImPartnerSearchField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
        items(ungrouped, key = { it.id.toString() }) { row(it) }
        groups.forEach { (group, members) ->
            val key = group.id.toString()
            // A search shows every match, folded or not.
            val open = searching || key !in folded
            item("group:$key") {
                ImPartnerGroupHeader(group.name, members.size, open) {
                    folded = if (key in folded) folded - key else folded + key
                }
            }
            if (open) items(members, key = { it.id.toString() }) { row(it) }
        }
        if (searching && ungrouped.isEmpty() && groups.isEmpty()) item("no_match") { ImEmpty(stringResource(R.string.im_partners_no_match)) }
        if (!searching) item("suggest") {
            ImPartnerSuggestions(templates.filter { template -> settings.assistants.none { it.id == template.id } }, vm)
        }
    }
    moving?.let { partner ->
        val all = organization.groups
        ImGroupPickerSheet(
            groups = all,
            current = all.firstOrNull { partner.id in it.chatIds }?.id,
            onPick = { vm.moveToGroup(partner.id, it); moving = null },
            onCreate = { vm.createGroup(it, partner.id); moving = null },
            onDismiss = { moving = null },
        )
    }
}

@Composable
private fun ImPartnerSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    ImSearchPill(modifier) {
        val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { field ->
                Box {
                    if (query.isEmpty()) Text(stringResource(R.string.im_partners_search), style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    field()
                }
            },
        )
        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(24.dp)) {
            Icon(HugeIcons.Cancel01, stringResource(R.string.cancel), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImPartnerGroupHeader(name: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    // Same as the Chats tab's group header: the arrow follows the name and points right while folded.
    val arrow by animateFloatAsState(if (open) 0f else -90f, label = "group arrow")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Name and arrow share one weighted row, so the count sits at the far end.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(HugeIcons.ArrowDown01, null, Modifier.size(18.dp).rotate(arrow), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(count.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A partner in the list: tap opens its profile, long press pins it or moves it to a group. */
@Composable
private fun ImPartnerRow(assistant: Assistant, tagline: String?, thread: ImChatItem?, pinned: Boolean, vm: ImHomeVM, onMove: () -> Unit) {
    val navController = LocalNavController.current
    val openProfile: () -> Unit = { navController.navigate(Screen.PartnerProfile(assistant.id.toString())) }
    val computer by rememberPartnerComputer(assistant.id)
    val running = thread?.typing == true
    val waiting = thread?.waiting == true
    val menu = rememberPressMenu()
    Box {
        Row(
            Modifier.fillMaxWidth().imLongPress(menu::open).clickable(onClick = openProfile).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(52.dp)) {
                // The mascot consumes taps for its own reactions, so it forwards the row action.
                AssistantAvatar(assistant.name, assistant.avatar, Modifier.size(52.dp), onClick = openProfile)
                RunningLight(running, 52.dp, waiting = waiting)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(partnerName(assistant), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (computer != null) Icon(HugeIcons.Computer, stringResource(R.string.im_partners_uses_computer),
                        Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    if (running || waiting) Text(
                        stringResource(if (waiting) R.string.im_partner_waiting else R.string.im_partner_running),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (waiting) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    )
                }
                Text(tagline ?: stringResource(R.string.im_thread_intro, threadAssistantName(assistant)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ImPressMenu(menu) {
            ImPressMenuItem(if (pinned) HugeIcons.PinOff else HugeIcons.Pin,
                stringResource(if (pinned) R.string.im_p5_unpin else R.string.im_p5_pin)) { menu.close(); vm.setPinned(assistant.id, !pinned) }
            ImPressMenuItem(HugeIcons.Folder01, stringResource(R.string.im_chats_move_to_group)) { menu.close(); onMove() }
        }
    }
    HorizontalDivider(Modifier.padding(start = 84.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** "Make your own" and the templates not added yet, as a strip that never outweighs the owner's own partners. */
@Composable
private fun ImPartnerSuggestions(templates: List<ImPartnerTemplate>, vm: ImHomeVM) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf<Uuid?>(null) }
    Column(Modifier.padding(top = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.im_partners_suggest_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { navController.navigate(Screen.AddPartner()) }) { Text(stringResource(R.string.im_partners_all_templates)) }
        }
        // A plain scrolling row, so every card can match the tallest one.
        Row(Modifier.horizontalScroll(rememberScrollState()).height(IntrinsicSize.Min).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            run {
                Surface(onClick = { navController.navigate(Screen.AddPartner(custom = true)) }, shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.width(132.dp).fillMaxHeight()) {
                    ImSuggestionContent(
                        modifier = Modifier.fillMaxHeight(),
                        avatar = {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(HugeIcons.Add01, null, tint = MaterialTheme.colorScheme.onPrimary) }
                            }
                        },
                        title = stringResource(R.string.im_partners_make_own),
                        description = stringResource(R.string.im_partners_make_own_desc),
                    )
                }
            }
            templates.forEach { template ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(132.dp).fillMaxHeight()) {
                    ImSuggestionContent(
                        avatar = { AssistantAvatar(template.name, template.avatar, Modifier.size(52.dp)) },
                        title = template.name,
                        description = template.description,
                    ) {
                        FilledTonalButton(
                            enabled = adding == null,
                            onClick = {
                                adding = template.id
                                scope.launch {
                                    try {
                                        vm.addPartner(template.assistant())
                                        navController.navigate(Screen.PartnerProfile(template.id.toString()))
                                    } finally { adding = null }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            modifier = Modifier.heightIn(min = 36.dp),
                        ) { Text(stringResource(R.string.im_partners_add_short)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImSuggestionContent(
    avatar: @Composable () -> Unit,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
        avatar()
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        action?.invoke()
    }
}
