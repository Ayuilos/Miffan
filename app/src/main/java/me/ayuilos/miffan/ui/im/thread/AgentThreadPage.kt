package me.ayuilos.miffan.ui.im.thread

import androidx.compose.ui.platform.testTag
import me.ayuilos.miffan.utils.NetworkMonitor
import org.koin.compose.koinInject
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import androidx.compose.foundation.lazy.LazyListState
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
import kotlinx.coroutines.flow.conflate
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.context.LocalSettings
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock01
import me.ayuilos.miffan.data.remotetask.SCHEDULED_RUN_FAILED
import me.ayuilos.miffan.data.remotetask.SCHEDULED_RUN
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
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Filter
import me.rerere.hugeicons.stroke.MoreHorizontal
import me.rerere.hugeicons.stroke.Computer
import me.ayuilos.miffan.ui.im.computer.rememberPartnerComputer
import me.ayuilos.miffan.ui.pages.chat.AssistantBackground
import me.rerere.hugeicons.stroke.Reply
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.ui.im.thinkingModelOf
import me.ayuilos.miffan.ui.im.thinkTimeOf
import me.ayuilos.miffan.ui.im.ThinkTime
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.data.revision.RestoreResult
import me.ayuilos.miffan.data.thread.TimelineItem
import me.ayuilos.miffan.data.thread.ThreadTimeline
import me.ayuilos.miffan.ui.context.SharedElementScope
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
    initialFiles: List<String> = emptyList(),
    vm: AgentThreadVM = koinViewModel { parametersOf(assistantId) },
) {
    LifecycleResumeEffect(vm) {
        vm.setVisible(true)
        onPauseOrDispose { vm.setVisible(false) }
    }
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val computer by rememberPartnerComputer(assistantId)
    // A partner on a computer gets its connection ready while its chat is open (not through a relay).
    me.ayuilos.miffan.ui.im.computer.RetainComputerConnection(assistant?.workspaceId?.toString(), throughRelay = false)
    // One state, so the page never sees "loaded" before the items that came with it.
    val timelineState = vm.timelineState.collectAsStateWithLifecycle()
    // The page itself must not read the timeline while composing: it changes with every streamed chunk, and the
    // whole page (header, composer, every row's lambdas) would recompose each time. The list content, effects and
    // the derived states below read it; only what they produce reaches the page.
    val timeline by remember { derivedStateOf { timelineState.value.items } }
    val loaded by remember { derivedStateOf { timelineState.value.loaded } }
    val hasItems by remember { derivedStateOf { timelineState.value.items.isNotEmpty() } }
    ThreadAutoRead { id ->
        timelineState.value.items.firstNotNullOfOrNull { item ->
            (item as? TimelineItem.Message)?.message?.takeIf { it.id == id && it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
        }?.previewText()
    }
    val generating by vm.generatingSegmentIds.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val filter by vm.topicFilter.collectAsStateWithLifecycle()
    val topics by vm.topics.collectAsStateWithLifecycle()
    val reply by vm.replyTarget.collectAsStateWithLifecycle()
    val merged by vm.undoableMerge.collectAsStateWithLifecycle()
    val newTopicPending by vm.newTopicPending.collectAsStateWithLifecycle()
    val favoriteNodes by vm.favoriteNodes.collectAsStateWithLifecycle()
    // A wrongly split topic and the earlier topics it can go back to.
    var mergeChoice by remember { mutableStateOf<Pair<Uuid, List<ThreadTopic>>?>(null) }
    val assistantName = threadAssistantName(assistant)
    val assistants = LocalSettings.current.assistants
    val mentionables = remember(assistants, assistantId) { assistants.filter { it.id != assistantId } }
    val nav = LocalNavController.current
    val delivery: me.ayuilos.miffan.data.remotetask.RemoteTaskDelivery = org.koin.compose.koinInject()
    val deliveries by delivery.summaries.collectAsStateWithLifecycle()
    val taskDelivery = deliveries[assistantId]
    LaunchedEffect(loaded) { if (loaded) me.ayuilos.miffan.utils.ThreadOpenTrace.end() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val missingMessage = stringResource(R.string.im_thread_missing_message)
    val voiceUnavailable = stringResource(R.string.im_thread_voice_unavailable)
    val voiceSetup = stringResource(R.string.im_thread_voice_setup)
    val undoFailed = stringResource(R.string.im_thread_undo_failed)
    val fallbackTopic = stringResource(R.string.im_thread_topic)
    var input by rememberSaveable(assistantId.toString()) { mutableStateOf(initialText.orEmpty()) }
    var viewingNotice by remember { mutableStateOf<ThreadNotice?>(null) }
    var highlighted by remember { mutableStateOf<String?>(null) }
    var followLatest by remember { mutableStateOf(focusMessageId == null) }
    var jumping by remember { mutableStateOf(false) }
    // Decided once per visit: a thread left alone for a while opens on the partner, not on its old messages.
    var welcomeDecided by rememberSaveable { mutableStateOf(focusMessageId != null) }
    var welcomeCutoff by rememberSaveable { mutableStateOf<Long?>(null) }
    var historyShown by rememberSaveable { mutableStateOf(false) }
    // Once the partner leaves the welcome to work it stays with the conversation, and when that work
    // ends the welcome shrinks to a marker instead of taking the partner back.
    var partnerLeft by rememberSaveable { mutableStateOf(false) }
    var welcomeSettled by rememberSaveable { mutableStateOf(false) }
    // The welcome's partner flies into the status row as a shared element, which needs a lookahead scope, and
    // inside one everything is measured twice. So the scope holds only the list, and only from a welcome until
    // the partner has landed; the list keeps its rows as they are when it moves in and out.
    var partnerLanded by rememberSaveable { mutableStateOf(false) }
    val inFlightScope = welcomeCutoff != null && !partnerLanded
    val listState = rememberLazyListStateAcross(inFlightScope)
    val atBottom by remember(listState) { derivedStateOf { !listState.canScrollForward } }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val visibleErrors = errors.filter { filter == null || it.conversationId == filter }
    // A topic without a title is called by its first words; kept per message, the timeline changes with every chunk.
    val topicPreviews = remember { ThreadTimeline.QuotePreviews() }
    val topicPreview by remember {
        derivedStateOf {
            filter?.let { id ->
                timeline.firstNotNullOfOrNull { item ->
                    (item as? TimelineItem.Message)?.takeIf { it.segmentId == id }?.message
                        ?.let(topicPreviews::of)?.takeIf(String::isNotBlank)
                }
            }
        }
    }
    val topicLabel = filter?.let { topics[it]?.title?.takeIf(String::isNotBlank) ?: topicPreview ?: fallbackTopic }
    val connectedSegments by vm.connectedSegmentIds.collectAsStateWithLifecycle()
    val online by koinInject<NetworkMonitor>().online.collectAsStateWithLifecycle()
    // One instance per change: a new one on every streamed chunk made every visible bubble recompose.
    val connection = remember(connectedSegments, online) { ThreadConnection(connectedSegments, online) }
    val latestConnection by rememberUpdatedState(connection)
    val status by remember { derivedStateOf { threadHeaderStatus(timeline, generating, latestConnection) } }
    val thinkHarder by vm.thinkHarder.collectAsStateWithLifecycle()
    val deepMessageIds by vm.deepMessageIds.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val partnerLevel = assistant?.reasoningLevel ?: ReasoningLevel.AUTO
    val thinkingModel = assistant?.let { settings.thinkingModelOf(it) }
    val thinkTime = thinkingModel?.let { thinkTimeOf(partnerLevel, it) }
    val thinksDeeply = thinkingModel != null && thinkingModel.resolveReasoningLevel(partnerLevel) == ThinkTime.DEEP.levelFor(thinkingModel)
    // How hard the partner thinks about the newest owner message; the header counts the seconds for the long tiers.
    val latestThinkTime by rememberUpdatedState(thinkTime)
    val depth by remember {
        derivedStateOf {
            val lastOwner = timeline.lastOrNull { it is TimelineItem.Message && it.message.role == MessageRole.USER } as TimelineItem.Message?
            if (lastOwner != null && lastOwner.message.id in deepMessageIds) ThinkTime.DEEP
            else latestThinkTime?.takeIf { it == ThinkTime.MORE || it == ThinkTime.DEEP }
        }
    }
    var busySince by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(status != null) { busySince = if (status != null) System.currentTimeMillis() else null }

    // The welcome hero stands in for the hidden history, so list rows and timeline indices differ.
    fun welcomeAt(): Instant? = welcomeCutoff?.takeIf { filter == null }?.let(Instant::ofEpochMilli)
    // Worked out once per timeline; the list, following the newest message and the composer all ask for it.
    val shownHistorySize by remember { derivedStateOf { welcomeAt()?.let { ThreadWelcome.historySize(timeline, it) } ?: 0 } }
    fun historySize(items: List<TimelineItem>): Int =
        if (items === timeline) shownHistorySize else welcomeAt()?.let { ThreadWelcome.historySize(items, it) } ?: 0
    fun rowOf(index: Int, items: List<TimelineItem>): Int {
        if (welcomeAt() == null) return index
        val history = historySize(items)
        return if (index < history) index else index - (if (historyShown) 0 else history) + 1
    }
    fun firstOwnerMessageSinceWelcome(): TimelineItem.Message? = timeline.drop(historySize(timeline))
        .firstOrNull { it is TimelineItem.Message && it.message.role == MessageRole.USER } as TimelineItem.Message?
    // Back after a while without opening the history: the first message starts a topic of its own.
    fun freshStart(): Boolean = welcomeAt() != null && !historyShown && firstOwnerMessageSinceWelcome() == null
    val startsFresh by remember { derivedStateOf { freshStart() } }
    // That topic, once started, until it is put back with an earlier one.
    fun welcomeTopic(): Uuid? {
        val segmentId = firstOwnerMessageSinceWelcome()?.segmentId ?: return null
        val history = timeline.take(historySize(timeline))
        return segmentId.takeIf {
            it != PENDING_SEGMENT && it !in generating && history.none { item -> item is TimelineItem.Message && item.segmentId == it }
        }
    }
    fun startMerge(segmentId: Uuid) {
        scope.launch {
            val targets = vm.mergeTargets(segmentId)
            when (targets.size) {
                0 -> Unit
                1 -> vm.merge(segmentId, targets.single().segmentId)
                else -> mergeChoice = segmentId to targets
            }
        }
    }

    suspend fun scrollToLatest(animate: Boolean = false) {
        val lastIndex = rowOf(timeline.size, timeline) + (if (newTopicPending) 1 else 0) + visibleErrors.size - 1
        if (lastIndex < 0) return
        // Already on screen, as a streaming reply is while it grows: scroll by what it grew. Scrolling to the item
        // first would show its start for a frame on every chunk, so the reply would flicker back to its top.
        if (!animate) listState.layoutInfo.let { layout ->
            layout.visibleItemsInfo.firstOrNull { it.index == lastIndex }?.let { last ->
                val remaining = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
                if (remaining > 0) listState.scrollBy(remaining.toFloat())
                return
            }
        }
        if (animate) listState.animateScrollToItem(lastIndex) else listState.scrollToItem(lastIndex)
        withFrameNanos { }
        // A message can be taller than the viewport; reaching its start is not reaching the end.
        val layout = listState.layoutInfo
        layout.visibleItemsInfo.firstOrNull { it.index == lastIndex }?.let { last ->
            val remaining = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
            if (remaining > 0) listState.scrollBy(remaining.toFloat())
        }
    }

    LaunchedEffect(loaded, hasItems) {
        if (!welcomeDecided && loaded && hasItems) {
            welcomeDecided = true
            if (generating.isEmpty()) welcomeCutoff = ThreadWelcome.cutoff(timeline, Instant.now())?.toEpochMilli()
        }
    }
    LaunchedEffect(status != null) {
        if (welcomeAt() == null) return@LaunchedEffect
        if (status != null) partnerLeft = true else if (partnerLeft) welcomeSettled = true
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
    // Follow new content with one long-lived collector, taking the newest state after each scroll. An effect keyed
    // on the timeline restarted on every streamed chunk and was cancelled while waiting for its frame, so a long
    // reply stayed at its start while it streamed and jumped to its end only when it finished.
    val latestErrorCount by rememberUpdatedState(visibleErrors.size)
    val latestNewTopic by rememberUpdatedState(newTopicPending)
    val followScroll by rememberUpdatedState<suspend () -> Unit> { scrollToLatest() }
    LaunchedEffect(listState) {
        snapshotFlow { timeline; latestErrorCount; latestNewTopic; loaded; Any() }
            .conflate()
            .collect {
                if (loaded && followLatest && !jumping && timeline.size + latestErrorCount > 0) {
                    withFrameNanos { }
                    if (followLatest && !jumping) followScroll()
                }
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
    val headerPhase by remember {
        derivedStateOf {
            assistantGenerationPhase(
                (timeline.lastOrNull { it is TimelineItem.Message && it.streaming && it.message.role == MessageRole.ASSISTANT } as TimelineItem.Message?)?.message,
                // Follows the header status, so the avatar already thinks while a sent message is being routed.
                loading = status != null,
            )
        }
    }
    // The newest secure key card still waiting, with its service: a key typed into the chat box can go there instead.
    val connectCards = koinInject<me.ayuilos.miffan.data.appsettings.ProviderConnectCards>()
    val keyCardSettings = me.ayuilos.miffan.ui.context.LocalSettings.current
    val keyCard by remember(keyCardSettings) {
        derivedStateOf {
            timeline.asReversed().firstNotNullOfOrNull { item ->
                (item as? TimelineItem.Message)?.message?.parts?.asReversed()?.firstNotNullOfOrNull { part ->
                    val tool = part as? UIMessagePart.Tool
                    if (tool == null || !tool.isPending || tool.toolName !in me.ayuilos.miffan.data.appsettings.SECRET_CARD_TOOLS) null
                    else (me.ayuilos.miffan.data.appsettings.resolveSecretTarget(tool.toolName, tool.inputAsJson(), keyCardSettings)
                        as? me.ayuilos.miffan.data.appsettings.ConnectTargetResult.Ok)?.let { tool to it.target.name }
                }
            }
        }
    }
    // A request of the partner's waits for the owner.
    val waitingForOwner by remember {
        derivedStateOf { timeline.any { it is TimelineItem.Message && it.message.parts.any { part -> part is UIMessagePart.Tool && part.isPending } } }
    }
    val density = LocalDensity.current
    var topChrome by remember { mutableStateOf(0.dp) }
    var bottomChrome by remember { mutableStateOf(0.dp) }
    val hazeState = rememberHazeState()
    LaunchedEffect(bottomChrome) {
        // The composer grows (keyboard, attachments); keep the newest message above it while following.
        if (loaded && followLatest && !jumping) scrollToLatest()
    }
    val starter by remember { derivedStateOf { threadStarter(timeline) } }
    @Composable
    fun TimelineRow(item: TimelineItem) {
        when (item) {
            is TimelineItem.DateSeparator -> ThreadDateLabel(item.at)
            is TimelineItem.TopicBoundary -> ThreadTopicBoundaryLine(item) { startMerge(item.segmentId) }
            is TimelineItem.Message -> CompositionLocalProvider(
                LocalToolCardActions provides remember(item.segmentId) { vm.toolCardActions(item) },
            ) {
                merged?.takeIf { it.anchor?.messageId == item.message.id }?.let { ThreadMergedLine(it, vm::undoMerge) }
                ThreadMessageBubble(item, highlighted = highlighted == item.message.id.toString(),
                onReply = { replyTo(item) }, onFilter = { filterTo(item) }, onRegenerate = { vm.regenerate(item) },
                favorited = item.node.id in favoriteNodes, onFavorite = { vm.toggleFavorite(item, assistantName) },
                onQuote = { ref -> scope.launch { jumpTo(ref.messageId.toString()) } },
                onToolApproval = { id, approved -> vm.answerToolApproval(item, id, approved) },
                onToolApprovals = { ids, approved -> vm.answerToolApprovals(item, ids, approved) },
                onToolAnswer = { id, answer -> vm.answerToolQuestion(item, id, answer) },
                computer = computer, onComputerScreen = { nav.navigate(Screen.PartnerScreen(assistantId.toString())) },
                assistant = assistant, connection = connection)
                starter?.takeIf { it.greetingId == item.message.id }?.let { starter ->
                    ThreadStarterQuiz(assistant, starter, enabled = generating.isEmpty()) {
                        followLatest = true
                        vm.sendText(it)
                    }
                }
            }
            is TimelineItem.Typing -> ThreadTyping(assistant, item.segmentIds, connection)
            is TimelineItem.Event -> if (item.origin.kind == SCHEDULED_RUN || item.origin.kind == SCHEDULED_RUN_FAILED) {
                val target = item.origin.link?.removePrefix("remote-task:")?.split('/')?.takeIf { it.size == 2 }
                ThreadTaskRunLine(item.origin.summary, failed = item.origin.kind == SCHEDULED_RUN_FAILED,
                    onOpen = target?.let { (host, task) -> { nav.navigate(Screen.RemoteTaskDetail(host, task)) } })
            } else ThreadEventLine(item.origin.summary)
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
    val timelineList: @Composable () -> Unit = {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().hazeSource(hazeState).testTag("thread_list"),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topChrome + 4.dp, bottom = bottomChrome + 8.dp)) {
            if (welcomeDecided) {
                if (welcome == null) {
                    items(timeline, key = { it.key }, contentType = ::timelineContentType) { TimelineRow(it) }
                } else {
                    // The list content is re-read lazily against the newest timeline, so split that same
                    // snapshot here: a split computed in an earlier composition can exceed a shrunk timeline.
                    val items = timeline
                    val history = historySize(items).coerceIn(0, items.size)
                    if (historyShown) items(items.subList(0, history), key = { it.key }, contentType = ::timelineContentType) { TimelineRow(it) }
                    item(key = "welcome") {
                        val fill = !historyShown && history == items.size && visibleErrors.isEmpty()
                        ThreadWelcomeHero(assistant, welcome, headerPhase, historyHidden = !historyShown, fill = fill,
                            partnerGone = welcomeSettled,
                            onShowHistory = ::showHistory,
                            modifier = if (fill) Modifier.fillParentMaxHeight() else Modifier,
                            onContinue = welcomeTopic()?.let { topic -> { startMerge(topic) } })
                    }
                    items(items.subList(history, items.size), key = { it.key }, contentType = ::timelineContentType) { TimelineRow(it) }
                }
            }
            if (newTopicPending) item(key = "new-topic") {
                ThreadDividerLine(stringResource(R.string.im_thread_topic_changed), listOf(stringResource(R.string.im_thread_undo) to vm::cancelNewTopic))
            }
            items(visibleErrors, key = { "error-${it.id}" }) { error ->
                val segmentId = error.conversationId
                ThreadErrorBubble(error, canRetry = segmentId != null,
                    onRetry = { segmentId?.let(vm::retry); vm.dismissError(error) }, onDismiss = { vm.dismissError(error) })
            }
        }
    }
    LaunchedEffect(welcomeSettled) {
        // Until the welcome's place for the partner has closed, it still belongs to the flight.
        if (welcomeSettled && !partnerLanded) { delay(1_000); partnerLanded = true }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        assistant?.let { AssistantBackground(it, Modifier.fillMaxSize().hazeSource(hazeState, zIndex = -1f)) }
        SharedElementScope(enabled = inFlightScope, Modifier.fillMaxSize()) { flight ->
            // During a welcome the big partner moves into the live status row when the partner starts working.
            CompositionLocalProvider(LocalThreadPartnerTransition provides flight?.let {
                ThreadPartnerTransition(it, handOff = welcome != null && welcomeDecided && (status != null || partnerLeft))
            }) {
                timelineList()
            }
        }
        if (!loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.im_thread_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (filter == null && !hasItems && visibleErrors.isEmpty()) {
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
                            ThreadAvatar(assistant, headerPhase, error = generating.isEmpty() && visibleErrors.isNotEmpty(), modifier = Modifier.size(36.dp),
                                running = status != null, runningRing = MaterialTheme.colorScheme.surfaceContainerHigh,
                                waiting = waitingForOwner)
                            Column {
                                Text(assistantName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                // Fades between states and grows or shrinks the line as the status appears or clears.
                                AnimatedContent(status, transitionSpec = { fadeIn() togetherWith fadeOut() using SizeTransform(clip = false) }, label = "thread-status") { shown ->
                                    if (shown != null) Text(threadStatusText(shown, depth, busySince), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                GlassIconButton(hazeState, if (computer?.showsEntry == true) HugeIcons.Computer else HugeIcons.MoreHorizontal,
                    if (computer?.showsEntry == true) {
                        if (computer?.space == true) stringResource(R.string.im_space_screen_description, threadAssistantName(assistant))
                        else stringResource(R.string.im_computer_screen_description, computer?.name.orEmpty())
                    }
                    else stringResource(R.string.im_p5_profile),
                    onClick = { nav.navigate(if (computer?.showsEntry == true) Screen.PartnerScreen(assistantId.toString())
                        else Screen.PartnerProfile(assistantId.toString())) }, modifier = Modifier.align(Alignment.CenterEnd))
            }
            taskDelivery?.let { delivered ->
                // Shown once after results arrive; the lines themselves stay in the timeline.
                GlassSurface(hazeState, RoundedCornerShape(18.dp), Modifier.fillMaxWidth(),
                    onClick = { delivery.dismiss(assistantId); nav.navigate(Screen.ComputerDetail(delivered.hostId)) }) {
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(HugeIcons.Clock01, null, Modifier.size(18.dp),
                            tint = if (delivered.failures > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text(if (delivered.failures > 0) stringResource(R.string.remote_task_banner_failed, delivered.runs, delivered.failures)
                            else stringResource(R.string.remote_task_banner, delivered.runs),
                            Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { delivery.dismiss(assistantId) }) {
                            Icon(HugeIcons.Cancel01, stringResource(R.string.im_thread_dismiss), Modifier.size(18.dp))
                        }
                    }
                }
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
                        Text(target.message.previewText(300), Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { vm.setReplyTarget(null) }) { Icon(HugeIcons.Cancel01, stringResource(R.string.im_thread_dismiss), Modifier.size(18.dp)) }
                    }
                }
            }
            ThreadComposer(input = input, onInput = { input = it }, topicLabel = topicLabel,
                loaded = loaded, generating = generating.isNotEmpty(),
                // Opening the history hints at picking it back up, so a long pause alone does not split.
                onSend = { followLatest = true; vm.send(it, freshStart = freshStart(), reviewedHistory = welcomeAt() != null && historyShown) },
                onStop = vm::stop,
                onError = { message -> scope.launch { snackbar.showSnackbar(message) } },
                onVoiceUnavailable = {
                    scope.launch {
                        val result = snackbar.showSnackbar(voiceUnavailable, actionLabel = voiceSetup, withDismissAction = true, duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) nav.navigate(Screen.SettingSpeech(recognition = true))
                    }
                },
                hazeState = hazeState, mentionables = mentionables, initialFiles = initialFiles,
                // Right after the welcome the next message starts fresh anyway.
                onNewTopic = if (newTopicPending || startsFresh) null else { { followLatest = true; vm.requestNewTopic() } },
                freshTopic = newTopicPending,
                onThinkHarder = if (thinkingModel != null) vm::setThinkHarder else null,
                thinkHarder = thinkHarder, thinksDeeply = thinksDeeply,
                keyCardService = keyCard?.second,
                onKeyToCard = { key -> keyCard?.let { (tool, _) -> connectCards.handOff(tool.toolCallId, key) } })
        }
        mergeChoice?.let { (segmentId, targets) ->
            ThreadMergeSheet(targets, assistantName,
                onPick = { target -> mergeChoice = null; vm.merge(segmentId, target.segmentId) },
                onDismiss = { mergeChoice = null })
        }
        // Outside the measured composer so showing it never shifts the list's padding.
        if (!atBottom || highlighted != null) GlassIconButton(hazeState, HugeIcons.ArrowDown01, stringResource(R.string.im_thread_latest),
            onClick = { highlighted = null; followLatest = true; scope.launch { scrollToLatest(animate = true) } },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomChrome + 8.dp))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomChrome))
    }
}

/** Rows of one kind reuse each other's compositions while scrolling; a message bubble never becomes a date line. */
private fun timelineContentType(item: TimelineItem): Any = item::class

/**
 * A list state that starts over, where the last one was, whenever [scope] changes. A LazyListState measured inside a
 * lookahead scope (the welcome's SharedTransitionLayout) keeps reading its lookahead layout after the list moves out
 * of it: the list stopped scrolling past the rows it had when the partner landed, until the thread was reopened.
 */
@Composable
private fun rememberLazyListStateAcross(scope: Boolean): LazyListState {
    val last = remember { arrayOfNulls<LazyListState>(1) }
    return rememberSaveable(scope, saver = LazyListState.Saver) {
        LazyListState(last[0]?.firstVisibleItemIndex ?: 0, last[0]?.firstVisibleItemScrollOffset ?: 0)
    }.also { last[0] = it }
}
