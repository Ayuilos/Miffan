package me.ayuilos.miffan.ui.im

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.OWNER_PARTICIPANT_ID
import me.ayuilos.miffan.data.model.PartnerChat
import me.ayuilos.miffan.data.model.PartnerChatApproval
import me.ayuilos.miffan.data.model.PartnerChatMessage
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.ui.im.thread.ThreadPartnerMessage
import me.ayuilos.miffan.ui.im.thread.ThreadToolPrompt
import me.ayuilos.miffan.ui.im.thread.isPartnerMessage
import me.ayuilos.miffan.data.partner.PartnerChatService
import me.ayuilos.miffan.data.thread.ThreadListState
import me.ayuilos.miffan.data.thread.ThreadTimeline
import me.ayuilos.miffan.ui.components.richtext.MarkdownBlock
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.components.ui.AssistantGenerationPhase
import me.ayuilos.miffan.ui.components.ui.EdgeBlurScrim
import me.ayuilos.miffan.ui.components.ui.EdgeScrimPosition
import me.ayuilos.miffan.ui.components.ui.GlassIconButton
import me.ayuilos.miffan.ui.components.ui.GlassSurface
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.im.thread.ThreadDateLabel
import me.ayuilos.miffan.ui.im.thread.ThreadLiveStatusRow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Copy01
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/** What the partner chat page shows: the members, the final messages and who is typing. */
data class PartnerChatState(
    val chat: PartnerChat,
    val members: List<Assistant>,
    val messages: List<PartnerChatMessage>,
    val typing: Set<Uuid>,
    /** Members' tool calls waiting for the owner. */
    val approvals: List<PartnerChatApproval>,
)

class PartnerChatVM(
    val chatId: Uuid,
    settingsStore: SettingsStore,
    private val service: PartnerChatService,
    private val listState: ThreadListState,
) : ViewModel() {
    val state: StateFlow<PartnerChatState?> = combine(
        service.observeChat(chatId), service.observeMessages(chatId), service.observeTyping(chatId),
        service.observeApprovals(chatId), settingsStore.settingsFlow,
    ) { chat, messages, typing, approvals, settings ->
        chat ?: return@combine null
        PartnerChatState(chat, chat.memberIds.mapNotNull { settings.getAssistantById(it) }, messages, typing, approvals)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun answer(approval: PartnerChatApproval, toolCallId: String, approved: Boolean) {
        viewModelScope.launch { service.answerApproval(chatId, approval.memberId, toolCallId, approved) }
    }

    fun send(text: String, mentions: List<Uuid>) {
        viewModelScope.launch { service.sendFromOwner(chatId, text.trim(), mentions) }
    }

    fun markRead() = listState.markRead(chatId)
}

/**
 * Two partners talking. Their messages sit on the left with avatar and name, since more than one
 * partner speaks here; the owner's sit on the right. The owner joins by writing, and can @ a
 * partner to choose who answers.
 */
@Composable
internal fun PartnerChatPage(chatId: Uuid, vm: PartnerChatVM = koinViewModel { parametersOf(chatId) }) {
    val nav = LocalNavController.current
    val state by vm.state.collectAsStateWithLifecycle()
    val hazeState = rememberHazeState()
    val density = LocalDensity.current
    var topChrome by remember { mutableStateOf(0.dp) }
    var bottomChrome by remember { mutableStateOf(0.dp) }
    val listState = rememberLazyListState()
    val members = state?.members.orEmpty()
    val messages = state?.messages.orEmpty()
    val typing = state?.typing.orEmpty()
    val group = state?.chat?.isGroup == true
    val names = members.map { partnerName(it) }.joinToString(" · ")
    val title = if (group) "${state?.chat?.title?.takeIf { it.isNotBlank() } ?: names} (${members.size})" else names
    val approvalCount = state?.approvals?.size ?: 0
    LaunchedEffect(messages.size, typing, approvalCount) {
        vm.markRead()
        // Date labels sit between messages, so follow the list's own last item rather than a message index.
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topChrome + 4.dp, bottom = bottomChrome + 8.dp)) {
            if (state != null && messages.isEmpty() && typing.isEmpty()) item("empty") {
                Text(stringResource(if (group) R.string.im_gc_empty else R.string.im_partner_chat_empty), Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            messages.forEachIndexed { index, message ->
                val previous = messages.getOrNull(index - 1)
                if (previous == null || message.createdAt.toEpochMilli() - previous.createdAt.toEpochMilli() > ThreadTimeline.SEPARATOR_GAP_MILLIS) {
                    item("date-${message.id}") { ThreadDateLabel(message.createdAt) }
                }
                val grouped = previous?.senderId == message.senderId &&
                    message.createdAt.toEpochMilli() - previous.createdAt.toEpochMilli() <= ThreadTimeline.GROUP_GAP_MILLIS
                item(message.id.toString()) {
                    PartnerChatBubble(message, members.firstOrNull { it.id == message.senderId }, grouped,
                        host = group && message.senderId == state?.chat?.hostId)
                }
            }
            typing.mapNotNull { id -> members.firstOrNull { it.id == id } }.forEach { partner ->
                item("typing-${partner.id}") {
                    ThreadLiveStatusRow(partner, AssistantGenerationPhase.Waiting, R.string.im_thread_status_thinking)
                }
            }
            // A member waiting for the owner stops until answered, so its request sits at the end where the owner looks.
            state?.approvals.orEmpty().forEach { approval ->
                item("approval-${approval.tool.toolCallId}") {
                    PartnerChatApprovalCard(approval, members.firstOrNull { it.id == approval.memberId }) { id, approved ->
                        vm.answer(approval, id, approved)
                    }
                }
            }
        }
        EdgeBlurScrim(hazeState, EdgeScrimPosition.Top, topChrome + 16.dp, Modifier.align(Alignment.TopCenter))
        EdgeBlurScrim(hazeState, EdgeScrimPosition.Bottom, bottomChrome + 24.dp, Modifier.align(Alignment.BottomCenter))

        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .onSizeChanged { topChrome = with(density) { it.height.toDp() } }
            .statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassIconButton(hazeState, HugeIcons.ArrowLeft01, stringResource(R.string.back), onClick = { nav.popBackStack() })
            GlassSurface(hazeState, CircleShape, Modifier.weight(1f, fill = false).heightIn(min = 48.dp),
                onClick = if (group) ({ nav.navigate(Screen.GroupInfo(chatId.toString())) }) else null) {
                Row(Modifier.padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ImStackedAvatars(members, 36.dp) {}
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onSizeChanged { bottomChrome = with(density) { it.height.toDp() } }
            .imePadding().navigationBarsPadding()) {
            PartnerChatComposer(members, hazeState, enabled = state != null) { text, mentions -> vm.send(text, mentions) }
        }
    }
}

@Composable
private fun PartnerChatBubble(message: PartnerChatMessage, sender: Assistant?, grouped: Boolean, host: Boolean) {
    val owner = message.senderId == OWNER_PARTICIPANT_ID
    val menu = rememberPressMenu()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val maxWidth = (LocalConfiguration.current.screenWidthDp * .78f).coerceAtMost(600f).dp
    Row(Modifier.fillMaxWidth().padding(top = if (grouped) 4.dp else 16.dp),
        horizontalArrangement = if (owner) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!owner) {
            // Avatar once per run of messages from the same partner, like a group chat.
            Box(Modifier.size(36.dp)) {
                if (!grouped && sender != null) AssistantAvatar(sender.name, sender.avatar, Modifier.size(36.dp))
            }
        }
        Column(Modifier.padding(start = if (owner) 0.dp else 8.dp), horizontalAlignment = if (owner) Alignment.End else Alignment.Start) {
            val mentionsOwner = !owner && OWNER_PARTICIPANT_ID in message.mentions
            if (!owner && (!grouped || mentionsOwner) && sender != null) {
                Row(Modifier.padding(start = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!grouped) Text(partnerName(sender), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (host && !grouped) Text(stringResource(R.string.im_gc_host), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary)
                    if (mentionsOwner) Text(stringResource(R.string.im_gc_mentions_you), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            Box(Modifier.widthIn(max = maxWidth)) {
                Surface(shape = RoundedCornerShape(20.dp),
                    color = if (owner) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.imLongPress(menu::open)
                        .then(if (menu.at != null) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)) else Modifier)) {
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        MarkdownBlock(message.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                ImPressMenu(menu) {
                    ImPressMenuItem(HugeIcons.Copy01, stringResource(R.string.im_thread_copy)) {
                        menu.close()
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", message.text))) }
                    }
                }
            }
        }
    }
}

/**
 * The owner's composer. Tapping a partner above the field @-mentions it, so that partner answers;
 * with no mention the partner who spoke last answers.
 */
@Composable
private fun PartnerChatComposer(
    members: List<Assistant>,
    hazeState: dev.chrisbanes.haze.HazeState,
    enabled: Boolean,
    onSend: (String, List<Uuid>) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val mentions = remember { mutableStateListOf<Uuid>() }
    fun send() {
        if (input.isBlank() || !enabled) return
        onSend(input, mentions.toList())
        input = ""
        mentions.clear()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(members, key = { it.id.toString() }) { partner ->
                val selected = partner.id in mentions
                FilterChip(
                    selected = selected,
                    onClick = { if (selected) mentions.remove(partner.id) else mentions.add(partner.id) },
                    label = { Text("@" + partnerName(partner)) },
                    leadingIcon = { AssistantAvatar(partner.name, partner.avatar, Modifier.size(20.dp)) },
                    // Solid, since the chips float over the messages.
                    colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            }
        }
        GlassSurface(hazeState, RoundedCornerShape(28.dp), Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { field ->
                        Box {
                            if (input.isEmpty()) Text(stringResource(R.string.im_partner_chat_hint), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            field()
                        }
                    },
                )
                if (input.isNotBlank()) FilledIconButton(onClick = { send() }, enabled = enabled) {
                    Icon(HugeIcons.ArrowUp02, stringResource(R.string.im_thread_send))
                }
            }
        }
    }
}

/** Two partners overlapping in one avatar slot: the first behind at top-left, the second in front. */
@Composable
internal fun ImStackedAvatars(partners: List<Assistant>, size: Dp, onClick: () -> Unit) {
    val small = size * 0.68f
    Box(Modifier.size(size)) {
        partners.getOrNull(0)?.let { AssistantAvatar(it.name, it.avatar, Modifier.size(small), onClick = onClick) }
        partners.getOrNull(1)?.let {
            Box(Modifier.align(Alignment.BottomEnd).size(small).background(MaterialTheme.colorScheme.background, CircleShape).padding(2.dp)) {
                AssistantAvatar(it.name, it.avatar, Modifier.size(small - 4.dp), onClick = onClick)
            }
        }
    }
}

/** A member's request for the owner's OK, under its avatar and name like its messages. */
@Composable
private fun PartnerChatApprovalCard(approval: PartnerChatApproval, member: Assistant?, onApproval: (String, Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp)) { member?.let { AssistantAvatar(it.name, it.avatar, Modifier.size(36.dp)) } }
        Column(Modifier.padding(start = 8.dp).widthIn(max = (LocalConfiguration.current.screenWidthDp * .78f).dp)) {
            member?.let {
                Text(stringResource(R.string.im_gc_needs_ok, partnerName(it)), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            }
            if (approval.tool.isPartnerMessage()) ThreadPartnerMessage(approval.tool, member, onApproval)
            else ThreadToolPrompt(approval.tool, onApproval, onAnswer = { _, _ -> }, assistant = member)
        }
    }
}
