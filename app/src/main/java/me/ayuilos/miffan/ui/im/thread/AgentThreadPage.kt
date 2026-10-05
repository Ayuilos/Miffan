package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import me.ayuilos.miffan.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Cancel01
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.ui.components.nav.BackButton
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.revision.RestoreResult
import me.ayuilos.miffan.data.thread.TimelineItem
import me.ayuilos.miffan.data.thread.ThreadNotice
import me.ayuilos.miffan.data.thread.previewText
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun AgentThreadPage(
    assistantId: Uuid,
    focusMessageId: String? = null,
    vm: AgentThreadVM = koinViewModel { parametersOf(assistantId) },
) {
    LifecycleResumeEffect(vm) {
        vm.setVisible(true)
        onPauseOrDispose { vm.setVisible(false) }
    }
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val timeline by vm.timeline.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val generating by vm.generatingSegmentIds.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val filter by vm.topicFilter.collectAsStateWithLifecycle()
    val topics by vm.topics.collectAsStateWithLifecycle()
    val reply by vm.replyTarget.collectAsStateWithLifecycle()
    val assistantName = threadAssistantName(assistant)
    val nav = LocalNavController.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val missingMessage = stringResource(R.string.im_thread_missing_message)
    val undoFailed = stringResource(R.string.im_thread_undo_failed)
    val fallbackTopic = stringResource(R.string.im_thread_topic)
    val labels = remember(assistantId) { mutableStateMapOf<Uuid, String>() }
    var input by rememberSaveable(assistantId.toString()) { mutableStateOf("") }
    var overflow by remember { mutableStateOf(false) }
    var viewingNotice by remember { mutableStateOf<ThreadNotice?>(null) }
    var highlighted by remember { mutableStateOf<String?>(null) }
    var followLatest by remember { mutableStateOf(focusMessageId == null) }
    var jumping by remember { mutableStateOf(false) }
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val viewportEnd by remember { derivedStateOf { listState.layoutInfo.viewportEndOffset } }
    val visibleErrors = errors.filter { filter == null || it.conversationId == filter }
    val topicLabel = filter?.let { topics[it]?.title?.takeIf(String::isNotBlank) ?: labels[it] ?: fallbackTopic }

    suspend fun scrollToLatest(animate: Boolean = false) {
        val lastIndex = timeline.size + visibleErrors.size - 1
        if (lastIndex < 0) return
        if (animate) listState.animateScrollToItem(lastIndex) else listState.scrollToItem(lastIndex)
        withFrameNanos { }
        // A message can be taller than the viewport; reaching its start is not reaching the end.
        val layout = listState.layoutInfo
        layout.visibleItemsInfo.firstOrNull { it.index == lastIndex }?.let { last ->
            val remaining = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
            if (remaining > 0) listState.scrollBy(remaining.toFloat())
        }
    }

    LaunchedEffect(timeline, topics) {
        timeline.filterIsInstance<TimelineItem.Message>().forEach { message ->
            message.quote?.let { quote -> labels.putIfAbsent(quote.ref.conversationId, quote.preview) }
            message.message.previewText().takeIf { it.isNotBlank() }?.let { labels.putIfAbsent(message.segmentId, it) }
        }
    }
    LaunchedEffect(dragging, atBottom, highlighted, jumping) {
        // Programmatic scrolling must not turn off following new messages.
        if (!jumping && highlighted == null && (dragging || atBottom)) followLatest = atBottom
    }
    LaunchedEffect(listState, filter) {
        if (filter == null) snapshotFlow {
            if (listState.isScrollInProgress && (dragging || !followLatest) && listState.firstVisibleItemIndex == 0) listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key else null
        }.distinctUntilChanged().collect { if (it != null && !jumping) vm.loadMore() }
    }
    LaunchedEffect(timeline, visibleErrors, loaded, viewportEnd) {
        val count = timeline.size + visibleErrors.size
        if (loaded && followLatest && !jumping && count > 0) {
            withFrameNanos { }
            if (followLatest && !jumping) scrollToLatest()
        }
    }
    LaunchedEffect(highlighted) {
        if (highlighted != null) { delay(1800); highlighted = null }
    }
    suspend fun jumpTo(messageId: String) {
        jumping = true
        try {
            followLatest = false
            if (vm.timeline.value.none { it is TimelineItem.Message && it.message.id.toString() == messageId } && vm.topicFilter.value != null) {
                vm.setTopicFilter(null)
                delay(250)
            }
            var index = -1
            // The VM has no "has more" signal. Stop when a page no longer changes the oldest key.
            for (page in 0 until 20) {
                val current = vm.timeline.value
                index = current.indexOfFirst { it is TimelineItem.Message && it.message.id.toString() == messageId }
                if (index >= 0) break
                val oldest = current.filterIsInstance<TimelineItem.Message>().firstOrNull()?.key
                vm.loadMore()
                val changed = withTimeoutOrNull(1500) {
                    vm.timeline.first { it.filterIsInstance<TimelineItem.Message>().firstOrNull()?.key != oldest }
                }
                if (changed == null) break
            }
            if (index >= 0) {
                // Allow the collected timeline to reach the LazyColumn before using its index.
                withFrameNanos { }
                listState.animateScrollToItem(index)
                highlighted = messageId
            } else snackbar.showSnackbar(missingMessage)
        } finally { jumping = false }
    }
    LaunchedEffect(focusMessageId, loaded) {
        if (loaded && focusMessageId != null) jumpTo(focusMessageId)
    }
    fun replyTo(item: TimelineItem.Message) {
        vm.setTopicFilter(null)
        vm.setReplyTarget(item)
    }
    fun filterTo(item: TimelineItem.Message) {
        vm.setReplyTarget(null)
        vm.setTopicFilter(item.segmentId)
        followLatest = true
    }
    viewingNotice?.let { notice ->
        AlertDialog(onDismissRequest = { viewingNotice = null },
            title = { Text(stringResource(R.string.im_thread_view)) },
            text = { Column { Text(notice.summary); ThreadDateLabel(notice.at) } },
            confirmButton = { TextButton(onClick = { viewingNotice = null }) { Text(stringResource(R.string.im_thread_dismiss)) } },
            dismissButton = notice.trigger?.let { trigger ->
                { TextButton(onClick = { viewingNotice = null; scope.launch { jumpTo(trigger.messageId.toString()) } }) { Text(stringResource(R.string.im_thread_view)) } }
            },
        )
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { BackButton() },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AssistantAvatar(name = assistantName, value = assistant?.avatar ?: Avatar.Miffan(),
                                modifier = Modifier.size(36.dp), onClick = { nav.navigate(Screen.AssistantDetail(assistantId.toString())) })
                            TextButton(onClick = { nav.navigate(Screen.AssistantDetail(assistantId.toString())) }) {
                                Text(assistantName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { overflow = true }) { Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options)) }
                            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.im_thread_new_topic)) }, onClick = {
                                    overflow = false; vm.setTopicFilter(null); vm.setReplyTarget(null); vm.startNewTopic()
                                })
                            }
                        }
                    },
                )
                if (filter != null && topicLabel != null) {
                    val color = threadTopicColor(topics[filter]?.topicIndex ?: 0)
                    Surface(color = color.copy(alpha = .12f), contentColor = color, shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.im_thread_only, topicLabel), Modifier.padding(start = 16.dp).widthIn(max = 240.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { vm.setTopicFilter(null); followLatest = true }) { Icon(HugeIcons.Cancel01, stringResource(R.string.im_thread_dismiss), Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column(Modifier.imePadding().navigationBarsPadding()) {
                if (filter == null) reply?.let { target ->
                    Surface(color = threadTopicColor(target.topicIndex).copy(alpha = .12f), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = MaterialTheme.shapes.medium) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(12.dp)) {
                                Text(stringResource(R.string.im_thread_reply), style = MaterialTheme.typography.labelMedium)
                                Text(target.message.previewText(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { vm.setReplyTarget(null) }) { Icon(HugeIcons.Cancel01, stringResource(R.string.im_thread_dismiss), Modifier.size(18.dp)) }
                        }
                    }
                }
                ThreadComposer(input = input, onInput = { input = it }, topicLabel = topicLabel,
                    loaded = loaded, generating = generating.isNotEmpty(),
                    onSend = { followLatest = true; vm.send(it) }, onStop = vm::stop,
                    onError = { message -> scope.launch { snackbar.showSnackbar(message) } })
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp)) {
                items(timeline, key = { it.key }) { item ->
                    when (item) {
                        is TimelineItem.DateSeparator -> ThreadDateLabel(item.at)
                        is TimelineItem.Message -> ThreadMessageBubble(item, assistant, highlighted = highlighted == item.message.id.toString(),
                            onReply = { replyTo(item) }, onFilter = { filterTo(item) }, onRegenerate = { vm.regenerate(item) },
                            onQuote = { ref -> scope.launch { jumpTo(ref.messageId.toString()) } })
                        is TimelineItem.Typing -> ThreadTyping(assistant)
                        is TimelineItem.Notice -> ThreadNoticeLine(item.notice, assistantName,
                            onView = { viewingNotice = item.notice },
                            onUndo = {
                                vm.undoNotice(item.notice) { result ->
                                    if (result != RestoreResult.Restored) scope.launch { snackbar.showSnackbar(undoFailed) }
                                }
                            })
                    }
                }
                items(visibleErrors, key = { "error-${it.id}" }) { error ->
                    val segmentId = error.conversationId
                    ThreadErrorBubble(error, assistant, canRetry = segmentId != null,
                        onRetry = { segmentId?.let(vm::retry); vm.dismissError(error) }, onDismiss = { vm.dismissError(error) })
                }
            }
            if (!loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.im_thread_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (filter == null && timeline.isEmpty() && visibleErrors.isEmpty()) {
                ThreadEmptyState(assistant) { followLatest = true; vm.sendText(it) }
            }
            if (!atBottom || highlighted != null) FilledTonalButton(
                onClick = { highlighted = null; followLatest = true; scope.launch { scrollToLatest(animate = true) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            ) { Text(stringResource(R.string.im_thread_latest)) }
        }
    }
}
