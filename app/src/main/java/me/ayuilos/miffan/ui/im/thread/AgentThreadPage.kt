package me.ayuilos.miffan.ui.im.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.animateScrollBy
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
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.ayuilos.miffan.ui.components.ui.EdgeBlurScrim
import me.ayuilos.miffan.ui.components.ui.EdgeScrimPosition
import me.ayuilos.miffan.ui.components.ui.GlassIconButton
import me.ayuilos.miffan.ui.components.ui.GlassSurface
import me.ayuilos.miffan.ui.components.ui.assistantGenerationPhase
import me.rerere.ai.core.MessageRole
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Filter
import me.rerere.hugeicons.stroke.MoreHorizontal
import me.rerere.hugeicons.stroke.Computer
import me.ayuilos.miffan.ui.im.computer.rememberPartnerComputer
import me.ayuilos.miffan.ui.pages.chat.AssistantBackground
import me.rerere.hugeicons.stroke.Reply
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.data.revision.RestoreResult
import me.ayuilos.miffan.data.thread.TimelineItem
import me.ayuilos.miffan.data.thread.ThreadNotice
import me.ayuilos.miffan.data.thread.previewText
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AgentThreadPage(
    assistantId: Uuid,
    focusMessageId: String? = null,
    initialText: String? = null,
    vm: AgentThreadVM = koinViewModel { parametersOf(assistantId) },
) {
    LifecycleResumeEffect(vm) {
        vm.setVisible(true)
        onPauseOrDispose { vm.setVisible(false) }
    }
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val computer by rememberPartnerComputer(assistantId)
    // One state, so the page never sees "loaded" before the items that came with it.
    val timelineState = vm.timelineState.collectAsStateWithLifecycle()
    val timeline by remember { derivedStateOf { timelineState.value.items } }
    val loaded by remember { derivedStateOf { timelineState.value.loaded } }
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
    val voiceUnavailable = stringResource(R.string.im_thread_voice_unavailable)
    val voiceSetup = stringResource(R.string.im_thread_voice_setup)
    val undoFailed = stringResource(R.string.im_thread_undo_failed)
    val fallbackTopic = stringResource(R.string.im_thread_topic)
    val labels = remember(assistantId) { mutableStateMapOf<Uuid, String>() }
    var input by rememberSaveable(assistantId.toString()) { mutableStateOf(initialText.orEmpty()) }
    var viewingNotice by remember { mutableStateOf<ThreadNotice?>(null) }
    var highlighted by remember { mutableStateOf<String?>(null) }
    var followLatest by remember { mutableStateOf(focusMessageId == null) }
    var jumping by remember { mutableStateOf(false) }
    // Decided once per visit: a thread left alone for a while opens on the partner, not on its old messages.
    var welcomeDecided by rememberSaveable { mutableStateOf(focusMessageId != null) }
    var welcomeCutoff by rememberSaveable { mutableStateOf<Long?>(null) }
    var historyShown by rememberSaveable { mutableStateOf(false) }
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val visibleErrors = errors.filter { filter == null || it.conversationId == filter }
    val topicLabel = filter?.let { topics[it]?.title?.takeIf(String::isNotBlank) ?: labels[it] ?: fallbackTopic }
    val status = threadHeaderStatus(timeline, generating)

    // The welcome hero stands in for the hidden history, so list rows and timeline indices differ.
    fun welcomeAt(): Instant? = welcomeCutoff?.takeIf { filter == null }?.let(Instant::ofEpochMilli)
    fun historySize(items: List<TimelineItem>): Int = welcomeAt()?.let { ThreadWelcome.historySize(items, it) } ?: 0
    fun rowOf(index: Int, items: List<TimelineItem>): Int {
        if (welcomeAt() == null) return index
        val history = historySize(items)
        return if (index < history) index else index - (if (historyShown) 0 else history) + 1
    }

    suspend fun scrollToLatest(animate: Boolean = false) {
        val lastIndex = rowOf(timeline.size, timeline) + visibleErrors.size - 1
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

    LaunchedEffect(loaded, timeline) {
        if (!welcomeDecided && loaded && timeline.isNotEmpty()) {
            welcomeDecided = true
            if (generating.isEmpty()) welcomeCutoff = ThreadWelcome.cutoff(timeline, Instant.now())?.toEpochMilli()
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
            // While the welcome hides the history, the top of the list is the welcome, not the oldest page.
            if (listState.isScrollInProgress && (dragging || !followLatest) && listState.firstVisibleItemIndex == 0 &&
                (welcomeCutoff == null || historyShown)) listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key else null
        }.distinctUntilChanged().collect { if (it != null && !jumping) vm.loadMore() }
    }
    LaunchedEffect(timeline, visibleErrors, loaded) {
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
                val items = vm.timeline.value
                if (!historyShown && index < historySize(items)) {
                    historyShown = true
                    withFrameNanos { }
                }
                listState.animateScrollToItem(rowOf(index, items))
                highlighted = messageId
            } else snackbar.showSnackbar(missingMessage)
        } finally { jumping = false }
    }
    LaunchedEffect(focusMessageId, loaded) {
        if (loaded && focusMessageId != null) jumpTo(focusMessageId)
    }
    fun showHistory() {
        historyShown = true
        followLatest = false
        scope.launch {
            withFrameNanos { }
            listState.scrollToItem(historySize(timeline))
            withFrameNanos { }
            // Rest the partner at the bottom so the end of the history shows right above it.
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.firstOrNull { it.key == "welcome" }?.let { hero ->
                val gap = layout.viewportEndOffset - layout.afterContentPadding - (hero.offset + hero.size)
                if (gap > 0) listState.animateScrollBy(-gap.toFloat())
            }
        }
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
    val headerPhase = assistantGenerationPhase(
        (timeline.lastOrNull { it is TimelineItem.Message && it.streaming && it.message.role == MessageRole.ASSISTANT } as TimelineItem.Message?)?.message,
        // Follows the header status, so the avatar already thinks while a sent message is being routed.
        loading = status != null,
    )
    val density = LocalDensity.current
    var topChrome by remember { mutableStateOf(0.dp) }
    var bottomChrome by remember { mutableStateOf(0.dp) }
    val hazeState = rememberHazeState()
    LaunchedEffect(bottomChrome) {
        // The composer grows (keyboard, attachments); keep the newest message above it while following.
        if (loaded && followLatest && !jumping) scrollToLatest()
    }
    @Composable
    fun TimelineRow(item: TimelineItem) {
        when (item) {
            is TimelineItem.DateSeparator -> ThreadDateLabel(item.at)
            is TimelineItem.Message -> ThreadMessageBubble(item, highlighted = highlighted == item.message.id.toString(),
                onReply = { replyTo(item) }, onFilter = { filterTo(item) }, onRegenerate = { vm.regenerate(item) },
                onQuote = { ref -> scope.launch { jumpTo(ref.messageId.toString()) } },
                onToolApproval = { id, approved -> vm.answerToolApproval(item, id, approved) },
                onToolAnswer = { id, answer -> vm.answerToolQuestion(item, id, answer) },
                computer = computer, onComputerScreen = { nav.navigate(Screen.PartnerScreen(assistantId.toString())) },
                assistant = assistant)
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
    val welcome = welcomeAt()
    SharedTransitionLayout {
    // During a welcome the big partner moves into the live status row while the partner works.
    CompositionLocalProvider(LocalThreadPartnerTransition provides ThreadPartnerTransition(this,
        handOff = welcome != null && welcomeDecided && status != null)) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        assistant?.let { AssistantBackground(it, Modifier.fillMaxSize().hazeSource(hazeState, zIndex = -1f)) }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topChrome + 4.dp, bottom = bottomChrome + 8.dp)) {
            if (welcomeDecided) {
                if (welcome == null) {
                    items(timeline, key = { it.key }) { TimelineRow(it) }
                } else {
                    // The list content is re-read lazily against the newest timeline, so split that same
                    // snapshot here: a split computed in an earlier composition can exceed a shrunk timeline.
                    val items = timeline
                    val history = historySize(items).coerceIn(0, items.size)
                    if (historyShown) items(items.subList(0, history), key = { it.key }) { TimelineRow(it) }
                    item(key = "welcome") {
                        val fill = !historyShown && history == items.size && visibleErrors.isEmpty()
                        ThreadWelcomeHero(assistant, welcome, headerPhase, historyHidden = !historyShown, fill = fill,
                            onShowHistory = ::showHistory,
                            modifier = if (fill) Modifier.fillParentMaxHeight() else Modifier)
                    }
                    items(items.subList(history, items.size), key = { it.key }) { TimelineRow(it) }
                }
            }
            items(visibleErrors, key = { "error-${it.id}" }) { error ->
                val segmentId = error.conversationId
                ThreadErrorBubble(error, canRetry = segmentId != null,
                    onRetry = { segmentId?.let(vm::retry); vm.dismissError(error) }, onDismiss = { vm.dismissError(error) })
            }
        }
        if (!loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.im_thread_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (filter == null && timeline.isEmpty() && visibleErrors.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(top = topChrome, bottom = bottomChrome)) {
                ThreadEmptyState(assistant) { followLatest = true; vm.sendText(it) }
            }
        }
        EdgeBlurScrim(hazeState, EdgeScrimPosition.Top, topChrome + 16.dp, Modifier.align(Alignment.TopCenter))
        EdgeBlurScrim(hazeState, EdgeScrimPosition.Bottom, bottomChrome + 24.dp, Modifier.align(Alignment.BottomCenter))

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .onSizeChanged { topChrome = with(density) { it.height.toDp() } }
            .statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(end = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(hazeState, HugeIcons.ArrowLeft01, stringResource(R.string.back), onClick = { nav.popBackStack() })
                    GlassSurface(hazeState, CircleShape, Modifier.weight(1f, fill = false).heightIn(min = 48.dp),
                        onClick = { nav.navigate(Screen.PartnerProfile(assistantId.toString())) }) {
                        Row(Modifier.padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // The reply's thinking and typing play here now that bubbles carry no avatar.
                            ThreadAvatar(assistant, headerPhase, error = generating.isEmpty() && visibleErrors.isNotEmpty(), modifier = Modifier.size(36.dp))
                            Column {
                                Text(assistantName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                // Fades between states and grows or shrinks the line as the status appears or clears.
                                AnimatedContent(status, transitionSpec = { fadeIn() togetherWith fadeOut() using SizeTransform(clip = false) }, label = "thread-status") { shown ->
                                    if (shown != null) Text(stringResource(shown.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                GlassIconButton(hazeState, if (computer?.showsEntry == true) HugeIcons.Computer else HugeIcons.MoreHorizontal,
                    if (computer?.showsEntry == true) stringResource(R.string.im_computer_screen_description, computer?.name.orEmpty())
                    else stringResource(R.string.im_p5_profile),
                    onClick = { nav.navigate(if (computer?.showsEntry == true) Screen.PartnerScreen(assistantId.toString())
                        else Screen.PartnerProfile(assistantId.toString())) }, modifier = Modifier.align(Alignment.CenterEnd))
            }
            if (filter != null && topicLabel != null) {
                val color = threadTopicColor(topics[filter]?.topicIndex ?: 0)
                GlassSurface(hazeState, CircleShape, Modifier.align(Alignment.CenterHorizontally)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(HugeIcons.Filter, null, Modifier.padding(start = 16.dp).size(16.dp), tint = color)
                        Text(stringResource(R.string.im_thread_only, topicLabel), Modifier.padding(start = 8.dp).widthIn(max = 240.dp), color = color,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { vm.setTopicFilter(null); followLatest = true }) { Icon(HugeIcons.Cancel01, stringResource(R.string.im_thread_dismiss), Modifier.size(18.dp)) }
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onSizeChanged { bottomChrome = with(density) { it.height.toDp() } }
            .imePadding().navigationBarsPadding()) {
            if (filter == null) reply?.let { target ->
                val color = threadTopicColor(target.topicIndex)
                GlassSurface(hazeState, RoundedCornerShape(20.dp), Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(HugeIcons.Reply, null, Modifier.padding(start = 16.dp).size(18.dp), tint = color)
                        Text(target.message.previewText(), Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { vm.setReplyTarget(null) }) { Icon(HugeIcons.Cancel01, stringResource(R.string.im_thread_dismiss), Modifier.size(18.dp)) }
                    }
                }
            }
            ThreadComposer(input = input, onInput = { input = it }, topicLabel = topicLabel,
                loaded = loaded, generating = generating.isNotEmpty(),
                onSend = { followLatest = true; vm.send(it) }, onStop = vm::stop,
                onError = { message -> scope.launch { snackbar.showSnackbar(message) } },
                onVoiceUnavailable = {
                    scope.launch {
                        val result = snackbar.showSnackbar(voiceUnavailable, actionLabel = voiceSetup, withDismissAction = true, duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) nav.navigate(Screen.SettingSpeech(recognition = true))
                    }
                },
                hazeState = hazeState)
        }
        // Outside the measured composer so showing it never shifts the list's padding.
        if (!atBottom || highlighted != null) GlassIconButton(hazeState, HugeIcons.ArrowDown01, stringResource(R.string.im_thread_latest),
            onClick = { highlighted = null; followLatest = true; scope.launch { scrollToLatest(animate = true) } },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomChrome + 8.dp))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomChrome))
    }
    }
    }
}
